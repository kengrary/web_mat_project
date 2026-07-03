# 基于 MAT 的智能 Heap Dump 分析与诊断平台 —— 项目蓝图

> 一句话定位：**复用 Eclipse MAT 的堆分析内核，构建一个"解析 → Web 查询 → 领域诊断 → 自然语言 Agent"四层递进的平台**，让任何人用一句话就能从 heap dump 里查出 OOM、连接池泄漏、线程泄漏等问题，并拿到带证据链的结论。

---

## 1. 背景与目标

Eclipse Memory Analyzer (MAT) 是业界最强的 Java 堆分析工具，但它有两个局限：
1. **只能本地 GUI 用**（SWT/RCP 桌面应用），无法做成 Web 服务、无法多人协作、难以集成到运维体系。
2. **门槛高**：histogram、支配树、retained size、GC root 路径这些概念，非专家很难驾驭；它默认聚焦 OOM，对连接池泄漏等问题没有开箱即用的诊断。

本项目的目标是**站在 MAT 的肩膀上**，解决这两点：

| 维度 | MAT 现状 | 本项目目标 |
|---|---|---|
| 形态 | 桌面 GUI | Web 服务（解析/查询分离部署） |
| 能力复用 | — | 直接复用 MAT 解析内核与算法（不重写） |
| 问题范围 | 主要 OOM | OOM + 连接池/线程池/ThreadLocal/ClassLoader 泄漏等 |
| 使用门槛 | 需专家 | 自然语言提问，Agent 自主诊断 |
| 效率 | 本地交互快 | 对齐 MAT 本地 UI 效率 |

**核心设计原则：不重写 MAT 解析器**。hprof 解析、支配树(Lengauer-Tarjan)、压缩 index 格式工程量极大且易错——我们复用 MAT 的 jar，把精力投在"服务化 + 领域知识 + 智能化"这三件 MAT 没做的事上。

---

## 2. 已确认的关键约束

| 约束 | 选择 | 理由 |
|---|---|---|
| Dump 格式 | 仅 HotSpot **hprof** | 最常见、依赖最轻（无需 IBM DTFJ） |
| 后端运行时 | 内嵌 **Equinox/OSGi** | 复用 MAT 的 extension point 机制，兼容性最好 |
| 部署模型 | **解析与查询分离** | 大内存机器解析产 index，查询机只读 index |
| 实现语言 | **Java**（核心层） | 直接复用 MAT jar；Agent 层可另选 |
| 主控/上传/队列 | Python **FastAPI** | async + SSE + 流式上传；复用已有 agent(手写 loop+OpenAI SDK) |
| 解析器制品 | **独立 jar**（parser-cli） | 子进程隔离内存，单独 JVM 参数 |
| 共享存储 | **共享 NFS** | POSIX 随机 seek，解析机写/查询机直接读，省掉本地副本层；靠 OS page cache 补随机小读 |
| 任务/元数据 | **数据库** | 队列(SKIP LOCKED 单消费者)、进度、dump 元数据持久化 |
| 首批视图 | Histogram / 支配树 / 线程树 / OQL | 见文档 06 的 API 契约 |

---

## 3. 四层架构总览

```
┌──────────────────────────────────────────────────────────────┐
│  L4  自然语言诊断 Agent（ReAct）           ← 文档 04           │
│      "有没有连接池泄漏？" → 自主规划 → 调工具 → 带证据的结论   │
├──────────────────────────────────────────────────────────────┤
│  L3  领域诊断层（Inspectors）              ← 文档 03           │
│      连接池/线程池/ThreadLocal/ClassLoader… 的语义化诊断       │
├──────────────────────────────────────────────────────────────┤
│  L2  Web 后端（REST/MCP，对齐 MAT UI）     ← 文档 01·02        │
│      ISnapshot 包装：histogram/支配树/引用/路径/OQL，懒加载    │
├──────────────────────────────────────────────────────────────┤
│  L1  解析内核（复用 MAT，hprof → index）   ← 文档 01·02        │
│      SnapshotFactory.openSnapshot() 跑完管线，产出 *.index    │
└──────────────────────────────────────────────────────────────┘
                         ▲
                复用 Eclipse MAT 内核
        (org.eclipse.mat: api / report / parser / hprof)
```

每一层都建立在下一层之上，可独立交付、独立演进。

---

## 4. 文档导航

| # | 文档 | 解决什么问题 | 核心结论 |
|---|---|---|---|
| 01 | [MAT 源码研究与架构设计](./01-MAT源码研究与架构设计.md) | 摸清 MAT 怎么实现、怎么复用 | 解析期把堆预计算成紧凑可随机访问的 index；运行期只在 index 上做图遍历。复用 4 个 jar 即可 |
| 02 | [核心算法深挖与落地方案](./02-核心算法深挖与落地方案.md) | 关键算法 + Web 后端怎么搭 | 解析就一句 `openSnapshot`；支配树=Lengauer-Tarjan；路径=BFS；REST 端点映射 + 懒加载原则 |
| 03 | [超越 OOM：连接池泄漏等排查](./03-超越OOM-连接池泄漏等问题排查思路.md) | 堆镜像还能查什么、怎么查 | 用 `resolveValue`+`@Subject` resolver 给领域对象叠加语义层；给出 HikariCP/Druid 等的泄漏信号字段 |
| 04 | [接口工具化：自然语言诊断 Agent](./04-MAT接口工具化-自然语言诊断Agent思路.md) | 怎么让 LLM 驱动分析 | MAT query 自带注解 = 现成 tool schema；三档工具粒度；出参瘦身是效果关键 |
| 05 | [全链路系统集成设计](./05-全链路系统集成设计.md) | Python 主控 + Java API 服务怎么集成落地 | 解析独立子进程隔离内存；分块上传/队列/进度；三层 SoftReference 防 Java 服务 OOM |
| 06 | [技术选型确认与四大视图 API 设计](./06-技术选型确认与四大视图API设计.md) | 选型固化 + 四大视图契约 | FastAPI/独立jar/共享NFS/数据库；Histogram(4分组)/支配树(对象+聚合)/线程树(三级)/OQL 的 API 与子功能 |
| 07 | [与现有 JVMind 系统对接落地方案](./07-与现有JVMind系统对接落地方案.md) | MAT 模块如何接入已成熟的平台 | 复刻 GC/jstack 报告范式；Java 只做计算不碰鉴权；融入共享 PostgreSQL + ReAct loop + 跨数据源联合诊断 |
| 08 | [MAT 功能盘点与实现缺口](./08-MAT功能盘点与实现缺口.md) | MAT 全部 57 个 query 盘点 + 缺口优先级 | 已实现四大视图；缺口里 Leak Suspects/path2gc/集合提取/两dump对比 最该补；先做通用 ResultRenderer 再批量接入 |
| 09 | [前端实现建议-复刻MAT体验](./09-前端实现建议-复刻MAT体验.md) | Web 前端怎么达到接近 MAT 的体验 | 核心=虚拟表+懒加载树+Tab+右键跳转+Inspector；视图联动是灵魂；MVP 先做 Overview/Histogram/Dominator |
| 10 | [MAT-Inspector能力盘点与实现建议](./10-MAT-Inspector能力盘点与实现建议.md) | Inspector 全部能力 + 已实现项 | 顶部信息表+4Tab；已实现字段/静态字段/Type/搜索/排序/值含Unicode/引用跳转 |
| 12 | [Python串通执行计划](./12-Python串通执行计划.md) | Python 接入 Java 侧的分阶段计划 | 阶段一解析闭环→二查询反代→三Agent；先做阶段一(独立、价值即时) |
| 13 | [OOM线程级诊断思路](./13-OOM线程级诊断思路.md) | OOM 触发线程识别 + SQL 提取 | 复用线程树/局部变量；启发式判元凶vs受害者；从栈帧提取真凶SQL（超越 MAT）|
| 14 | [heapdump前端落地设计-对齐真实端点](./14-heapdump前端落地设计-对齐真实端点.md) | heapdump 前端**可执行落地**设计（区别于 09 通用建议）| 端点映射表 + 异步任务4状态机 + 架构红线(前端只连Python) + 渐进披露(概览A→分析器B) + M1~M5 分期 |

> 推荐阅读顺序：README → 01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09。
> 前端落地另见：09（通用复刻 MAT 体验）→ 14（对齐真实端点的可执行设计 + 异步状态机 + 集成红线）。
> **重要背景**：本项目并非从零起步，而是接入一个已成熟运行的平台 **JVMind / ReAct Agent**（FastAPI + 自写 ReAct loop + 多用户/会话/计费/org + 已有 GC日志/jstack 分析）。文档 07 是当前最新、最贴合落地的对接方案，对前 6 份做了关键修正。
> MAT 源码已克隆在仓库 `mat-src/`，可随时对照任意实现细节。

---

## 5. 关键技术决策速查（贯穿四份文档）

1. **对象用 int id（0..N-1）标识**，不是地址——这是所有 index 能用数组高效访问的根本。地址只在 `idx` 索引和详情里出现。
2. **index 文件与 MAT 完全互通**——我们产出的 index 能被 MAT GUI 打开，反之亦然。
3. **reopen 仍需原始 hprof 在场**（读对象字段要 seek 回 hprof）——查询机要同时持有 hprof + index（文档 02 §F.1 标注的取舍点）。
4. **支配树最吃内存**（约 7 个 int[N] 数组），MAT 解析时主动 unload 索引省内存；Web 后端不重算，直接读盘。
5. **效率铁律**：永不在请求线程做全堆扫描；树视图严格懒加载（一次一层）；重计算异步化 + 缓存。
6. **领域诊断不碰 index 底层**——全部基于 `resolveValue` / collection extractor / paths2gc 等高层 API。
7. **工具化的最大杠杆**：`QueryDescriptor` 反射体系 → 自动生成 LLM tool schema。
8. **许可证 EPL-2.0**：复用/链接没问题，分发注意 license 与源码义务。

---

## 6. 里程碑路线图（端到端）

> 阶段间有依赖，但每个阶段都能独立产出可演示的价值。

### 阶段 0 · 复用验证（地基）
- [ ] 用 Tycho 构建或下载 MAT 1.15.x 发行版 jar
- [ ] 跑通命令行 `org.eclipse.mat.api.parse dump.hprof` 产出 index
- ✅ **验收**：拿到与 MAT GUI 互通的 index 文件

### 阶段 1 · 解析模块（L1）
- [ ] 包装成自有 CLI（嵌 Equinox），参数透传（keep_unreachable/discard/...）
- [ ] 大内存机器部署，产 index 供拷贝
- ✅ **验收**：`parse dump.hprof outDir/` 一键产出全套 index

### 阶段 2 · Web 后端骨架（L2）
- [ ] 嵌 Equinox + `openSnapshot` 加载已存在 index（秒级）
- [ ] `Map<dumpId, ISnapshot>` 常驻 + LRU/dispose
- [ ] 首批端点：`/overview` `/histogram`
- ✅ **验收**：浏览器看到概览 + Top 类直方图

### 阶段 3 · 核心交互视图（L2）
- [ ] outbound/inbound/dominator 懒加载端点
- [ ] paths-to-gc-roots、对象 Inspector、OQL
- [ ] 前端树组件（一次展开一层）
- ✅ **验收**：交互效率对齐 MAT UI

### 阶段 4 · 领域诊断（L3）
- [ ] 抽象 `PoolInspector` 接口 + 配置驱动字段映射
- [ ] 首批：**HikariCP + Druid** 连接池泄漏诊断
- [ ] 复用 MAT 内置：Leak Suspects、Duplicate Classes
- ✅ **验收**：一键报出"X 个连接借出超时未归还 + 泄漏点"

### 阶段 5 · 工具化 + Agent（L4）
- [ ] L2 工具网关：~8 原子工具 + 出参瘦身
- [ ] 复合诊断工具（diagnose_connection_pools 等）
- [ ] `QueryDescriptor → tool schema` 自动导出 + `search_tools`
- [ ] MCP Server 化 + 最小 ReAct 循环
- ✅ **验收**：自然语言"有没有连接池泄漏？"→ 带证据链的诊断结论

### 阶段 6 · 平台化
- [ ] 多 dump 对比（缓慢泄漏分析）、异步任务、结果缓存、脱敏
- [ ] 诊断 playbook 沉淀（专家排查套路结构化）

---

## 7. 复用的 MAT 组件清单

**需要的 bundle**（嵌 Equinox 时加载）：
- `org.eclipse.mat.api` —— ISnapshot、query 框架、内置 inspections
- `org.eclipse.mat.report` —— query 注册/注解体系、collect 压缩类、报告引擎
- `org.eclipse.mat.parser` —— 解析管线、index 读写、支配树
- `org.eclipse.mat.hprof` —— hprof 两遍解析器

**排除**（避免 SWT/RCP 依赖）：`org.eclipse.mat.ui` / `.ui.rcp` / `.chart` / `.chart.ui`

**环境**：建议 JDK 17/21（与最新 MAT 对齐；当前研究沙箱为 JDK 11）

---

## 8. 风险与注意事项

| 风险 | 说明 | 应对 |
|---|---|---|
| 构建复杂 | MAT 用 Tycho/p2/OSGi 构建，非普通 Maven | 优先用发行版 jar；或专门搭 Tycho 环境 |
| 内存占用 | 单 dump 约 `N×28.25 + C×1000` 字节常驻 | LRU + 引用计数 + 限制并发打开数 |
| 字段名漂移 | 连接池内部字段随版本变 | 配置驱动字段映射 + 降级容错 |
| LLM 结果爆炸 | 原始结果几万行/id | L2 强制出参瘦身 + 分页游标 |
| 敏感数据 | 堆里有密码/token 字符串 | 工具层可配置脱敏 + 长度限制 |
| 协议绑定 | Agent 框架耦合 | L2 统一 Tool 抽象，同出 MCP + REST |

---

## 9. 下一步可选项

1. **设计 L2 工具层 schema**：选 6~8 个核心工具，给出完整 JSON tool definition + 出参瘦身示例。
2. **设计 REST API 契约**（OpenAPI）+ 前端树视图懒加载交互。
3. **写 HikariCP/Druid 的 `PoolInspector` 原型代码**（伪代码 → 可编译骨架）。
4. **设计 MCP Server 工具清单**与 `QueryDescriptor → MCP schema` 映射方案。
5. **继续深挖 MAT 模块**：Leak Suspects 报告引擎 / 线程视图 / index 二进制逐字节验证。

---

*本蓝图与四份子文档均基于对 Eclipse MAT 主仓库（`github.com/eclipse-mat/mat`）源码的实际研读。MAT 采用 EPL-2.0 许可证。*
