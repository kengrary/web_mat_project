# parser-cli — JVMind Heapdump 解析器（独立 jar）

> 链路定位（见 `mat-study/`）：本模块是 **L1 解析层**。
> Python worker 从 DB 队列取任务后，驱动 `hprof → 全套 MAT index 文件`。
> 解析完成后 index 落到 NFS 的 dump 目录，供 Java MAT Query 服务 reopen 做交互查询。

## 两版演进（已确认路线）
- **V1（先做，跑通端到端）**：直接用 MAT 发行版的 `org.eclipse.mat.api.parse` application，
  Python 解析其**文本进度**。零接线、最快验证链路。代码见 [`v1-parsehd/`](./v1-parsehd/README.md)。
- **V2（后做，精细进度）**：本 jar 方式 A（嵌 Equinox + 自定义 `IProgressListener` → JSONL）。
  接口契约与 V1 一致（`on_update(progress, phase)`），**切换时只换 runner，worker/DB/路由零改动**。

> 下面的 Java 骨架是 **V2** 的实现；**V1 不需要编译 Java**，直接用发行版 + `v1-parsehd/mat_parse_runner.py`。

## 为什么是独立进程
- **内存隔离**：解析峰值可达数十 GB（`N×28.25 + C×1000` 字节），进程退出后彻底归还 OS，不与在线查询抢内存。
- **故障隔离**：解析 OOM/崩溃只死这一个子进程。
- **中断干净**：Python 直接 kill 子进程即可中断；本进程也支持协作式 cancel。
- **单独 JVM 参数**：可给解析进程单独设大 `-Xmx`。

## 设计要点
1. **复用 MAT 内核**，不重写解析器：核心就是 `SnapshotFactory.openSnapshot(file, args, listener)`。
2. **结构化进度**：把 MAT 的 `IProgressListener` 进度转成**每行一条 JSON** 打到 stdout，Python 逐行解析更新 `heapdump_reports.progress/phase`。
3. **半成品隔离**：先解析到 `<dumpDir>/.tmp/`，全部完成 + fsync 后**原子 rename** 到正式目录——查询机绝不会读到半成品 index（呼应 NFS 方案，文档 06 §2.2）。
4. **stats 提取**：解析完读 `SnapshotInfo`，输出 `stats` JSON（对象数/类数/堆大小/JVM 信息…），Python 写入 `heapdump_reports.stats`。
5. **退出码**：`0` 成功；`79` OOM（沿用 MAT 约定）；`2` 参数错误；`3` 解析失败；`130` 被取消。

## 两种运行方式（二选一）

### 方式 A：Equinox headless（推荐，与"内嵌 Equinox"决策一致）
直接复用 MAT 命令行应用 `org.eclipse.mat.api.parse`，本 jar 作为启动器/包装器。
- 优点：完全复用 MAT 的 extension registry，解析器自动发现，最稳。
- 实现：`EquinoxLauncher` 用 `org.eclipse.equinox.launcher` 起最小 OSGi，运行 application。

### 方式 B：直连 SnapshotFactory（更轻，需 MAT bundle 在 classpath 且 registry 可用）
直接 `SnapshotFactory.openSnapshot(...)`。
- 优点：无需起完整 Equinox，启动快。
- 风险：MAT 用 Eclipse extension point 发现 hprof 解析器，脱离 OSGi 需保证 registry 已初始化（见 `DirectParser` 注释）。

> 本骨架两种都给出。**先用方式 A 跑通**，方式 B 作为后续优化。

## 用法
```bash
java -Xmx32g -jar parser-cli.jar \
     --hprof   /nfs/heapdumps/hd_xxx/app.hprof \
     --out-dir /nfs/heapdumps/hd_xxx \
     [--keep-unreachable] [--discard-ratio 50] [--snapshot-id "#1"] \
     [--progress-file /path/progress.jsonl]   # 不指定则进度走 stdout
```

## 构建
```bash
mvn -f parser-cli/pom.xml clean package
# 产出 target/parser-cli.jar (shaded)
```
> 需先把 MAT 的 4 个 jar（api/report/parser/hprof）+ Equinox 装进本地 Maven 仓或用 system scope 引入。
> 当前研究沙箱无 Maven 且 MAT 用 Tycho 构建，**本骨架在你本地补上 MAT 依赖后即可编译**。

## MAT 依赖怎么拿
见 **[MAT-DEPENDENCIES.md](./MAT-DEPENDENCIES.md)** —— 完整的依赖清单、获取方式（发行版取 jar / Tycho 构建 / p2 镜像）、
以及"零接线捷径"（直接用发行版 `ParseHeapDump.sh`）。要点速记：
- 解析核心 4 个 bundle（api/report/parser/hprof）**零第三方库依赖**；
- headless 解析**不需要 SWT/UI**（hprof 的 UI import 全是 optional）；
- 最快路径：下载 MAT 发行版 zip，从 `plugins/` 取 jar；
- 最新 1.17.x 需 **JDK 21**，要 JDK 17 用 MAT **1.14.0**。

## stdout 进度协议（每行一个 JSON）
```jsonc
{"type":"progress","phase":"Pass1 Scanning","progress":0.12,"ts":"2026-06-28 10:00:01"}
{"type":"progress","phase":"DominatorTree","progress":0.85,"ts":"..."}
{"type":"message","severity":"INFO","text":"Heap contains 1,234,567 objects"}
{"type":"stats","data":{"numObjects":1234567,"numClasses":4821,"usedHeapSize":1288490188,...}}
{"type":"done","status":"DONE","indexDir":"/nfs/heapdumps/hd_xxx"}
{"type":"done","status":"FAILED","error":"Not a HPROF heap dump"}
```
非 `type` 行（MAT 内部偶发 stdout）Python 侧应忽略。
