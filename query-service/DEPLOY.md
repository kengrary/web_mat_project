# query-service 部署（已沙箱实测验证）

## 部署拓扑（确定方案）：Java 分析模块独立部署，Python 唯一入口
```
                          外网入口（唯一）
   浏览器 ───────────────────▶ Python 服务 (FastAPI)   ← 鉴权/配额/权限都在这层
                                     │
   ┌─────────────────────────────────┼─────────────────────────────┐
   │  独立云服务器 / 内网（无外网入口）                              │
   │   ▼ 反代查询(httpx)               ▼ 触发解析(fork)              │
   │  Java 查询服务 :8090            解析(ParseHeapDump/parser-cli)  │
   │        └──────── NFS(dump: hprof+index) ────────┘             │
   │        └──────── PostgreSQL(任务/报告) ─────────┘             │
   └───────────────────────────────────────────────────────────────┘
```
**要点（这套拓扑下的硬性约定）**：
- **Java 侧（解析+查询）无外网入口、无鉴权、无 CORS** —— 安全完全靠网络隔离 + Python 当唯一入口。
- **前端不直连 8090** —— 前端只调 Python 的 `/api/heapdump-reports/...`，Python 反代到 8090（同源，无 CORS 问题）。
- **8090 需让 Python 连得上** → `MAT_QUERY_BIND` 绑**内网网卡 IP**（不是 127.0.0.1，否则跨机连不上；也不要 0.0.0.0，太宽）。

### 安全双保险（务必都做）
1. **网络层**：云安全组/防火墙 —— 8090 入站**只放行 Python 服务器 IP**；整个 Java 子网无公网入站。
2. **绑定层**：`-DMAT_QUERY_BIND=<内网IP>` 绑具体内网网卡（非 0.0.0.0）。即使安全组配错，也不监听公网网卡。
> 即 `MAT_QUERY_CORS` **不要设**（前端不直连，无需 CORS）。它只是开发期临时直连调试的后门，生产关闭。

## 关键结论：用 bundles.info 注册，不是 dropins

MAT 发行版用 **simpleconfigurator（`bundles.info`）** 管理 bundle，**不扫描 `dropins/`**。
把 jar 丢进 dropins → bundle 不被加载 → application 注册不进 registry →
启动报 `Application "com.jvmind.mat.query.app" could not be found in the registry`。

> 沙箱用真实 MAT 实测：造一个最小 bundle，注册进 bundles.info + `-clean` 重启，
> 自定义 application 成功启动（输出 PROBE-APP-STARTED-OK）。dropins 方式则不行。

## 部署步骤（实测可行）

```bash
MAT=/home/pan/mat
JAR=/home/pan/web_mat_project/query-service/target/com.jvmind.mat.query-0.1.0.jar

# 1) jar 放进 plugins/（与其它 bundle 同目录），不是 dropins
cp "$JAR" "$MAT/plugins/"

# 2) 在 bundles.info 末尾注册一行
echo 'com.jvmind.mat.query,0.1.0,plugins/com.jvmind.mat.query-0.1.0.jar,4,true' \
  >> "$MAT/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"

# 3) -clean 重启（关键：清 OSGi 缓存，否则不重读 bundles.info）
#    MAT_QUERY_BIND 绑【本机内网 IP】，让 Python 跨机连得上（不要 127.0.0.1，也不要 0.0.0.0）
cd "$MAT"
./MemoryAnalyzer -consoleLog -nosplash -clean \
    -application com.jvmind.mat.query.app \
    -vmargs -Xmx16g \
    -DMAT_QUERY_BIND=10.0.0.5 -DMAT_QUERY_PORT=8090
#    启动日志应显示: started on 10.0.0.5:8090
```

### 配置项（-D 系统属性 或 环境变量，前者优先）
| 键 | 默认 | 说明 |
|---|---|---|
| `MAT_QUERY_BIND` | 127.0.0.1 | 监听地址。**跨机部署设本机内网 IP**；同机调试可留默认 |
| `MAT_QUERY_PORT` | 8090 | 端口 |
| `MAT_QUERY_THREADS` | 16 | HTTP 线程数 |
| `MAT_QUERY_MAX_OPEN` | 4 | 同时打开 dump 上限（防 OOM） |
| `MAT_QUERY_HEAP_PCT` | 85 | 内存兜底阈值（%） |
| `MAT_QUERY_TASK_THREADS` | 2 | 重操作任务线程数 |
| `MAT_QUERY_TASK_TIMEOUT` | 300 | 重操作超时（秒） |
| `MAT_QUERY_CORS` | （不设） | CORS 来源。**生产不设**（前端走 Python 反代）；仅开发期前端直连调试才用 `*` |

### bundles.info 行格式（5 字段，逗号分隔）
```
<Bundle-SymbolicName>,<version>,plugins/<jar文件名>,<start-level>,<auto-start>
com.jvmind.mat.query,0.1.0,plugins/com.jvmind.mat.query-0.1.0.jar,4,true
```
- SymbolicName = MANIFEST 的 `Bundle-SymbolicName` 去掉 `;singleton:=true` → `com.jvmind.mat.query`
- **auto-start 必须 `true`**：启动时激活，才会注册 application
- jar 文件名以 Tycho 实际产出为准（可能是 `com.jvmind.mat.query-0.1.0.jar` 或带限定符），
  用 `ls plugins/com.jvmind.mat.query*.jar` 确认后填准。

## 验证成功
启动后另开终端（查询机本机用 127.0.0.1；从 Python 机验证则换成查询机内网 IP）：
```bash
curl http://127.0.0.1:8090/health
# 期望: {"status":"ok"}
curl "http://127.0.0.1:8090/overview?dumpDir=/path/to/dump_dir"   # 该目录需有 *.hprof + *.index
# 从 Python 服务器验证连通：curl http://<查询机内网IP>:8090/health
```

## 常见后续报错

### com.sun.net.httpserver 不可见
QueryHttpServer 用 JDK 内置 HttpServer。若启动报该包不可见，在 MAT 的
`configuration/config.ini` 加：
```
org.osgi.framework.system.packages.extra=com.sun.net.httpserver
```
然后再 `-clean` 重启。（或换 Netty，README 备选。）

### 仍 could not be found
- 确认第 2 步那行真的写进了 bundles.info（`grep com.jvmind plugins ...bundles.info`）；
- 确认 jar 真在 plugins/ 且文件名与 bundles.info 一致；
- **必须带 `-clean`**（漏了它最常见）；
- 看 `-consoleLog` 输出有无本 bundle 的 resolve 失败（缺依赖会导致不激活 → application 不注册）。
  resolve 失败常见原因：MANIFEST 的 Require-Bundle 写的 bundle 在该 MAT 版本里 symbolic name 对不上。

## 端到端验证（沙箱用 MAT 测试 hprof 实测）

用 MAT 源码自带的 `oracle_jdk8_05_x64.hprof`（6094 objects）在沙箱直接调 MAT API
（模拟各端点核心逻辑），结果正确：

| 端点 | 实测结果 |
|---|---|
| `/overview` | objects=6094, classes=565, gcRoots=530, heap=300920 ✅ |
| `/histogram` | 565 类，Top5 by shallow：char[](145088) / String(36792) / byte[](24960) / Object[](14232) / Class(7272) ✅ |
| `/dominator?parent=ROOT` | 975 个顶层支配对象 ✅ |
| `/threads` | 5 个线程实例 ✅ |

**一个正常现象（非 bug）**：`getHistogram()` 的 565 条记录里只有 235 条 shallow>0，
其余是**无实例的类**（接口/未实例化类，如 `sun.nio.cs.ArrayDecoder`），count/shallow 自然为 0。
HistogramView 按 shallow 降序排，这些 0 值沉底，不影响 Top-N。前端展示属预期行为。

## 你本地用测试 dump 验证（curl）
```bash
# 1) 用 MAT 自带测试 hprof（或你的任意 hprof）解析出 index
/home/pan/mat/ParseHeapDump.sh /path/to/test.hprof   # 生成 test.*.index

# 2) curl 各端点（dumpDir = hprof 所在目录）
D=/path/to       # 含 test.hprof + test.*.index
curl "http://127.0.0.1:8090/overview?dumpDir=$D"
curl "http://127.0.0.1:8090/histogram?dumpDir=$D&top=10&sort=shallow"
curl "http://127.0.0.1:8090/dominator?dumpDir=$D&parent=ROOT&top=10"
curl "http://127.0.0.1:8090/threads?dumpDir=$D"
curl "http://127.0.0.1:8090/tools?core=true"

# 概览完整版（Heap Dump Info + JVM System Properties）
curl "http://127.0.0.1:8090/overview?dumpDir=$D&full=true"

# ThreadLocals 全局视图（泄漏排查）：默认按值类聚合；groupBy=thread 按线程
curl "http://127.0.0.1:8090/threadlocals?dumpDir=$D&groupBy=valueClass&top=50"
curl "http://127.0.0.1:8090/threadlocals?dumpDir=$D&groupBy=thread"

# SQL in Threads（SQL 引起的 OOM 排查）：从线程栈提取 SQL，默认只看有风险（过滤 select 1 等心跳）
curl "http://127.0.0.1:8090/sql-in-threads?dumpDir=$D&onlyRisky=true"

# OOM 诊断：识别触发线程 + 元凶/受害者判定（culpritPct=元凶阈值,默认30%）
curl "http://127.0.0.1:8090/oom-diagnosis?dumpDir=$D&culpritPct=30"

# 复合 OOM 诊断 mat_diagnose_oom：一步聚合 OOM触发线程+SQL风险+Leak Suspects（含leakhunter→异步）
# 返回 {taskId} → 轮询 /tasks/{id} 取 result.summary(verdict/headline/recommendation)+result.sections
curl "http://127.0.0.1:8090/diagnose-oom?dumpDir=$D&culpritPct=30&onlyRisky=true"   # → {taskId}
curl "http://127.0.0.1:8090/tasks/<taskId>"                                          # 轮询结果

# 连接池配置+统计（识别 HikariCP/Druid/Tomcat JDBC/DBCP2/c3p0；密码脱敏为 ******）
# Druid/HikariCP 额外返回 connections.{idleCount,activeCount,idle[],active[]} 空闲/活跃连接明细
#   Druid: 空闲=connections[](holder) / 活跃=activeConnections(pooledConnection)
#   HikariCP: pool.connectionBag.sharedList 的 PoolEntry，按 state 区分(IN_USE/NOT_IN_USE)
curl "http://127.0.0.1:8090/connection-pools?dumpDir=$D"

# 连接池泄漏归因：借出未归还的连接 + 借出栈（Druid 有栈）+ 借出时长（超阈值=疑似泄漏）
curl "http://127.0.0.1:8090/pool-leaks?dumpDir=$D&thresholdMs=300000"

# 对象详情（Inspector：字段+静态字段+出/入引用+GC root；字段值含 String 内容/Unicode）
curl "http://127.0.0.1:8090/object?dumpDir=$D&id=<objectId>"

# OQL（POST body 带 q）—— 支持 view/sort
#   view=list（默认，对象列表）| histogram（对结果对象集按类聚合 ★）
#   sort=shallow|retained|count（histogram）；list 可 retained|shallow
curl -XPOST "http://127.0.0.1:8090/oql?dumpDir=$D&view=histogram&sort=retained" \
     -H 'Content-Type: application/json' \
     -d '{"q":"SELECT * FROM com.example.Connection","limit":200,"offset":0}'

# 结果集复用（resultSetId）：OQL 返回里带 resultSetId（如 rs-xxx），对这批对象再分析，无需重查：
#   list 视图的返回含 "resultSetId":"rs-xxx"
curl "http://127.0.0.1:8090/histogram?dumpDir=$D&objectSet=rs-xxx"          # 这批对象按类聚合
curl -XPOST "http://127.0.0.1:8090/retained-set?dumpDir=$D&objectSet=rs-xxx" -d '{}'  # → taskId
curl -XPOST "http://127.0.0.1:8090/merge-paths?dumpDir=$D&objectSet=rs-xxx" -d '{}'   # → taskId

# P0 王牌诊断（leak-suspects/retained-set/merge-paths 为异步，返回 taskId → 轮询 /tasks/{id}）
curl "http://127.0.0.1:8090/top-consumers?dumpDir=$D"
curl "http://127.0.0.1:8090/path2gc?dumpDir=$D&object=<objectId>"
curl -XPOST "http://127.0.0.1:8090/leak-suspects?dumpDir=$D"      # → {taskId}
curl "http://127.0.0.1:8090/tasks/<taskId>"                       # 轮询结果
```

## 卸载
```bash
sed -i '/com.jvmind.mat.query/d' "$MAT/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
rm -f "$MAT/plugins/com.jvmind.mat.query"*.jar
# 再 -clean 启动一次
```
