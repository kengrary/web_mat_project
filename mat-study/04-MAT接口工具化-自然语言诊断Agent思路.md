# 将 MAT 接口工具化：自然语言驱动的 Heap Dump 诊断 Agent

> 目标：用户用自然语言（"这个 dump 有没有连接池泄漏？""谁占了最多内存？"），
> 由一个 **ReAct Agent** 自主规划，调用我们封装的 MAT 工具集，从 heap dump 取数、分析、归因，
> 最终给出带证据链的诊断结论。
>
> 本文是思路文档，建立在前三份（解析/查询架构、核心算法、领域诊断）之上。

---

## 0. 核心判断：MAT 的接口"天生适合工具化"

读源码确认了一个关键事实——**MAT 的每个分析单元（query）都是自描述的**：

```java
@CommandName("histogram")                    // 工具名
@Help("...")                                 // 工具描述
@Category("...")                             // 分组
public class HistogramQuery implements IQuery {
    @Argument(isMandatory = false)           // 参数：是否必填
    public ... groupBy;
    @Argument(advice = Advice.HEAP_OBJECT)   // 参数语义：这是堆对象 id
    public int[] objects;
    public IResult execute(IProgressListener listener) { ... }
}
```

MAT 内部已有完整的反射体系把这些注解抽取成元数据：
`QueryRegistry → QueryDescriptor（name/category/help）→ ArgumentDescriptor（参数名/类型/必填/advice 语义）`。

**这几乎就是一份现成的 Tool Schema**。我们可以写一个适配器，把 `QueryDescriptor` 自动翻译成
LLM function-calling 的 JSON schema —— 一次性把 MAT 几百个 query 暴露成 agent 工具，
而不必手工逐个定义。这是整个方案最大的杠杆点。

---

## 1. 三层架构

```
┌─────────────────────────────────────────────────────────┐
│  L3  ReAct Agent（LLM 规划层）                            │
│      自然语言 → 思考 → 选工具 → 看结果 → 再思考 → 结论    │
└───────────────────────┬─────────────────────────────────┘
                        │ function calling (JSON)
┌───────────────────────▼─────────────────────────────────┐
│  L2  Tool 适配层（工具网关）                              │
│   · 工具注册：原子工具 + 复合诊断工具 + 自动导出的 query  │
│   · 入参校验/类型转换、出参"瘦身"（裁剪成 LLM 友好 JSON） │
│   · 会话态：当前 snapshot、对象集游标、上一步结果引用     │
└───────────────────────┬─────────────────────────────────┘
                        │ Java 调用
┌───────────────────────▼─────────────────────────────────┐
│  L1  MAT 能力层（前几份文档的 Web 后端）                  │
│   ISnapshot + QueryRegistry + 领域 Inspector（连接池等）  │
└──────────────────────────────────────────────────────────┘
```

> Agent 本身（L3）可以用 Python（LangGraph / 自研 ReAct 循环）或 Java（LangChain4j）实现；
> L2/L1 是我们的 Java Web 后端暴露的 HTTP 工具端点。Agent 与后端通过 HTTP/MCP 通信，**进程解耦**。

---

## 2. 工具分层设计（关键：粒度要为 LLM 优化，不是直接暴露 ISnapshot）

直接把 `getOutboundReferentIds(int)` 这种细粒度方法丢给 LLM 是灾难——
返回几万个 int、需要几十轮调用才能完成一次分析、token 爆炸。
正确做法是**分三档粒度**：

### 2.1 原子工具（Atomic）——低频、精确取数
| 工具 | 入参 | 返回（瘦身后） | 底层 |
|---|---|---|---|
| `get_overview` | dumpId | 对象数/类数/堆大小/GC root 数/JVM 信息 | SnapshotInfo |
| `get_histogram` | dumpId, top=N, groupBy | Top-N 类：name, 实例数, shallow, retained | getHistogram |
| `find_classes` | dumpId, namePattern | 匹配的类 + 实例数 | getClassesByName |
| `inspect_object` | dumpId, objectId | 类名、语义名、字段值(浅)、shallow/retained | getObject+resolveValue |
| `list_outbound` / `list_inbound` | dumpId, objectId, limit | 一层引用（id+类名+retained），分页游标 | get*ReferentIds |
| `paths_to_gc_roots` | dumpId, objectId | 最短引用链（已解析成可读名） | getPathsFromGCRoots |
| `dominator_top` | dumpId, parentId?, top | 支配树某层 Top-N | getImmediateDominatedIds |
| `run_oql` | dumpId, oql | 表格结果（截断+行数） | createQuery().execute |

### 2.2 复合诊断工具（Composite）——高价值、一步出结论
把"专家会做的多步操作"固化成单个工具，**大幅减少 agent 的思考轮数和出错率**：
| 工具 | 作用 | 内部编排 |
|---|---|---|
| `mat_diagnose_oom` ✅已实现 | 一步定调 OOM 元凶（SQL/线程/泄漏） | OOM触发线程 + SQL风险 + Leak Suspects 三路聚合 |
| `diagnose_connection_pools` | 扫所有连接池，报 active/idle/leak | 文档03 的 PoolInspector |
| `diagnose_thread_pools` | 线程池积压/线程泄漏 | ThreadPoolExecutor 字段 + 线程分组 |
| `diagnose_leak_suspects` | 复刻 MAT Leak Suspects 报告 | 复用 LeakHunterQuery |
| `diagnose_duplicate_classes` | ClassLoader 泄漏/重复类 | DuplicatedClassesQuery |
| `find_biggest_retainers` | 谁真正占内存（按 retained） | 支配树 Top + 归因 |
| `explain_why_alive` | 某对象为什么没被回收 | paths_to_gc_roots + 持有者解读 |

> 复合工具是 agent 可用性的关键。多数用户问题（"有没有泄漏"）应能被 1~2 个复合工具直接命中。

#### `mat_diagnose_oom`（✅ 已实现，query-service `OomComposite` + `GET /diagnose-oom`）
"为什么 OOM" 是最高频的问题，排查几乎总是连着问三步，固化成一个工具：
- **内部编排（同一 snapshot 租约，只开一次 dump）**：
  ① `OomDiagnosis`（OOM 触发线程 + 元凶/受害者判定）→ ② `SqlExtractor`（线程栈 SQL + 风险分级）
  → ③ `DiagnosticsView.leakSuspects`（leakhunter 全图分析）。
- **输出**：`summary{verdict, confidence, headline, recommendation, ...}` + `sections{oomDiagnosis, sqlInThreads, leakSuspects}`（三路原始结果原样嵌入，agent 需细节时直接读，不必再调单工具）。
- **verdict 合成规则**：元凶成立+有高风险SQL→`sql_oom`(high)；元凶成立→`thread_culprit`(high)；有高风险SQL→`sql_oom`(medium)；判受害者→`leak_suspect`(medium)；其余按触发线程有无降级。
- **成本/调用方式**：内含 leakhunter（可能几分钟）→ **异步任务**，提交返回 `taskId`，轮询 `/tasks/{id}` 取 `result`。参数 `culpritPct`(默认30) / `onlyRisky`(默认true)。
- **取舍**：不改三个单工具的 API，summary 用轻量正则从三路 JSON 取关键字段合成，**不重复计算**（已用 JDK11 独立程序验证取值正确）。
- **限制（诚实）**：summary 是经验法则启发式，非确定性结论；真实环境（带 OOM 的 hprof）尚未端到端单测。

### 2.3 自动导出工具（Auto-exported queries）——长尾覆盖
通过 `QueryDescriptor` 反射，把 MAT 全部内置 query 自动注册为 `mat_query.<commandName>` 工具，
schema 由注解生成。**默认不全部暴露给 LLM**（太多会撑爆 context），而是：
- 只把最常用的十几个标进"核心工具集"给 agent；
- 其余通过一个 `search_tools(keyword)` 元工具按需检索（见 §4 工具过多问题）。

---

## 3. 出参"瘦身"——让结果对 LLM 友好（最容易被忽视、最影响效果）

MAT 的原始结果（histogram 几千行、引用集几万 id）不能直接喂 LLM。L2 层必须做转换：

1. **截断 + 摘要**：默认只返回 Top-N，附 `total_count` 和"还有 N 条，用游标 X 继续"。
2. **id → 语义名**：对象不返回裸地址，返回 `{id, className, displayName, retainedHeap}`；
   displayName 用 MAT 的 resolver（连接池显示成 `HikariPool[active=48/50]`）。
3. **数值人性化**：字节数附带 `"523.4 MB"`，时间戳附带相对时长。
4. **稳定引用**：返回的对象用 `objectId`（int，稳定）作为后续工具的入参，agent 可串联。
5. **证据指针**：诊断结论里每个判断都带"证据" = 可点开的对象 id / 路径，便于人复核，也便于 agent 追问。

```jsonc
// get_histogram 返回示例（瘦身后）
{
  "top": [
    {"className": "byte[]", "instances": 120344, "shallow": "1.2 GB", "retained": "1.2 GB"},
    {"className": "com.zaxxer.hikari.pool.PoolEntry", "instances": 50, "retained": "8.1 MB"}
  ],
  "totalClasses": 4821,
  "cursor": "next:50"
}
```

---

## 4. ReAct 循环与 Agent 设计要点

### 4.1 典型轨迹（"有没有连接池泄漏？"）
```
User: 帮我看看这个 dump 有没有数据库连接泄漏
Thought: 先看有哪些连接池 → 用复合工具最快
Action: diagnose_connection_pools(dumpId)
Obs: HikariPool active=49/50, 其中 12 个连接 borrowedAge>30min（疑似泄漏）
Thought: 需要归因，挑一个最久的看谁持有
Action: paths_to_gc_roots(dumpId, objectId=<最久那个>)
Obs: Thread "http-nio-8080-exec-7" → ... → MyService.queryUser() 帧持有 connection
Thought: 借出栈指向 MyService.queryUser，未 close。结论清晰。
Final: 检测到 HikariCP 连接泄漏：12 个连接借出超 30 分钟未归还，
       泄漏点疑似 MyService.queryUser()（持有线程 http-nio-8080-exec-7）。证据：对象 #12345。
```

### 4.2 系统提示词要点
- 明确**领域知识**：先用复合诊断工具，不行再下钻原子工具；retained 比 shallow 更能说明"真凶"。
- **预算约束**：限制最大工具调用轮数（如 ≤15），避免无限下钻；大结果先看摘要再决定是否展开。
- **必须给证据**：每个结论附对象 id / 引用链，不能凭空断言。
- **不确定就说不确定**：dump 是静态快照，无法判断"正在归还中"，要标注置信度。

### 4.3 工具过多的治理（MAT 有几百个 query）
- **分级暴露**：核心工具集（~12-15 个）常驻 agent；全量 query 走 `search_tools(keyword)` 语义检索后临时挂载（RAG over tool descriptions）。
- **出参也要瘦（已落地）**：`/tools` 默认返回**精简版**——每工具只 `command + desc(help 第一句) + 必要参数(name/type/required/advice)`，
  并**过滤 MAT 内部参数**（snapshot/contexts，由服务用 dumpDir 代填，agent 不该看见）、**不含 default/长 help**。
  实测每工具 ~250 字节（比含 default/全 help 的完整版省 ~40%），12 个 core 工具约 3KB，可常驻 agent system。
  要细节时才 `?verbose=true` 单查（含 name/help/category/default，并已解 HTML 实体、清理脏 name/category）。
  > 教训：直接把 QueryDescriptor 全量元数据吐给 agent 会撑爆 context（leakhunter 单个 help ~300字、excludes 的 default 是一长串正则）。
  > 必须像查询出参一样对工具清单做"瘦身"。
- **工具描述即文档**：直接复用 `@Help` 注解内容做 embedding，检索质量天然不错。

### 4.4 会话状态与多 dump
- L2 维护会话：当前 `dumpId`、最近结果集（供 "把上一步那些对象按类分组" 这类指代）、对象游标。
- 支持对比：`compare_snapshots(dumpA, dumpB)`（复用 MAT 的 Comparison/DeltaHistogram）做"泄漏随时间增长"分析——这是定位缓慢泄漏的利器。

---

## 5. 协议选择：MCP vs 自定义 HTTP

- **推荐 MCP（Model Context Protocol）**：把 L2 做成一个 MCP Server，工具自动被各类 agent 框架/IDE 发现。我们的 `QueryDescriptor → MCP tool schema` 适配器是核心。
- 或**自定义 HTTP function-calling**：更轻，但与 agent 框架耦合。
- 两者可共存：L2 内部统一一套 `Tool` 抽象，对外同时提供 MCP 和 REST。

---

## 6. 性能与安全

1. **重操作异步化**：retained set / paths / OQL 可能很慢。工具返回 `taskId`，agent 轮询 `get_task_result`，或用流式进度（复用 `IProgressListener`）。给每个工具设超时。
2. **结果缓存**：histogram、支配树顶层、连接池诊断结果按 dumpId 缓存，agent 反复问不重算。
3. **只读 + 资源隔离**：所有工具只读 snapshot；限制单次返回大小、OQL 执行时长，防止 LLM 写出全表扫描把后端拖垮。
4. **快照生命周期**：agent 会话绑定 snapshot 引用计数，空闲 LRU dispose（每个 dump 内存占用见文档01估算）。
5. **隐私**：堆里可能有敏感数据（密码/token 字符串）。工具层对返回的 String 值做可配置脱敏/长度限制。

---

## 7. 与前几份文档的衔接 & 演进路线

1. **依赖地基**：本方案完全建立在"Web 后端 + ISnapshot + 领域 Inspector"之上，先把那层做扎实。
2. **M1**：实现 L2 的 ~8 个原子工具 + 出参瘦身 + 一个最小 ReAct 循环，跑通"谁占内存最多"。
3. **M2**：加复合诊断工具（先 `diagnose_connection_pools` + `diagnose_leak_suspects`），这是价值最高的一步。
4. **M3**：`QueryDescriptor → tool schema` 自动导出 + `search_tools` 元工具，覆盖长尾。
5. **M4**：MCP Server 化 + 多 dump 对比 + 异步任务 + 缓存。
6. **M5**：沉淀"诊断 playbook"——把专家排查套路写成 agent 可参考的结构化提示/子流程（连接池/线程/ClassLoader 各一套）。

---

## 8. 待确认的设计选择

1. **Agent 实现语言/框架**：Python(LangGraph) 还是 Java(LangChain4j)？（影响 L2 暴露方式：HTTP 还是进程内）
2. **协议**：优先做 MCP Server，还是先做自定义 HTTP function-calling？
3. **工具暴露策略**：先手工精选 ~15 个核心工具，还是一上来就做 `QueryDescriptor` 自动导出？
4. **交互形态**：一次性诊断报告（agent 跑完给结论），还是多轮对话式探索（用户可追问下钻）？
5. **首个端到端 demo 选哪个场景**：连接池泄漏 / 内存占用归因 / Leak Suspects 复刻？
