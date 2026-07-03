# query-service — Java MAT Query 服务（L2 交互查询）

> 链路定位（见 `mat-study/`）：常驻 Java 服务，**reopen NFS 上的 index** 提供两类能力：
> 1) **给 V1 解析回填 stats**（`/overview` → 写 heapdump_reports.stats）；
> 2) **四大视图交互查询**（histogram/支配树/线程树/OQL）。
> 只被 Python 反向代理调用，**无鉴权，仅靠网络 ACL，绑定 127.0.0.1**。

## 已实现（M2 范围）
- `SnapshotManager` —— ★ **三层 SoftReference + 引用计数**的核心（防 Java 服务 OOM）。
- `/health`、`/overview`（回填 stats）、`/histogram`（BY_CLASS，分页+瘦身）。
- `MemoryWatcher` —— 内存兜底，逼近阈值主动 dispose 空闲 snapshot。
- `Json` —— 出参瘦身工具（截断/人性化字节/转义）。

## 架构要点

### SnapshotManager：三层软引用怎么协作（最关键）
- **层 A（MAT 内部，已存在）**：MAT 用 SoftReference 缓存 index 页，内存紧张时回收、按需从 NFS 重读。我们不重复造。
- **层 B（本服务）**：`SoftReference<SnapshotHolder>` 持有整个 snapshot。内存紧张时可被 GC 回收，
  下次 `acquire` 因 index 仍在 NFS → `openSnapshot` 走 readFromFile 秒级重建。
- **引用计数防回收**：`acquire()` 返回 `Lease`（try-with-resources）。Lease 持有 holder 的**强引用**，
  请求作用域内即便软引用被清，正在服务的请求也不会丢 snapshot；`close()` 减计数。
- **并发上限**：`Semaphore(maxOpen)` 限制同时打开的 dump 数，防一次涌入多个大 dump 直接 OOM。

> 用法范式（所有查询都走这个）：
> ```java
> try (var lease = manager.acquire(dumpDir)) {
>     ISnapshot s = lease.snapshot();
>     return HistogramView.byClass(s, top, offset, sort);
> }   // 自动 release 减计数
> ```

### 为什么没用 Spring
仅内网、轻量、只读查询 → JDK 内置 `HttpServer` + 自带 `Json` 足够，零额外依赖、启动快、内存小。
落地若要 Spring Boot 生态可平移，但非必需。

### dumpDir 由调用方传入（ByExplicitDir）
契合"Python 当唯一权威 + 网络 ACL"：Java 不查 DB，dumpDir 作为可信内网请求参数传入。
也可切 `ByConvention`（root/<dumpId>/app.hprof）用 /snapshots/{dumpId}/ 风格。

## 端点（M2）
```
GET /health
GET /overview?dumpDir=/nfs/heapdumps/hd_xxx
    → {numObjects,numClasses,numGcRoots,usedHeapSize,jvmInfo,...}   # 回填 stats
GET /histogram?dumpDir=...&top=50&offset=0&sort=shallow|retained|count&approx=true
    → {groupBy,sort,retainedMode,rows:[{classId,label,count,shallow,retained,retainedApprox}],totalRows,cursor}
```

### retained 的三种精度（重要）
读 MAT 源码确认，retained size 有三档成本，本服务都覆盖：
| 模式 | 怎么算 | 成本 | 精度 |
|---|---|---|---|
| raw | histogram 自带值（常为 0=未算）| 免费 | 有就准，没有就 n/a |
| **approximate（默认）** | `getMinRetainedSize`：支配树上"顶层祖先 retained 之和"，每个 O(1) 查 o2ret 索引 | **很便宜** | 下界近似，偏小但很接近 |
| exact | `getHeapSize(getRetainedSet(...))` 全图标记-清除 | 贵，不在 histogram 默认做 | 精确 |

- `sort=retained` 时默认用 **approximate** 补齐（`?approx=false` 关掉）。
- 返回里 `retainedApprox:true` 表示该值是近似（MAT 内部用负数标记近似，本服务取绝对值 + 该布尔位）。
  前端可在近似值前显示 "≈"，与 MAT UI 行为一致。
- 这解决了"histogram retained 常为 0、精确算法又太重"的两难。

## V1 stats 回填怎么接（呼应 v1-parsehd）
V1 用 ParseHeapDump 解析完拿不到 SnapshotInfo 数值。Python worker 在任务 DONE 后：
```
GET java-internal:/overview?dumpDir=<dump_dir>   →  把返回 JSON 写入 heapdump_reports.stats
```
此时 index 已在 NFS，reopen 秒级，几乎无成本。

## 运行模式：路线2 —— query-service 作为 OSGi bundle ★（已采用）

> 背景：实测发现独立 jar 进程内起 Equinox 会 ClassNotFoundException（容器外 AppClassLoader 看不到
> bundle 里的 MAT 类），详见 [EQUINOX-A-VERIFICATION.md](./EQUINOX-A-VERIFICATION.md)。
> **已选路线2**：把本服务做成 OSGi bundle，与 MAT 同类空间，现有 `import org.eclipse.mat.*` 全部可用，
> View/QueryRunner/SnapshotManager **代码无需改成反射**。

### bundle 化的四件套（已就位）
| 文件 | 作用 |
|---|---|
| `META-INF/MANIFEST.MF` | Bundle-SymbolicName + **Require-Bundle: org.eclipse.mat.api/report/parser/hprof**（声明依赖 MAT，OSGi 自动激活它们→registry 就绪）|
| `plugin.xml` | 注册 application `com.jvmind.mat.query.app`（入口 `QueryServiceApp`）|
| `build.properties` | Tycho/PDE 打包声明 |
| `pom.xml` | `packaging=eclipse-plugin`（Tycho），target platform 指向 MAT 发行版 p2 |

### 入口：QueryServiceApp（IApplication）
取代旧 `Main`（已废弃，仅路线1参考）。它在 OSGi 内装配 SnapshotManager/TaskManager/HTTP，
长驻直到 stop()。因同在 OSGi 类空间，`SnapshotManager` 里直接 `import SnapshotFactory` 即可。

### 构建 / 部署 / 启动
```bash
# 构建（Tycho；target platform 指向解压的 MAT 发行版，pom 里改 file:/opt/mat）
mvn -f query-service/pom.xml clean package
#   产物: target/com.jvmind.mat.query-0.1.0.jar （OSGi bundle）

# 部署：丢进 MAT 发行版的 dropins/
cp target/com.jvmind.mat.query-0.1.0.jar /opt/mat/dropins/

# 启动（headless application，长驻 HTTP 服务）
/opt/mat/MemoryAnalyzer -consoleLog -nosplash \
    -application com.jvmind.mat.query.app \
    -vmargs -Xmx16g -DMAT_QUERY_PORT=8090
```
配置走 `-D系统属性` 或环境变量（MAT_QUERY_PORT/THREADS/MAX_OPEN/HEAP_PCT/TASK_*）。

> `equinox/EquinoxBootstrap.java` 仅路线1（独立 jar 全反射）才用，路线2 下忽略它。

## （历史参考）路线1：进程内起 Equinox + 全反射
调用 MAT 需要 extension registry → 需 OSGi/Equinox。采用 **A 方案**（`equinox/EquinoxBootstrap`）：
- 启动时先 `EquinoxBootstrap.start(MAT_HOME, configDir)`：用<b>反射</b>调 `EclipseStarter`
  （setInitialProperties / startup / isRunning / shutdown，API 已核对），
  把 MAT 发行版 plugins/ 设为 `osgi.install.area`，用 `osgi.bundles` 自启动 core runtime + MAT 4 个 bundle，
  并校验它们 ACTIVE（触发 ParserPlugin/ReportPlugin 的 activator 注册 extension）。
- registry 就绪后再装配 SnapshotManager / TaskManager / HTTP。
- 启动必需环境变量：**`MAT_HOME`**（MAT 发行版解压目录，含 plugins/）。

> 为什么必须：MAT 用 `Platform.getExtensionRegistry()` 发现 hprof 解析器；这些 bundle 不 ACTIVE，
> registry 就是空的，openSnapshot 会找不到解析器。

## 重操作异步化 ★ 已实现
leakhunter / retained_set / merge_paths 是全图分析（几十秒~几分钟），改为异步任务：
```
POST /leak-suspects?dumpDir=...        → 202 {taskId, status:"RUNNING"}   (后台跑)
POST /retained-set?dumpDir=... {objects}→ 202 {taskId}
POST /merge-paths?dumpDir=...  {objects}→ 202 {taskId}
GET  /tasks/{taskId}                    → {status:RUNNING,progress,phase} | {status:DONE,result} | {status:FAILED,error}
DELETE /tasks/{taskId}                  → 取消
```
- `task/TaskManager`：线程池 + 进度(MAT IProgressListener 回写) + **超时**(超限 setCanceled，MAT 协作式中断) +
  **结果软引用缓存**(层 C，agent 反复取命中；内存紧张可回收，回收后返回 resultEvicted 提示重跑)。
- **租约在任务线程内持有**，覆盖整个执行期（重操作跑几分钟，lease 不能在 submit 就 close）。
- 轻操作（overview/histogram/dominator/threads/oql/top-consumers/path2gc单对象）仍同步——本来就快。

## 配置（环境变量）
| 变量 | 默认 | 说明 |
|---|---|---|
| `MAT_QUERY_PORT` | 8090 | 监听端口（127.0.0.1）|
| `MAT_QUERY_THREADS` | 16 | HTTP 线程数 |
| `MAT_QUERY_MAX_OPEN` | 4 | 同时打开 dump 上限（防 OOM）|
| `MAT_QUERY_HEAP_PCT` | 85 | 内存兜底阈值百分比 |

## 构建
```bash
mvn -f query-service/pom.xml clean package   # 需先备齐 MAT 依赖（见 ../parser-cli/MAT-DEPENDENCIES.md）
# 产出 target/mat-query-service.jar
```

## M3 视图（新增）
```
GET /dominator?dumpDir=...&parent=ROOT|<objectId>&top=50&offset=0
    → {parent,rows:[{objectId,label,type,shallow,retained,percent,expandable}],totalRows,cursor}
    # 对象级支配树，逐层懒加载；parent=ROOT 取顶层支配者(getImmediateDominatedIds(-1))
    # percent = retained 占总堆百分比（MAT UI 关键列）

GET /threads?dumpDir=...                          → 线程列表(三级树一级)
    → rows:[{objectId,name,daemon,priority,contextClassLoader,retained,hasStack}]
GET /threads?dumpDir=...&thread=<id>              → 该线程栈帧(二级)
    → frames:[{frameIdx,text,localsCount,maxLocalRetained}]
GET /threads?dumpDir=...&thread=<id>&frame=<idx>  → 该帧局部变量(三级)
    → locals:[{objectId,label,...}]   # 局部变量持有的连接/大对象 = 泄漏归因金矿(文档03)

POST /oql?dumpDir=...   body {"q":"SELECT ...","limit":200,"offset":0}
    → {kind:objectList|table|scalar|empty, rows/columns, totalRows, truncated, cursor}
    # 结果可能是 int[](对象列表) 或 IResultTable(自定义投影)，两种都处理
    # 表格行带 objectId(若有上下文)，便于行内跳转/投喂给 histogram/dominator
```

## P0 王牌诊断（新增，QueryRunner + ResultRenderer）
> 文档 08 §2 的高价值缺口。核心是两件基础设施 + 几个王牌端点：
> - `core/QueryRunner` —— 统一执行**任意** MAT query：`SnapshotQuery.lookup(name).setArgument().execute()`。
> - `view/ResultRenderer` —— 把**任意** IResult（表/树/文本/复合）递归渲染成瘦身 JSON。
> 有了这两个，接入任何 query 只是"run + render"，长尾可批量自动导出（文档04）。

```
GET  /leak-suspects?dumpDir=...        → leakhunter，返回 {kind:composite,sections:[...]}
                                          # MAT 招牌；复合诊断工具 mat_diagnose_leak_suspects 后端
GET  /top-consumers?dumpDir=...        → top_consumers，谁占内存（按类/包/类加载器）
GET  /path2gc?dumpDir=...&object=<id>&excludeWeakSoft=true
                                       → 单对象→GC Root 最短路径树（泄漏归因核心）
POST /merge-paths?dumpDir=...  body {"objects":[id,...]}   → 多对象合并最短路径（找泄漏模式）
POST /retained-set?dumpDir=... body {"objects":[id,...]}   → retained set（重操作，建议加超时/异步）
```

## 状态与下一步
- [x] SnapshotManager 三层软引用 + 引用计数 + 并发上限
- [x] /overview /histogram(4种排序+近似retained) + 出参瘦身 + MemoryWatcher
- [x] /dominator（对象级懒加载 + percent 列）
- [x] /threads（三级树：线程→栈帧→局部变量）
- [x] **/threadlocals（ThreadLocal 全局视图：按值类/线程聚合，泄漏排查）**
- [x] **/overview?full（Heap Dump Info + JVM System Properties）**
- [x] /object（Inspector：字段/静态字段/Type列/搜索/排序/值含Unicode/引用跳转）
- [x] /oql（int[] 与 IResultTable 双形态；直方图视图 + 排序）
- [x] **结果集复用 resultSetId（OQL → histogram/retained-set/merge-paths 投喂，分析流）**
- [x] **QueryRunner + ResultRenderer（接入任意 query 的基础设施）**
- [x] **P0 王牌：leak-suspects / path2gc / merge-paths / top-consumers / retained-set**
- [x] **重操作异步任务 + 超时 + 结果软引用缓存（taskId 轮询）**
- [x] **路线2：query-service 做成 OSGi bundle（MANIFEST/plugin.xml/QueryServiceApp）— 已采用**
- [x] A 方案核心机制沙箱实测通过（Equinox+bundle ACTIVE+openSnapshot）+ 抓出 CNF 缺陷并定路线
- [x] **QueryDescriptor 反射导出：/tools 自描述清单（精简/完整两档，省 token）+ /query 通用直通（agent 基础）**
- [ ] histogram/支配树子功能（按 package/classloader 分组维度）
- [ ] 本地 Tycho 构建 bundle + dropins 部署 + application 启动实测（沙箱无 Maven/Tycho，待你本地）
- [ ] 注意 `Import-Package: com.sun.net.httpserver`：JDK 内置 HttpServer 在某些 JPMS/OSGi 配置下
      需 `--add-exports`/系统 bundle 暴露；若启动报该包不可见，改用 netty 或 servlet bridge（备选）

## 工具自描述 + 通用直通（agent 基础设施）★
> 文档04 §0 的核心洞察落地：MAT 每个 query 都用 @CommandName/@Help/@Argument 自描述，
> 经 QueryRegistry→QueryDescriptor→ArgumentDescriptor 反射 = 现成的 tool schema。
> 一次把全部 ~57 个 query 暴露成 agent 可发现、可调用的工具，无需逐个手写端点。

```
GET  /tools                  → 精简版(默认,给 agent)：{tools:[{command,desc,args:[{name,type,required[,advice]}]}],count}
GET  /tools?core=true        → 只返回核心工具集(~12个)，建议给 agent 常驻
GET  /tools?filter=leak      → 关键字过滤（= search_tools 元工具，按需检索长尾）
GET  /tools?verbose=true     → 完整版(给调试/文档)：含 name/help/category/每参数 help+default
POST /query?dumpDir=...[&async=true]  body {"command":"duplicate_classes","args":{...}}
                             → 通用直通：QueryRunner + ResultRenderer 跑任意 query
                               args 支持 {objects:[ids]} / {object:id} 约定；async=true 走任务
```
- **精简版（默认）是给 agent 的**，每个工具只剩 command + desc（help 第一句）+ agent 真要填的参数
  （已过滤 MAT 内部参数 snapshot/contexts；不含 default/长 help）。每工具 ~250 字节，比完整版省 ~40%。
- **完整版（?verbose=true）给人看**：含完整 name/help/category/参数说明/default（已解 HTML 实体、清脏 name/category）。
- 每个 arg 带 `type`（number/string/boolean/enum/objectId[]）+ `required` + `advice`（HEAP_OBJECT 等语义），LLM 友好。
- 分级暴露治理 context：core 精简版常驻 agent，长尾靠 filter 检索，要细节才 verbose 单查。

## 关于树的深层展开（ResultRenderer 设计取舍）
MAT 树节点是 query 内部对象，<b>无稳定跨请求 id</b>。故 ResultRenderer 默认只渲染根这一层；
更深展开时，前端用节点的 objectId <b>重新发起以该对象为起点的 query</b>（如 path2gc(object=该id)），
而非在同一棵树里翻——既懒加载，又规避了"树节点无稳定 id"的问题。path2gc 根层通常已足够定位泄漏链。

> 注：本工程用了 JDK 14+ 语法（switch 箭头）；研究沙箱是 JDK 11 故无法在此编译，
> 已在 JDK11 上用等价传统 switch 验证过逻辑。目标 JDK 17/21 上直接 `mvn package` 即可。
