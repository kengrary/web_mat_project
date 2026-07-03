# 待办：Python 侧串通 heapdump 分析（给 Python 团队）

> 背景：Java 侧已完成且**实测跑通**——解析器（parser-cli / V1 用 MAT 的 ParseHeapDump）+
> 查询服务（query-service，OSGi bundle，HTTP 127.0.0.1:8090）。
> 现在需要 Python 侧把它们接进现有 JVMind 平台（FastAPI + DB + ReAct agent），
> 让用户能上传 heapdump → 看进度 → 用类 MAT 功能分析 → 在对话里追问。
>
> 设计依据：`mat-study/07-与现有JVMind系统对接落地方案.md`（务必先读）。
> 参考代码：`parser-cli/python-worker-snippet.py`（worker 调解析器 + 解析进度的可用片段）。
> 部署/接口：`query-service/DEPLOY.md`（查询服务怎么起、各端点 curl 示例）。

---

## 总原则（重要约束，来自现有系统对接文档）
1. **复刻 gc_reports / jstack_reports 的范式**：新模块"长得像"它们（上传→解析→stats→AI诊断→报告→可作对话上下文）。
2. **Java 不碰鉴权/用户/计费**：Python 是唯一权威。查询服务只被 Python **内网反代**调用（网络 ACL，无 token）。
3. **Schema 只由 Python 演进**：`heapdump_reports` 表加进现有 `init_db()`；Java 侧只读不写业务表。
4. **必须 PostgreSQL**（双进程共享，SQLite 不行）。
5. **时间格式** `yyyy-MM-dd HH:mm:ss` 本地时区；**id 前缀 `hd_`**；**解析配额复用 `file_upload`**（均已与现有团队确认）。

---

## 任务清单

### T1. 数据表 `heapdump_reports`（加进 init_db）
对齐 gc_reports 结构 + 异步解析所需字段。建表/补列写进现有 `init_db()`，**不要**在 Java 侧建表。
```sql
heapdump_reports(
  id TEXT PK,                 -- 'hd_' + uuid4().hex
  session_id TEXT, org_id TEXT NULL, user_id TEXT,
  filename TEXT, size BIGINT,
  status TEXT,                -- QUEUED/PARSING/DONE/FAILED/CANCELLED/CANCEL_REQUESTED
  progress REAL, phase TEXT,
  dump_dir TEXT,              -- NFS 上 dump 目录(含 app.hprof + *.index)；不使用 uploaded_files
  parse_args TEXT,           -- JSON
  stats TEXT,                -- JSON(解析完由 query-service /overview 回填)
  ai_conclusion TEXT,
  error TEXT,
  -- 水平扩展(单台起步但要支持多 worker)
  worker_id TEXT, heartbeat TEXT, attempts INT,
  created_at TEXT, queued_at TEXT, started_at TEXT, finished_at TEXT
)
```

### T2. 分块上传 API（heapdump 是 GB 级，必须分块）
```
POST   /api/heapdump-reports/uploads               创建上传会话 → {uploadId, chunkSize}
PUT    /api/heapdump-reports/uploads/{uid}/chunks/{i}   (body=分块二进制, Content-MD5 校验)
GET    /api/heapdump-reports/uploads/{uid}/status   → 已收哪些块(断点续传)
POST   /api/heapdump-reports/uploads/{uid}/complete → 合并/校验 hprof magic → 落 NFS dump_dir
                                                       → 建 heapdump_reports(status=QUEUED) → {id}
```
- 流式写盘/NFS，**不在内存累积**（FastAPI `request.stream()`）。
- complete 后校验 hprof 魔数（`JAVA PROFILE 1.0.x`），早失败。
- 计入 `file_upload` 配额（复用现有配额逻辑）。

### T3. 解析队列 + worker（DB 队列，支持多 worker）
- 取任务：`SELECT ... WHERE status='QUEUED' ORDER BY queued_at LIMIT 1 FOR UPDATE SKIP LOCKED`
  （PostgreSQL；天然单消费者顺序 + 可水平扩展，无需 MQ）。
- 调解析器：**V1 方式** = fork MAT 发行版的解析（见 `parser-cli/python-worker-snippet.py` 的 `run_parse_v1`，
  内部直接调 `equinox.launcher` 跑 `org.eclipse.mat.api.parse`，-Xmx 可控）。
  - 进度：解析它的文本输出 → 用 `parser-cli/v1-parsehd/mat_parse_runner.py` 的 `TextProgressMapper`
    （已实测，能映射出 Pass1/Pass2/GC/DominatorTree 等阶段 + 单调进度）。
  - 中断：`status=CANCEL_REQUESTED` → worker kill 子进程 → 清理半成品 `*.index`。
  - 僵尸任务：worker 定期更新 `heartbeat`；超时(>5min)的 PARSING 任务由看门狗重置为 QUEUED(attempts+1)。
- 解析 DONE 后：**调 query-service 回填 stats**（见 T5），再置 status=DONE。

### T4. 进度 / 报告 API（对齐 gc-reports 风格）
```
GET    /api/heapdump-reports?session_id=&org_id=   列表
GET    /api/heapdump-reports/{id}                   详情(status/progress/stats/ai_conclusion)
GET    /api/heapdump-reports/{id}/progress          SSE 进度流(解析中)
POST   /api/heapdump-reports/{id}/cancel            置 CANCEL_REQUESTED
DELETE /api/heapdump-reports/{id}                   删 NFS + DB
POST   /api/heapdump-reports/{id}/analyze           AI 诊断(计 llm 配额，对齐 gc/jstack)
```
- 进度优先 SSE（复用现有 sse-starlette），降级轮询。

### T5. 交互查询：反代到 query-service（解析完成后可用）
query-service 跑在内网 `127.0.0.1:8090`（或独立查询机内网地址）。Python 鉴权后透传，
**dumpDir = heapdump_reports.dump_dir** 作为参数传给 Java：
```
GET  /api/heapdump-reports/{id}/overview      → 反代 GET  8090/overview?dumpDir=<dump_dir>
GET  /api/heapdump-reports/{id}/histogram     → 反代 GET  8090/histogram?dumpDir=&top=&sort=
GET  /api/heapdump-reports/{id}/dominator     → 反代 GET  8090/dominator?dumpDir=&parent=&top=
GET  /api/heapdump-reports/{id}/threads       → 反代 GET  8090/threads?dumpDir=[&thread=&frame=]
POST /api/heapdump-reports/{id}/oql           → 反代 POST 8090/oql?dumpDir=  body{q,limit,offset}
GET  /api/heapdump-reports/{id}/leak-suspects → 反代 POST 8090/leak-suspects?dumpDir= (异步, 返回 taskId)
GET  /api/heapdump-reports/{id}/path2gc       → 反代 GET  8090/path2gc?dumpDir=&object=
... 其余端点见 query-service/DEPLOY.md
```
要点：
- 用 `httpx.AsyncClient` 转发，设超时/重试。
- 重操作（leak-suspects/retained-set/merge-paths）8090 返回 `{taskId}` → Python 再轮询 `8090/tasks/{taskId}`
  或包装成自己的任务，给前端进度。
- **stats 回填**（T3 用）：解析 DONE 后 `GET 8090/overview?dumpDir=<dump_dir>` 的返回 JSON 写入
  `heapdump_reports.stats`（此时 index 已在 NFS，reopen 秒级）。
- 校验该 report 属于当前 user/org（行级权限）后才反代。交互查询计 `api_call` 配额。

### T6. ReAct Agent 集成（让对话能分析 heapdump）
- **扩展 report_context type**：新增 `{type:"heapdump", session_id, report_id}`（现有支持 gc/jstack）。
  **只传 report_id**，不把数据塞进上下文（agent 按需用工具读，根治 token 爆炸）。
- **新增 MAT agent 工具**（注入现有手写 ReAct loop 的工具箱，每个工具首参 = report_id）：
  - 原子：`mat_overview` / `mat_histogram` / `mat_dominator` / `mat_threads` / `mat_oql` / `mat_path2gc`
  - 复合诊断：`mat_leak_suspects`（王牌，一步出 OOM 结论）/ `mat_top_consumers`
  - 工具发现（可选）：`mat_list_tools`(filter) → 反代 `8090/tools`，让 agent 检索长尾 query
  - 实现：工具内部 report_id → 查 DB 拿 dump_dir → 调 T5 的反代 → 出参已瘦身。
  - **就绪校验**：工具调用前查 status；若非 DONE 返回"解析中(进度 X%)，稍后再试"。
- **跨数据源联合诊断**（最大价值）：工具箱同时有 gc/jstack/heapdump，agent 可关联
  （例：jstack 看到线程卡 getConnection + heapdump 看到 HikariPool active=50/50 → 连接池泄漏）。

---

## 部署前置（运维/Python 协作确认）
- query-service 已部署在内网（见 `query-service/DEPLOY.md`：放 plugins/ + bundles.info + -clean 启动）。
- MAT 发行版（解析用）在解析机就位；NFS 挂载点解析机可写、查询机/Python 可读。
- 环境变量对齐（DATABASE_URL 指同一 PostgreSQL 等，见现有对接文档第7章）。

## 验收标准
1. 上传一个 GB 级 hprof → 前端看到解析进度推进 → DONE。
2. report 详情显示 stats（对象数/类数/堆大小）。
3. 点 Histogram/Dominator/Threads/OQL 菜单 → 返回正确数据（对照 query-service/DEPLOY.md 的实测数值形态）。
4. 对话挂一个 heapdump report → 问"有没有内存泄漏" → agent 调 mat_leak_suspects 给出带证据结论。
5. 中断解析 → 任务停止、半成品清理。

## 已就绪的参考物料（直接抄/改）
| 用途 | 文件 |
|---|---|
| 整体对接设计（表/接口/反代/agent） | `mat-study/07-与现有JVMind系统对接落地方案.md` |
| worker 调解析器 + 进度解析（可用片段） | `parser-cli/python-worker-snippet.py` |
| 文本进度映射器（已实测修过 bug） | `parser-cli/v1-parsehd/mat_parse_runner.py` |
| V1 解析方案说明 + 实测报告 | `parser-cli/v1-parsehd/README.md` / `VERIFICATION.md` |
| 查询服务端点清单 + curl 示例 | `query-service/DEPLOY.md` |
| agent 工具化思路（粒度/瘦身/分级暴露） | `mat-study/04-MAT接口工具化-自然语言诊断Agent思路.md` |
