# 技术选型确认 + 四大核心视图 API 设计

> 本文固化已确认的技术选型，并对四大核心功能视图（Histogram / 支配树 / 线程树 / OQL）
> 做深化的 API 契约设计（含子功能）。基于对 MAT 源码中对应 query 结果结构的实际研读。

---

## 1. 已确认技术选型（最终决策）

| 决策点 | 选择 | 设计影响 |
|---|---|---|
| Python Web 框架 | **FastAPI** | 原生 async + SSE + 大文件流式上传 + Pydantic 模型/OpenAPI |
| 解析器制品 | **独立 jar**（parser-cli） | 与 Query 服务完全解耦，单独 JVM 参数，子进程隔离内存 |
| 共享存储 | **共享 NFS 存储** | POSIX 语义、支持随机 seek，Java 服务可直接在 NFS 路径 openSnapshot，**省掉本地副本层** |
| 任务/元数据 | **数据库** | 任务队列、进度、dump 元数据、上传会话持久化 |
| 首批功能视图 | **Histogram / 支配树 / 线程树 / OQL**（含子功能） | 见本文 §3 |

---

## 2. 共享 NFS 存储 + 数据库带来的架构调整

### 2.1 为什么 NFS 比对象存储更适合本场景（关键，存储方案的最终选择）
> MAT reopen index 时需要 **hprof + 全套 index 都在可随机 seek 的文件系统上**
> （读源码确认：MAT 用 `RandomAccessFile` + 多缓冲区 `BufferedRandomAccessInputStream` 做随机读，
> 读对象字段要 seek 回 hprof，index 页也是按需随机读）。

| 维度 | 共享 NFS（采用） | 对象存储（放弃） |
|---|---|---|
| 随机 seek | ✅ 原生 POSIX，`RandomAccessFile` 直接可用 | ❌ 不支持，必须先整文件下载到本地 |
| 是否需本地副本层 | **不需要**，Java 服务直接挂载路径 openSnapshot | 需要"下载→本地缓存→LRU 淘汰"一整层逻辑 |
| 解析/查询共享 | 同一挂载点，解析机写、查询机读，**零拷贝** | 解析 put、查询 get，两次网络传输 |
| 架构复杂度 | 低（就是个共享目录） | 高（多一层副本生命周期管理） |
| 一致性 | 解析机写完 fsync，查询机即可见（注意属性缓存，§2.2） | 最终一致，需处理可见性延迟 |

**结论：用 NFS 后，文档此前为对象存储设计的"本地工作副本/ensureLocal/副本 LRU"那一整层全部删除。**
解析机和查询机挂载同一 NFS 路径，dump 目录写一次、各处直接读。架构大幅简化。

```
        共享 NFS  (/nfs/heapdumps)   ← 解析机写, 查询机直接读, 无副本
          dumps/<dumpId>/app.hprof
          dumps/<dumpId>/*.index
   解析机 ─挂载─┘                └─挂载─ 查询机(Java) ──openSnapshot(/nfs/.../app.hprof)
```

### 2.2 NFS 的性能与正确性注意事项（必须处理）
NFS 能跑，但要针对"随机小读 + 跨网络"的特性调优，否则查询会慢：

1. **随机小读放大网络往返**：MAT 解压一个 index 页可能只读几 KB，NFS 每次随机读都有 RTT。
   - **对策 A**：挂载用较大 `rsize/wsize`（如 1MB）+ 开启 `ac`(属性缓存) + `actimeo` 合理值。
   - **对策 B（推荐）**：查询机内存留足，靠 **OS page cache** 兜底——index 页第一次从 NFS 读后缓存在本地内存，后续命中本地（这与 MAT 内部 SoftReference 页缓存叠加，命中率很高）。给查询机足够物理内存是关键。
   - **对策 C（可选优化）**：对热点 dump，可把 index（通常远小于 hprof）预读/预热到 page cache，或对超热 dump 仍允许复制 index 到本地 SSD（hprof 留 NFS）——但这是优化，不是必需。
2. **写后可见性**：parser-cli 在解析机写完 index 必须 **fsync + close**；任务标 DONE 前确保文件已落盘可见。查询机首次打开前，DB 状态=DONE 即保证可读（属性缓存可能有秒级延迟，必要时 `actimeo` 调小或首开重试）。
3. **半成品隔离**：解析中先写到 `dumps/<dumpId>/.tmp/`，全部完成 + fsync 后**原子 rename** 到正式目录——避免查询机读到半成品 index（NFS rename 同目录是原子的）。
4. **文件锁**：MAT 解析用 `FileChannel.tryLock` 写 `lock.index` 防并发解析。NFS 上 `flock`/`lockd` 行为依实现而定；但我们已用 **DB 队列保证全局单解析**，可不依赖 NFS 锁（甚至可关掉以免 NFS 锁异常）。
5. **句柄与卸载**：查询机 dispose snapshot 时关闭 NFS 文件句柄；删 dump 目录前确保所有查询机已 close（否则 NFS "silly rename"留 `.nfsXXXX` 残留）。
6. **挂载选项建议**：`hard,intr,timeo=,retrans=` 保证可靠；避免 `soft`（随机读中途超时会导致 index 读坏 → 解析/查询异常）。

> 一句话：NFS 让架构简单了，代价是要把"随机小读慢"用 **OS page cache + 大 rsize + 足够内存** 补回来，并用 **临时目录 + 原子 rename** 保证写后可见性与半成品隔离。

### 2.2 数据库 schema（任务 + 元数据 + 上传会话）
```sql
-- 上传会话(断点续传)
upload_session(upload_id PK, filename, total_size, chunk_size,
               received_chunks JSON, status, created_at, expires_at)

-- 分析任务(队列 + 进度)
parse_task(dump_id PK, filename, size_bytes,
           status,            -- QUEUED/PARSING/DONE/FAILED/CANCELLED
           progress REAL,     -- 0~1
           phase,             -- Pass1/Pass2/GC/DominatorTree...
           dump_dir,          -- NFS 上 dump 目录路径 (如 /nfs/heapdumps/dumps/<dumpId>)
           parse_args JSON,   -- keep_unreachable/discard/...
           error TEXT,
           queued_at, started_at, finished_at)

-- dump 元数据(解析完成后回填,供 UI/agent 快速展示,免开 snapshot)
dump_meta(dump_id PK, num_objects BIGINT, num_classes INT,
          num_gc_roots INT, heap_size BIGINT, jvm_info JSON,
          used_unreachable BOOLEAN, snapshot_identifier,
          created_at)
```
- **队列即 DB**：worker `SELECT ... WHERE status='QUEUED' ORDER BY queued_at LIMIT 1 FOR UPDATE SKIP LOCKED`，天然单消费者顺序 + 重启可恢复。无需额外 MQ（除非已有 Redis/RabbitMQ 想复用）。
- **dump_meta 缓存 overview**：前端/agent 要概览时直接查 DB，不必打开 snapshot，省内存。

---

## 3. 四大核心视图 API 设计（含子功能）

> 设计依据：读 MAT 源码确认各 query 的结果结构与分组维度。所有列表类返回均**分页 + 出参瘦身**。

### 3.1 Histogram（直方图）
MAT `HistogramQuery` 支持 4 种分组：`BY_CLASS / BY_SUPERCLASS / BY_CLASSLOADER / BY_PACKAGE`，
且可对**对象子集**做 histogram（不只是全堆）。

```
GET /snapshots/{dumpId}/histogram
    ?groupBy=class|superclass|classloader|package   (默认 class)
    &objectSet=<setId>      (可选: 对某结果集/对象集做直方图)
    &sort=retained|shallow|count                     (默认 retained 降序)
    &top=50&cursor=...
```
返回（瘦身）：
```jsonc
{
  "groupBy": "class",
  "rows": [
    {"label":"byte[]", "objectId": 12, "count": 120344,
     "shallow":"1.2 GB", "shallowBytes": 1288490188,
     "retained":"1.2 GB", "retainedBytes": 1290000000,
     "expandable": true}        // package 分组时可展开下钻
  ],
  "totalRows": 4821, "totalShallow":"...", "cursor":"next:50"
}
```
子功能：
- **package 分组树**：`expandable=true` 的行 → `?groupBy=package&parent=<path>` 下钻一层。
- **List objects**：某行 → `GET .../classes/{classId}/objects?cursor=` 列该类实例。
- **行内跳转**：→ histogram of that subset / dominator of that subset / list incoming refs。

### 3.2 支配树（Dominator Tree）
MAT `DominatorQuery` 支持 `BY_CLASS / BY_CLASSLOADER / BY_PACKAGE` 三种聚合，也支持**对象级树**。
两种模式都要：

```
# 模式 A: 对象级支配树(逐层懒加载) —— 找"谁真正占内存"
GET /snapshots/{dumpId}/dominator/objects
    ?parent=<objectId|ROOT>&top=50&sort=retained&cursor=...
# 返回: 该节点直接支配的对象, 带 retained/shallow/percent, expandable

# 模式 B: 聚合支配树(按类/包/类加载器分组)
GET /snapshots/{dumpId}/dominator/grouped
    ?groupBy=class|package|classloader&parent=<groupKey|ROOT>&top=&cursor=
```
返回（对象级，瘦身）：
```jsonc
{
  "parent": "ROOT",
  "rows": [
    {"objectId": 5567, "label":"com.example.Cache @ 0x7f..",
     "retained":"523 MB", "retainedBytes":548..., "percent": 45.7,
     "shallow":"...", "expandable": true}
  ],
  "totalRetainedOfParent":"...", "cursor":"next:50"
}
```
子功能：
- **逐层展开**：`expandable` 行 → 同端点 `parent=<objectId>`，对应 `getImmediateDominatedIds`。
- **percent 列**：retained 占父/占总堆百分比（MAT UI 的关键列）。
- **跳转**：节点 → inspect / paths2gc / 该子树 histogram。

### 3.3 线程树（Thread Overview）★ 三级树
MAT `ThreadOverviewQuery` 是三级树：**线程 → 栈帧 → 栈帧局部变量**。
线程节点有 name/daemon/priority/contextClassLoader/retained；局部变量带 retained size。

```
# 一级: 线程列表
GET /snapshots/{dumpId}/threads?sort=retained&cursor=
# 二级: 某线程的栈帧
GET /snapshots/{dumpId}/threads/{threadObjId}/frames
# 三级: 某栈帧的局部变量(对象)
GET /snapshots/{dumpId}/threads/{threadObjId}/frames/{frameIdx}/locals
```
返回（线程节点，瘦身）：
```jsonc
{
  "rows": [
    {"objectId": 88, "name":"http-nio-8080-exec-7",
     "daemon": true, "priority": 5,
     "contextClassLoader":"WebappClassLoader @ 0x...",
     "retained":"512 MB", "retainedBytes":...,
     "maxLocalRetained":"480 MB",   // MAT 的关键列: 单帧局部变量最大 retained
     "hasStack": true}
  ], "cursor": null
}
```
栈帧返回：`{frameIdx, text("at com.example.Svc.query(Svc.java:42)"), localsCount, maxLocalRetained}`
局部变量返回：`{objectId, label, declaredType, retained}`
子功能：
- **关联连接池/对象**：局部变量节点 → inspect / paths2gc（线程局部变量持有的连接 = 泄漏归因金矿，呼应文档03）。
- **contextClassLoader 跳转**：→ ClassLoader explorer（ClassLoader 泄漏排查）。

### 3.4 OQL（对象查询语言）✅ 已实现（含直方图视图 + 排序）
MAT `OQLQueryImpl`，类 SQL。结果三态：对象列表(int[]) / 自定义表格(IResultTable) / 标量。

```
POST /oql?dumpDir=...&view=list|histogram&sort=shallow|retained|count
{ "q": "SELECT * FROM java.lang.String s WHERE s.count > 1000",
  "limit": 200, "offset": 0 }
```
返回按 `kind` 分形态：
```jsonc
// kind=objectList（view=list，结果是对象集；可按 retained/shallow 排序）
{ "kind":"objectList", "rows":[{"objectId":321,"label":"\"abc...\"","shallow":"..","retained":".."}],
  "totalRows":1543, "truncated":true, "cursor":"offset:200" }

// kind=histogram（view=histogram，对结果对象集按类聚合 —— 直方图）★
{ "kind":"histogram", "resultObjects":1543,
  "histogram": { "rows":[{"classId":..,"label":"char[]","count":..,"shallow":"..","retained":".."}],
                 "sort":"retained", "totalRows":.. } }

// kind=table（SELECT 投影出列）/ kind=scalar（标量）/ kind=empty
```
子功能（已实现）：
- **直方图视图**：`view=histogram` → 后端对结果对象集调 `getHistogram(objectIds)` 按类聚合
  （复用 `HistogramView.bySubset`）。= MAT 的"OQL 结果 → Show as Histogram"。
- **排序**：`sort=shallow|retained|count`（histogram）；list 视图按 retained/shallow。retained 用近似。
- **错误友好**：OQL 语法错误返回 `{error, position}`，前端编辑器高亮。
- **安全**：返回行上限 + （重查询）执行时长限制，防全表扫描拖垮服务。
> 前端：编辑器 + 视图下拉(列表/直方图) + 排序下拉，切换自动重跑；结果按 kind 渲染，行可右键联动。

#### 结果集复用（resultSetId）✅ 已实现 —— MAT "分析流"的灵魂
OQL 等产出对象集的端点，返回里带 **`resultSetId`**（后端 `ResultSetCache` 软引用缓存这批对象 id）。
之后对这批对象**换角度再分析**，只传短 id，不重查、不回传几万 id：
```
GET  /histogram?dumpDir=..&objectSet=rs-xxx          → 这批对象按类聚合
POST /retained-set?dumpDir=..&objectSet=rs-xxx        → 这批对象总 retained（异步 taskId）
POST /merge-paths?dumpDir=..&objectSet=rs-xxx         → 这批对象合并到 GC root 路径
```
- 缓存 SoftReference：内存紧张可回收，过期返回 409（前端重跑产出步骤即可）；按 dumpDir 隔离防串号。
- 典型分析流：`OQL 查出借出超时连接 → rs-1 → retained_set(rs-1) 看占用 → merge_paths(rs-1) 看持有者`，三步同一 rs。
- 范围：histogram / retained-set / merge-paths 支持 objectSet（对子集做这些语义自然）；
  dominator 不支持（支配树是全局 ROOT 树，"对任意子集做支配树"语义不直接，MAT 亦如此）。
- 前端：OQL 结果顶部"结果集复用条"，一键 Histogram / Retained Set。

> resultSetId 也可由其它视图产出（histogram 选中类的实例集、dominator 子树等）→ 通用投喂，
> 复刻 MAT 的"行内右键 → 对这批对象做 X"。当前已打通 OQL → 其它视图这条主链。

---

### 3.5 ThreadLocals 全局视图 ✅ 已实现（ThreadLocal 泄漏排查）
> 线程池复用线程导致 ThreadLocal 不清理 → 残留对象泄漏，是高频泄漏源。本视图专注找它。

原理（读源码确认）：MAT 解析时把线程持有的 ThreadLocal 值识别成线程对象的特殊出引用
`ThreadToLocalReference`。所以不必手工解析 `Thread.threadLocals→ThreadLocalMap→Entry[]`，
只需遍历每个线程 `getOutboundReferences()` 筛 `instanceof ThreadToLocalReference`。
```
GET /threadlocals?dumpDir=..&groupBy=valueClass|thread&top=100
```
- **groupBy=valueClass（默认，找泄漏）**：按 ThreadLocal 值的类聚合，retained 降序。
  `count 大 + 业务类` = 泄漏嫌疑（如 UserContext count=50 → 50 个线程各残留一个请求上下文）。
  返回 `rows:[{valueClass,count,retained,sampleObjectId}]`。
- **groupBy=thread**：每个线程 → 它持有的 ThreadLocal 值列表（看某线程残留了什么）。
- 前端：左侧菜单 🧷 ThreadLocals，聚合切换；行可点进 Inspector / 右键联动。

### 3.6 Overview（概览 + JVM 信息）✅ 已实现
对应 MAT 的 "Heap Dump Overview" + "System Properties"。
```
GET /overview?dumpDir=..            # 精简版（也用于给 V1 解析回填 stats，保持轻量）
GET /overview?dumpDir=..&full=true  # 完整版（概览页）
```
完整版含：
- 基础：对象数/类数/类加载器/GC Roots/堆大小/JVM 版本/标识符大小/创建时间
- **Heap Dump Info**：堆格式($heapFormat)、压缩指针($useCompressedOops)、文件路径/大小、快照标识
- **System Properties**：从堆里取 `java.lang.System.props`（用 MAT `CollectionExtractionUtils.extractMap`
  提取，跨 JDK/Map 类型）→ java.version/os.name/sun.java.command(启动命令)/时区/编码 等。
- 前端：数字卡片 + Heap Dump Info kv + System Properties 可搜索表。

---

## 4. 跨视图的统一约定（贯穿四视图）

1. **对象统一标识**：一律用 `objectId`(int) 作为游标/入参；`label` 用 MAT resolver 生成的可读名（地址只在 inspect 详情给）。
2. **分页游标**：所有列表 `cursor` + `totalRows`，前端无限滚动 / 后端缓存结果集（层 C 软引用）。
3. **可展开标记**：树节点带 `expandable`，前端一次只取一层（懒加载，对齐 MAT UI 效率）。
4. **结果集对象（resultSetId）**：histogram 行、OQL 结果、dominator 子树都能产出"对象集"，互相投喂 → 复刻 MAT 的"行内右键 → 对这批对象做 X"。
5. **统一行内操作**：任一对象/分组行支持 → `inspect` / `paths2gc` / `outbound` / `inbound` / `histogram-of-set` / `dominator-of-set`。

---

## 5. FastAPI 侧落地要点

- **分块上传**：`UploadFile` + 流式 `await request.stream()` 写盘/直传对象存储 multipart，**不进内存**。
- **SSE 进度**：`StreamingResponse` 推 `parse_task` 进度；DB 轮询变更或监听通知。
- **异步代理**：用 `httpx.AsyncClient` 转发到 Java Query 服务，超时/重试/熔断。
- **Pydantic 模型**：四视图返回结构定义为 Pydantic model，自动生成 OpenAPI 给前端。
- **队列消费**：独立 worker 协程/进程 `SKIP LOCKED` 取任务 → `asyncio.create_subprocess_exec` 跑 parser-cli → 解析 stdout 进度。

---

## 6. 对前述文档"待确认项"的回填

- 文档05 §11：① FastAPI ② parser-cli **独立 jar** ③ **对象存储 + 本地工作副本** ④ **数据库做队列/元数据** ⑤ 首批 **Histogram/支配树/线程树/OQL**。
- 文档04 §8 / 文档03 §8 的部分选择可后续随实现推进再定（连接池优先级、agent 框架已确认手写 loop + OpenAI SDK）。

---

## 7. 更新后的最小里程碑

1. **M1**：FastAPI 分块上传(流式写 NFS) + DB 队列 + worker fork parser-cli(独立 jar，写 .tmp 后原子 rename) + SSE 进度/中断。
2. **M2**：Java Query 服务 + SnapshotManager(直接 openSnapshot NFS 路径 + 三层软引用) + `/overview`(走 dump_meta) + `/histogram`(全 4 分组)。
3. **M3**：支配树(对象级+聚合) + 线程树(三级) + OQL，全部分页懒加载；前端类 MAT 菜单 + 行内跳转。
4. **M4**：结果集互投喂(resultSetId) + 领域诊断端点 + 内存兜底。
5. **M5**：agent 工具封装接入已有 loop。
