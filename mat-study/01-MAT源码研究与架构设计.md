# 基于 MAT 的 Heap Dump 解析 + Web 后端：源码研究与架构设计

> 研究对象：Eclipse Memory Analyzer (MAT) 主仓库 `github.com/eclipse-mat/mat`
> 目标：① 解析模块（heapdump → index 文件）；② Web 后端（读 index → 按 MAT UI 模式展示），效率对齐 MAT 本地 UI。
> 本文档基于对 MAT 源码（`org.eclipse.mat.parser` / `org.eclipse.mat.hprof` / `org.eclipse.mat.api`）的实际阅读。

---

## 0. 一句话结论

MAT 之所以能在普通笔记本上交互式分析几十 GB 的堆，**核心不在于把整个堆读进内存，而在于解析期一次性把堆"预计算"成一组紧凑的、可随机访问的二进制 index 文件**；运行期所有查询（histogram、支配树、GC root 路径、retained size）都只在这些 index（基本是 `int[]`/`long[]` 数组语义）上做图遍历，对象的真实字段才按需 seek 回原始 hprof 文件读取。

**你的项目最务实的路线**：直接复用 `org.eclipse.mat.parser` + `org.eclipse.mat.hprof` + `org.eclipse.mat.api` 三个 jar（去掉 SWT/UI 依赖），用它们的 `SnapshotFactory.openSnapshot()` 拿到 `ISnapshot`，在其上包一层 REST/JSON。**不要自己重写解析器**——hprof 格式 + 支配树算法 + 压缩 index 格式的工程量极大且容易出错。

---

## 1. 整体数据流（解析管线）

源码入口：`SnapshotFactoryImpl.parse(File, prefix, args, listtypes, listener)`

```
heapdump.hprof
   │
   │  IIndexBuilder.fill(IPreliminaryIndex, monitor)        ← HprofIndexBuilder
   │     ├─ Pass1Parser.read()  扫描：类定义/字符串/对象地址→构建 id↔address 映射、类结构
   │     └─ Pass2Parser.read()  扫描：每个对象的 outbound 引用 → 写出边索引
   ▼
PreliminaryIndexImpl  (内存中的初步索引)
   │
   │  GarbageCleaner.clean(idx, builder, args, monitor)
   │     · 从 GC Roots 标记可达对象
   │     · 删除不可达对象、重新编号 object id（0..N-1 连续）
   │     · 重写 idx/o2c/a2s 索引，构建 inbound 索引、按序重写 outbound
   ▼
SnapshotImplBuilder.create()  →  SnapshotImpl
   │
   │  snapshot.calculateDominatorTree(monitor)              ← 支配树（最贵的一步）
   │     · DFS + Lengauer-Tarjan 类算法求每个对象的 immediate dominator
   │     · 计算 retained size，写 domIn/domOut/o2ret 索引
   │  snapshot.calculateMinRetainedHeapSizeForClasses()
   ▼
一组 *.index 文件落盘  →  之后可直接 reopen，无需再解析
```

进度权重（源码里 `new int[]{700,30,90,20,150,10}`）很说明问题：**Pass1+Pass2 解析占 700，支配树占 150**，是两个最重的阶段。

### 关键设计点
- **两遍扫描（two-pass）**：Pass1 只建立"对象地址 → 连续 id"和类元数据；Pass2 才真正解析引用关系。因为解析引用需要先知道所有地址的 id 映射。
- **垃圾清理后重新编号**：解析期的 id 和最终 id 不一样。`GarbageCleaner` 返回 `purgedMapping`，把不可达对象剔除并把 id 压缩成连续区间——这让后续所有索引都能用纯数组下标访问。
- **lock 文件**：`prefix + "lock.index"` 用 `FileChannel.tryLock` 防止并发解析同一 dump。
- **增量复用**：reopen 时若 `index` 文件比 hprof 新，直接 `SnapshotImpl.readFromFile()`，跳过整个解析。**这正是你 Web 后端启动加载 index 的路径**。

---

## 2. Index 文件清单（这是项目的"数据契约"）

来自 `IndexManager.Index` 枚举。文件名 = `<prefix><name>.index`，prefix 是去掉扩展名的 dump 路径。

| 枚举 | 文件名后缀 | 语义 | Reader 实现 |
|---|---|---|---|
| `IDENTIFIER` | `.idx.index` | object id → 对象地址(long) | `LongIndexReader` |
| `O2CLASS` | `.o2c.index` | object id → class id(int) | `IntIndexReader` |
| `A2SIZE` | `.a2s.index` | 数组/变长对象 id → size(编码int) | `SizeIndexReader` |
| `OUTBOUND` | `.outbound.index` | object id → N 个出边 object id | `IntIndex1NSortedReader` |
| `INBOUND` | `.inbound.index` | object id → N 个入边 object id | `InboundReader` |
| `DOMINATOR` | `.domIn.index` | object id → 其支配者 id | `IntIndexReader` |
| `DOMINATED` | `.domOut.index` | object id → N 个被它直接支配的 id | `IntIndex1NReader` |
| `O2RETAINED` | `.o2ret.index` | object id → retained size(long) | `LongIndexReader` |
| `I2RETAINED` | `.i2sv2.index` | class/loader → retained size 缓存 | `RetainedSizeCache` |

另外还有：
- `<prefix>.index` —— **master index**（`SnapshotImpl` 序列化，含 SnapshotInfo、类定义、GC roots、线程信息等）。
- `<prefix>.threads` —— 文本文件，线程栈与局部变量。
- `<prefix>.*.log` —— inbound 构建的中间文件（不常驻内存）。

> 注意：MAT 内部对象用 **int id（0..N-1）** 标识，不是地址。地址只在 `idx` 索引里。`mapAddressToId` / `mapIdToAddress` 做转换。这是所有索引能用数组高效访问的根本原因。

---

## 3. Index 二进制格式（自己读写时的精确规格）

来自 `IndexWriter.IntIndexStreamer` / `IndexReader.IntIndexReader`。

### 3.1 分页 + 位压缩
- `int` 索引页大小 `PAGE_SIZE_INT = 1_000_000`，`long` 索引页 `PAGE_SIZE_LONG = 500_000`。
- 每页用 `ArrayIntCompressed` / `ArrayLongCompressed` 做**位打包压缩**：扫描整页找出公共的前导清零位(leadingClearBits)和尾部清零位(trailingClearBits)，只存中间"变化位"(varyingBits)。
  - 页头 2 字节：`data[0]=varyingBits`，`data[1]=trailingClearBits`。
  - 例如一页全是小整数 → varyingBits 很小 → 极高压缩比。这对"对象地址(8字节但高位相同)""class id(数量远小于对象数)"特别有效。
- 页在内存中用 `SoftReference` 持有（`pages2` 是 `ConcurrentHashMap<Integer,SoftReference<ArrayIntCompressed>>`），内存紧张时被 GC 回收，下次访问从磁盘重读。**这是 MAT 内存可控的关键机制。**

### 3.2 文件尾部（footer）布局
写在所有页数据之后（`closeStream()`）：
```
[ page 0 bytes ][ page 1 bytes ] ... [ page k-1 bytes ]
[ pageStart[0] : long ]               ← 每页在文件中的起始偏移
[ pageStart[1] : long ]
   ...
[ pageStart[pages] : long ]           ← 末尾哨兵（= 页区结束位置）
[ pageSize : int ]
[ size : int ]                        ← 元素总数；为负时表示 size 超过 int 范围的编码
```
读取时（`IntIndexReader` 构造）：`seek(end-16)` 读最后 16 字节拿到 `lastOffset/pageSize/size`，反推页数：
```
pages = size/pageSize + (size%pageSize>0 ? 2 : 1)
```
然后回头读 `pages` 个 `long` 的页起始表，按需 seek 解压目标页。**O(1) 随机访问任意 object id。**

### 3.3 1→N 索引（outbound/inbound/dominated）
`IntArray1NWriter` / `IntArray1NSortedWriter`：用一个 body 流连续存所有目标 id（每组前面带长度 length），再用一个 `IntIndexStreamer` 存"每个 object id 在 body 中的起始偏移"。SortedWriter 把组按 id 排序，使下一组起点即上一组终点，省去显式长度。

---

## 4. HPROF 解析细节（Pass1 / Pass2）

- `AbstractParser.readVersion()`：校验 hprof 魔数（`JAVA PROFILE 1.0.x`），否则 `IOException: Not a HPROF heap dump`。
- 文件头：magic + identifierSize(4/8) + 时间戳。之后是一串 record（tag + time + length + body）。
- 关键 record：
  - `STRING_IN_UTF8` —— 字符串常量池（类名/字段名来源）
  - `LOAD_CLASS` —— class serial → class object id + name string id
  - `HEAP_DUMP` / `HEAP_DUMP_SEGMENT` —— 内含子记录：
    - `GC_ROOT_*` —— GC roots（多种类型）
    - `CLASS_DUMP` —— 类的字段布局、静态字段、父类、实例大小
    - `INSTANCE_DUMP` —— 普通对象的字段字节流
    - `OBJECT_ARRAY_DUMP` / `PRIMITIVE_ARRAY_DUMP` —— 数组
- **Pass1**（`Pass1Parser`）：建 id↔address、类结构、字符串、GC roots、线程栈。`determineDumpNumber()` 处理一个文件含多个 dump 的情况。
- **Pass2**（`Pass2Parser`）：用 Pass1 的地址映射，逐对象解析 outbound 引用并写边索引；可并行。
- 支持压缩 hprof：`ChunkedGZIPRandomAccessFile` / `CompressedRandomAccessFile` 让 gzip 后的 dump 仍可随机 seek。

---

## 5. 运行期查询能力（ISnapshot —— Web 后端要暴露的 API 面）

`org.eclipse.mat.snapshot.ISnapshot` 就是 MAT UI 背后的统一接口，你的 REST 层基本是它的薄包装：

| ISnapshot 方法 | 对应 MAT UI 功能 |
|---|---|
| `getHistogram(listener)` | Histogram 视图（按类聚合 shallow/retained） |
| `getClassesByName(name, subclasses)` | 类筛选 |
| `getObject(id)` / `getClassOf(id)` | Inspector / 对象详情 |
| `getOutboundReferentIds(id)` / `getInboundRefererIds(id)` | outgoing / incoming references 树 |
| `getImmediateDominatedIds(id)` / `getImmediateDominatorId(id)` | Dominator Tree 视图 |
| `getRetainedSet(ids,...)` / `getMinRetainedSet(...)` | Retained set / leak 分析 |
| `getPathsFromGCRoots(id, excludeMap)` | "Path to GC Roots"（找泄漏链） |
| `getMultiplePathsFromGCRoots(ids,...)` | Merge Shortest Paths |
| `getHeapSize(id)` / `getRetainedHeapSize(id)` | shallow / retained heap |
| `mapAddressToId` / `mapIdToAddress` | 地址跳转 |
| `getThreadStack(id)` | 线程栈视图 |

对象模型：`IObject`（基类）→ `IInstance` / `IClass` / `IClassLoader` / `IObjectArray` / `IPrimitiveArray`。字段通过 `IObject.getFields()` / `resolveValue()` 按需从 hprof 读取。

---

## 6. 复用方式（依赖关系）

`org.eclipse.mat.parser` 的 `MANIFEST.MF`：
```
Require-Bundle: org.eclipse.core.runtime, org.eclipse.mat.report, org.eclipse.mat.api
Export-Package: org.eclipse.mat.parser, .parser.index, .parser.io, .parser.model
```
- 真正需要的 jar：`org.eclipse.mat.api` + `org.eclipse.mat.report` + `org.eclipse.mat.parser` + `org.eclipse.mat.hprof`（+ `org.eclipse.mat.collect` 在 report 内）。
- **不需要**：`org.eclipse.mat.ui` / `.chart` / `.ui.rcp`（这些拖来 SWT/Eclipse RCP）。
- 唯一的"麻烦"是它们用了 Eclipse 的 **extension point** 机制（parser 注册、query 注册）来发现实现类。脱离 OSGi 运行时需要：
  1. 要么起一个最小化的 Equinox（headless），MAT 命令行 `org.eclipse.mat.api.parse` 走的就是这条路；
  2. 要么自己注册 parser（直接 `new HprofIndexBuilder()` + 手工驱动 `SnapshotFactoryImpl` 的等价流程），绕开 extension registry。
- 许可证：MAT 是 **EPL-2.0**。复用其 jar / 链接没问题；若你要分发，注意 EPL 的源码与声明义务。

---

## 7. 给你项目的两模块落地建议（概要，详见 02 文档）

### 模块 A：解析模块（CLI）
- 形态：一个独立可执行 jar，`parse <dump.hprof> [outDir]`，产出整套 `*.index`。
- 实现：复用 MAT headless 解析（推荐起 Equinox，或 fork MAT 的 `ParseHeapDump` 流程）。可加 `keep_unreachable_objects` / `discard_*` 等参数透传。
- 产物即 MAT 原生 index，**和 MAT GUI 完全互通**（GUI 也能直接打开你产出的 index，反之亦然）。

### 模块 B：Web 后端
- 启动时对每个 dump 调用 `SnapshotFactory.openSnapshot(file)`：若 index 存在且新，秒级加载（走 `readFromFile`）。
- 维护一个 `Map<dumpId, ISnapshot>` 常驻（注意 dispose / LRU，单个 snapshot 占内存约 `N*28.25 + C*1000` 字节估算见 MAT 文档）。
- REST 端点逐个映射 §5 的 ISnapshot 方法，返回 JSON；分页/懒加载对齐 MAT UI（树节点按需展开 → 对应 `getOutboundReferentIds` 等的按需调用）。
- 效率对齐要点：**绝不在请求里做全堆扫描**；histogram/dominator 这类聚合结果做进程内缓存；树视图严格懒加载，一次只返回一层子节点。

---

## 8. 待你确认的设计选择（影响后续实现方向）

1. **是否只支持 HotSpot 的 hprof**，还是也要 IBM 的 PHD/system dump（需 DTFJ，依赖更重）？
2. **后端是否要在自己进程内嵌 OSGi/Equinox**（兼容性最好），还是要彻底"去 Eclipse 化"（工作量大但部署轻）？
3. 解析与查询**是否同机**？还是解析在大内存机器、Web 后端只读 index（MAT 官方支持这种分离）？
4. 前端是否需要复刻 MAT 的 **Leak Suspects / Top Components 报告**（这些在 `org.eclipse.mat.api` 的 report/query 里，可复用）？
