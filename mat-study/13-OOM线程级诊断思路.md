# OOM 线程级诊断思路（触发线程识别 + SQL 提取）

> 两个高价值实战想法，都建立在**已实现的线程树 + 栈帧局部变量**之上，不需新基础设施。
> 本文给可行性、启发式算法、与已有能力的复用，由你定是否实现。

---

## 想法 1：识别 OOME 触发线程，判断"元凶 vs 受害者"

### 核心问题
OOM 发生时，dump 里所有线程都在，但**谁是真正撑爆内存的，谁只是恰好倒霉**？
- **元凶**：正在分配大对象 / 持有大 retained 的线程（它把堆吃满）。
- **受害者**：只是恰好在分配时撞上 OOM 抛出（堆已被别人吃满），它自己没占多少。

### 可行性（读 hprof 规范确认）
⚠️ **hprof 格式没有"哪个线程触发了 OOME"的专门标记**。`-XX:+HeapDumpOnOutOfMemoryError`
产出的 dump 里，触发线程通常是"当时正在分配、栈最深"的那个，但无显式字段。
**→ 需要启发式判断**（这恰好也是"元凶 vs 受害者"判断需要的——本就不是单一标记能定的）。

### 启发式算法（多信号打分）
对每个线程算几个信号，综合判断：
| 信号 | 含义 | 数据来源（已有能力） |
|---|---|---|
| **线程 retained heap 大** | 该线程（含栈局部变量）独占大量内存 = 元凶嫌疑 | `/threads` 已有 retained |
| **栈帧局部变量有超大对象** | 正在构建/持有大集合、大数组 | `/threads?thread=&frame=` 局部变量 retained |
| **栈里有 OutOfMemoryError 对象** | 该线程栈帧引用了 OOME 实例 = 触发点 | 栈帧局部变量类名 = java.lang.OutOfMemoryError |
| **栈顶在分配相关帧** | 栈顶是 `Arrays.copyOf`/`ArrayList.grow`/`StringBuilder.expand`/`HashMap.resize` 等 | 栈帧 text |
| **占总堆百分比** | 该线程 retained 占总堆 % 高 = 元凶 | retained / 总堆 |

判定规则（简单版）：
- **触发线程**：栈里有 OutOfMemoryError 对象 OR 栈顶在分配帧。
- **元凶**：retained 占总堆 > 阈值（如 30%）或局部变量有超大对象。
- **受害者**：是触发线程但 retained 很小（说明堆是被别的线程/对象吃满的）。
- 输出："线程 X 触发了 OOM，且它自己 retained 占 62% → 它是元凶" 或
  "线程 X 触发了 OOM，但它 retained 仅 2%，真正的大头是 com.example.Cache(58%) → X 是受害者，元凶在别处"。

### 实现（复用已有，新增一个诊断端点）
```
GET /oom-suspects?dumpDir=..
→ { triggerThreads:[{threadId,name,reason,retained,percentHeap,topFrame}],
    verdict:"culprit|victim|unclear", culpritHint:{objectId/class, percentHeap} }
```
- 内部：遍历线程（已有）→ 算上述信号 → 打分 → 结合 leak-suspects 的"最大 retained 对象"对比。
- 前端：在 Threads 视图 / Overview 顶部高亮"⚠ 疑似 OOM 触发线程"，点开看判定理由。

### 价值
直接回答用户最想问的"**到底是谁的锅**"——是这个报错线程的代码问题，还是它只是被别人拖垮。
省去人工在几百个线程里翻找。

---

## 想法 2：SQL 引起的 OOM —— 从栈帧局部变量提取 SQL 语句直接展示

### 核心场景
`SELECT * FROM huge_table`（无分页/无 WHERE）→ JDBC 把几百万行读进 ResultSet/ArrayList → OOM。
这类 OOM 的"真凶 SQL"**就藏在执行查询的那个线程的栈帧局部变量里**。

### 可行性（高）
执行 SQL 的线程，栈帧里一定有这些对象之一（都可达、都是字符串/含字符串）：
- `PreparedStatement` / `Statement` 实现类（MySQL `ClientPreparedStatement`、PG `PgStatement` 等）→ 字段里有 SQL 文本
- `String sql` 局部变量本身
- ORM 层：MyBatis `BoundSql.sql`、Hibernate `QueryImpl` 等
- 连接池包装的 statement

栈帧局部变量我们已能取（`/threads?thread=&frame=` → 局部变量对象 + Inspector 看字段值，String 内容已能显示，含 Unicode）。
**SQL 就是某个对象的某个 String 字段** → 用 `resolveValue` 沿已知字段路径取出。

### 实现（领域提取器 + 诊断端点）
```
GET /sql-in-threads?dumpDir=..
→ [{threadId, threadName, sql:"SELECT * FROM orders WHERE ...", source:"MySQL PreparedStatement",
    frame:"at c.e.OrderDao.list(OrderDao.java:42)", rowsBufferedHint:..}]
```
- **SQL 提取器**（类似连接池 Inspector，配置驱动字段路径，呼应文档03）：
  - 扫每个线程的栈帧局部变量对象；
  - 对已知 statement 类（可配置清单 + 字段路径），`resolveValue` 取 SQL 文本：
    - MySQL: `ClientPreparedStatement` → `sql` / `originalSql`
    - PG: `PgPreparedStatement` → 拼接的 query
    - MyBatis: `BoundSql` → `sql`
    - 兜底：局部变量里所有 String，正则匹配 `^\s*(SELECT|INSERT|UPDATE|DELETE|WITH)\b`（启发式抓 SQL 样子的字符串）
  - 关联：该 SQL 所在线程是否 retained 大 / 是否疑似 OOM 触发线程（结合想法1）。
- 前端：诊断卡片"⚠ 线程 X 正在执行 SQL：`SELECT * FROM ...`（疑似全表扫描，缓冲了 N 行）"。

### 价值
**一步定位真凶 SQL**，不用人工在栈帧里逐个翻局部变量 → Inspector → 找字段。
对"SQL 导致 OOM"这类高频问题，从"几小时排查"变成"一眼看到"。

---

## 与已有能力的关系（都是复用，不造轮子）
| 想法 | 复用 | 新增 |
|---|---|---|
| 1 OOM 触发线程 | /threads(retained)、栈帧局部变量、leak-suspects(最大retained对象) | 一个打分诊断端点 /oom-suspects |
| 2 SQL 提取 | 栈帧局部变量、resolveValue、String 值显示（含Unicode） | SQL 提取器(配置驱动字段) + /sql-in-threads |

两者可联动：**"线程 X 触发 OOM(想法1) + 它正在执行这条 SQL(想法2) → 真凶是这条全表扫描 SQL"**——
这就是给用户的完整因果链，远超 MAT 原生（MAT 不做这种领域级 SQL 提取）。

## 落地建议
- 都属 **P1 领域诊断**（差异化能力，超越 MAT），优先级高于长尾 query。
- 实现量不大（复用线程/局部变量/resolveValue），主要工作是**启发式规则 + 已知类字段映射**（配置驱动，耐版本变化，同连接池 Inspector 套路）。
- 建议先做 **想法2 SQL 提取**（场景明确、ROI 极高、判定简单），再做想法1（启发式更复杂）。
- 都做成**复合诊断工具**（文档04）：`mat_diagnose_oom` / `mat_find_sql`，agent 一步调用出结论。

## 实现进度
- [x] **想法2 SQL 提取 ✅ 已实现**（query-service `SqlExtractor` + `/sql-in-threads`）
  - 三层识别：已知 statement 类字段路径（MySQL 5/8、PostgreSQL、Oracle、SQL Server、MariaDB、
    H2/SQLite/DB2、MyBatis BoundSql、Hibernate、Druid/HikariCP 包装）+ 通用正则兜底 + 去重。
  - 风险分级（已验证）：HARMLESS（select 1 from dual 等心跳/校验，过滤）/ RISKY（SELECT */无WHERE/无分页）
    / UNKNOWN（有 WHERE 或有 LIMIT/ROWNUM/TOP/FETCH FIRST 分页 → 结果集受限不算高风险）。
  - 端点：`GET /sql-in-threads?dumpDir=..&onlyRisky=true`（默认只看有风险）。
  - 前端：🗄️ SQL in Threads 菜单，风险徽标 + SQL 文本 + 线程/帧/原因，可点进 Inspector。
- [x] **想法1 OOM 触发线程识别 ✅ 已实现**（query-service `OomDiagnosis` + `/oom-diagnosis`）
  - 多信号启发式：栈帧持有 OutOfMemoryError 实例 / 栈顶在分配帧(Arrays.copyOf/grow/resize/...) / retained 占总堆%。
  - 判定：触发线程 + 元凶(retained≥阈值,默认30%)/受害者(占比低→给出真正大头 culpritHint)。
  - 端点：`GET /oom-diagnosis?dumpDir=..&culpritPct=30`。
  - 前端：Overview 顶部醒目横幅（🔴元凶/🟡受害者）+ "查看该线程 SQL" 跳转。
- [x] **两者联动**：SQL 视图显示线程 retained（高风险SQL+大retained=元凶强证据）；
      Overview OOM 横幅 → 跳 SQL 视图。完整因果链：触发线程 + retained 占比 + 真凶 SQL。
- [x] **复合 agent 工具 `mat_diagnose_oom` ✅ 已实现**（query-service `OomComposite` + `GET /diagnose-oom`）
  - 同一 snapshot 租约聚合三路：OOM触发线程 + SQL风险 + Leak Suspects(leakhunter)。
  - 输出 `summary{verdict(sql_oom/thread_culprit/leak_suspect/victim/unclear), confidence, headline, recommendation}` + `sections{三路原始JSON}`。
  - 含 leakhunter（重）→ **异步任务**，提交返回 taskId，轮询 `/tasks/{id}`。参数 `culpritPct`(30)/`onlyRisky`(true)。
  - summary 用轻量正则从三路 JSON 取关键字段合成（不重算，已 JDK11 独立验证取值正确）。详见文档04 §2.2。
  - 前端：🧪 OOM 复合诊断 菜单（头条结论 + 三路 section 卡片）；真实模式走 taskId 轮询。
  - 限制：summary 为启发式非确定结论；真实带 OOM 的 hprof 尚未端到端单测。

## 待确认（想法1 / 后续）
1. 想法1 的"元凶"阈值（retained 占总堆 %）默认值？（建议 30%）
2. SQL 提取的已知类清单需扩充时，做成外部配置文件（YAML）还是代码常量？（当前代码常量）
