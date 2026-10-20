# 记忆快照 — 项目恢复指南（压缩记忆）

> 读这一份即可恢复全部上下文。最后更新：query-service 全面跑通 + /tools 优化 + 前端原型之后。
> 详情入口永远是根目录 [README.zh.md](./README.zh.md) / [README.md](./README.md) 与 [PROJECT-STATUS.md](./PROJECT-STATUS.md)。

## 一句话
为现有 **JVMind 平台**（Python/FastAPI + 自写 ReAct agent + 已有 GC日志/jstack 分析）增加
**Java heapdump 分析能力**，复用 **Eclipse MAT 1.17** 内核，不重写解析器。

## 已确认的架构决策（不要再推翻）
- 仅 HotSpot **hprof**；后端 **Java 21 + MAT 1.17**（OSGi/Equinox）。
- 解析与查询**分离**；存储 **NFS**(dump 文件) + **PostgreSQL**(任务/报告，双进程共享)。
- Python↔Java 用 **REST/JSON**；Java 查询服务**无鉴权、无 CORS、无外网**，由 Python 反代。
- **部署拓扑（确定）**：heapdump 分析模块（解析+查询）放**独立云服务器/内网，无外网入口**；
  **Python 是唯一外网入口**。前端只调 Python（同源无 CORS），不直连 8090。
  查询服务 `MAT_QUERY_BIND` 绑内网 IP；安全双保险=安全组只放行 Python IP + 绑具体内网网卡。
  （配置项 MAT_QUERY_BIND/PORT/CORS 等见 query-service/DEPLOY.md；CORS 默认不设，仅开发直连调试才用。）
- 软引用：snapshot 缓存层 + 查询结果层都用 **Java SoftReference**（三层，防 Java 服务 OOM）。
- 现有系统对接：id 前缀 `hd_`；文件只在 NFS（用 `heapdump_reports.dump_dir`，不用 uploaded_files）；
  解析配额复用 `file_upload`；Schema 只由 Python 演进；report_context 只传 report_id（agent 按需取）。

## 三个工程模块现状
| 模块 | 路径 | 状态 |
|---|---|---|
| 解析层 | `parser-cli/` | ✅ **V1 实测可用**（用 MAT 发行版 `ParseHeapDump`，零编译；`v1-parsehd/mat_parse_runner.py` 解析文本进度，已修3个bug）。V2(自写jar)是骨架。 |
| 查询层 | `query-service/` | ✅ **真实环境跑通+端到端验证**。OSGi bundle，HTTP :8090。 |
| Python 串通 | （现有 JVMind） | ⏳ **待开发**，任务已交接 → `TODO-PYTHON-TEAM.md` |

## query-service 已实现能力（21 个 Java 文件）
- 四大视图：`/histogram`(4排序+近似retained) `/dominator`(懒加载+percent) `/threads`(三级树) `/oql`(双形态)
- P0 王牌：`/leak-suspects` `/path2gc` `/merge-paths` `/top-consumers` `/retained-set`（重操作走异步任务 `/tasks/{id}`）
- 全量 query：`/tools`(反射自描述，**精简版默认给agent/verbose=true完整**) + `/query`(通用直通)
- 机制：SnapshotManager 三层软引用+引用计数+并发上限；MemoryWatcher 内存兜底；TaskManager 异步+超时+结果软引用缓存
- 入口：`QueryServiceApp`(IApplication)；`/overview` 也用于给解析回填 stats

## 关键经验（踩过的坑，恢复时务必记得）
1. **MAT 不在 Maven Central** → Tycho `packaging=eclipse-plugin`，依赖走 MANIFEST `Require-Bundle` + `.target`，**无 `<dependencies>`**。
2. **MAT 发行版根目录不是 p2 仓库**（没 artifacts/content.xml，p2/ 是缓存）→ 用官方 update-site(联网) 或 `.target` 的 Directory location 指 plugins/。
3. **版本↔JDK**：1.17→JDK21 / 1.14→JDK17 / 1.12→JDK11，三处一致（MANIFEST BREE、pom release、executionEnvironment）。
4. **部署不是 dropins，是 bundles.info**：jar 放 plugins/ + bundles.info 加一行(auto-start=true) + **`-clean`** 重启。（实测验证过）
5. **独立 jar 进程内起 Equinox 会 CNF**（容器外 classloader 看不到 bundle 类）→ 已选**路线2=query-service 自身做成 bundle**，代码直接 import MAT，无反射。（EquinoxBootstrap 是路线1遗留，路线2 不用）
6. **getHistogram 记录**：无实例的类 count/shallow=0 是正常现象。
7. **近似 retained**：`getMinRetainedSize`（支配树 O(1)）便宜够用，比精确 `getRetainedSet` 快几个数量级；MAT 用负数标记近似。
8. **/tools 要瘦身**：全量元数据会撑爆 agent context；精简版过滤内部参数(snapshot)、help取首句、无default。

## 实测过的事实（可信，沙箱用真实 MAT 验证）
- ParseHeapDump 解析真实 hprof → 产出全套 index（文件清单与文档一致）。
- 嵌入式 Equinox + bundle ACTIVE + openSnapshot 成功（numObjects 正确）。
- bundles.info 注册 + -clean → 自定义 application 启动成功。
- 用 oracle_jdk8_05_x64.hprof 验证 overview/histogram/dominator/threads 数据正确
  （histogram Top: char[]/String/byte[]/Object[]/Class）。

## 文档地图（10份设计 + 部署/排错 + 顶层）
- 蓝图：`mat-study/README.md`；进度+坑：`PROJECT-STATUS.md`；Python交接：`TODO-PYTHON-TEAM.md`
- 设计 01~09：架构/算法/连接池诊断/Agent工具化/系统集成/四大视图API/对接JVMind/功能缺口/前端建议
- 部署实操：`query-service/DEPLOY.md`（含 curl 验证）、`MAT-DEPENDENCIES.md`、`BUILD-TROUBLESHOOTING.md`、`EQUINOX-A-VERIFICATION.md`
- 前端原型：`web-prototype/index.html`（单文件，mock+可连真实后端）

## 下一步待办（按优先级，详见 PROJECT-STATUS.md）
- **P0**：Python 侧串通（TODO-PYTHON-TEAM.md）；`com.sun.net.httpserver` 若不可见的兜底。
- **P1**：连接池 Inspector（HikariCP/Druid，靠 hash_entries 集合提取）；MAT 工具注入 ReAct loop + 跨数据源联合诊断。
- **P2**：重操作前端轮询；histogram/dominator 聚合分组；两 dump 对比；V1→V2 切换；大 hprof 压测；前端生产化。

## 沙箱临时物（不在工作区持久化，恢复后需重新准备）
- `/tmp/matdist/mat`（下载的 MAT 1.12 发行版，用于实测）—— 重启沙箱会丢，需要时重新下载。
- mat-src/ 是 MAT 源码 clone（在工作区，但可能被清理；需要查源码可重新 `git clone https://github.com/eclipse-mat/mat.git`）。
