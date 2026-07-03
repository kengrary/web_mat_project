# Python 侧串通执行计划

> 目标：把已实测跑通的 Java 侧（解析 parser-cli/V1 + 查询 query-service:8090）接进现有 JVMind 平台
> （FastAPI + PostgreSQL + 自写 ReAct agent），让用户：上传 heapdump → 看进度 → 类 MAT 分析 → 对话追问。
> 本文是**执行计划**（分阶段、有依赖、可验收）；接口契约/表结构细节见 `TODO-PYTHON-TEAM.md` 与 `mat-study/07`。

## 0. 前置确认（开工前 10 分钟对齐）
- [ ] DATABASE_URL 指向**同一 PostgreSQL**（双进程共享，非 SQLite）。
- [ ] NFS 挂载点：解析机可写、查询机可读、Python 可写（落 dump）。
- [ ] query-service 已在内网起好（`MAT_QUERY_BIND=内网IP`，安全组只放行 Python），`curl 内网IP:8090/health` 通。
- [ ] MAT 发行版在解析机就位（`ParseHeapDump.sh` 可用）。
- [ ] id 前缀 `hd_`、配额复用 `file_upload`、Schema 只 Python 演进（已确认）。

## Java 侧端点全集（Python 反代要对接的）
```
基础: /health /overview(?full) /histogram /dominator /threads /threadlocals /object /oql
诊断: /leak-suspects /retained-set /merge-paths /top-consumers /path2gc  (重操作→taskId) /tasks/{id}
通用: /tools /query
```

---

## 阶段一：解析闭环（最高优先，先让"能传能解析能看进度"）
> 产出：用户能上传 hprof → 队列解析 → 前端看进度 → 解析完成。这一步不依赖 query-service。

### T1.1 数据表
- [ ] `heapdump_reports` 加进现有 `init_db()`（字段见 TODO-PYTHON-TEAM；含 status/progress/phase/dump_dir/worker_id/heartbeat/attempts）。
- 验收：表建出来，字段齐。

### T1.2 分块上传
- [ ] `/api/heapdump-reports/uploads` 系列（create/chunk/status/complete）。
- [ ] complete：流式合并落 NFS `dumps/<hd_id>/app.hprof` → 校验 hprof magic → 建报告(QUEUED) → 计 file_upload 配额。
- 验收：传一个几 GB hprof，断点续传可用，complete 后 NFS 有文件、DB 有 QUEUED 记录。

### T1.3 解析 worker
- [ ] DB 队列消费：`SELECT ... FOR UPDATE SKIP LOCKED`（多 worker 安全）。
- [ ] 调解析：复用 `parser-cli/python-worker-snippet.py` 的 `run_parse_v1`（fork MAT 解析 + `mat_parse_runner.TextProgressMapper` 解析文本进度）。
- [ ] 进度回写 `heapdump_reports.progress/phase`；中断（status=CANCEL_REQUESTED → kill + 清半成品 index）；僵尸任务（heartbeat 超时重置）。
- 验收：QUEUED 任务被解析，progress 推进到 DONE，dump 目录生成 `*.index`；中断能停。

### T1.4 进度/报告 API
- [ ] `GET /api/heapdump-reports`（列表）、`/{id}`（详情）、`/{id}/progress`（SSE）、`/{id}/cancel`、`DELETE /{id}`。
- 验收：前端能看实时进度、能中断、能删。

**阶段一里程碑**：上传 → 看进度 → DONE，全程跑通（还不能查内容）。

---

## 阶段二：交互查询（反代到 query-service）
> 产出：解析完成后前端出现"类 MAT 菜单"，能用各视图分析。

### T2.1 反代基础
- [ ] `httpx.AsyncClient` 封装到查询服务（内网地址，超时/重试）。
- [ ] 鉴权后校验 report 属当前 user/org（行级权限）→ 取 `dump_dir` → 反代，计 api_call 配额。

### T2.2 stats 回填（衔接阶段一）
- [ ] 解析 DONE 后，worker 调 `GET 8090/overview?dumpDir=<dump_dir>` → 写 `heapdump_reports.stats`。
  （index 已在 NFS，reopen 秒级。）
- 验收：报告详情显示对象数/类数/堆大小。

### T2.3 视图端点（薄反代，dumpDir 由后端补）
- [ ] `GET /api/heapdump-reports/{id}/overview?full=true` → 8090/overview
- [ ] `.../histogram` `.../dominator` `.../threads` `.../threadlocals` `.../object` → 对应 8090 端点
- [ ] `POST .../oql`（透传 q/view/sort）
- 验收：前端各菜单返回正确数据（对照 query-service/DEPLOY.md 实测形态）。

### T2.4 重操作（异步任务透传）
- [ ] `.../leak-suspects` `.../retained-set` `.../merge-paths` → 8090 返回 taskId
- [ ] `.../tasks/{taskId}` 轮询透传（或 Python 包装成自己的任务给前端进度）。
- 验收：Leak Suspects 能跑出结论，前端看到进度。

**阶段二里程碑**：前端类 MAT 菜单全部可用。

---

## 阶段三：AI 诊断 + Agent 集成（平台差异化）
> 产出：报告能 AI 诊断；对话里能挂 heapdump 追问；跨 gc/jstack/heapdump 联合诊断。

### T3.1 AI 诊断（对齐 gc/jstack）
- [ ] `POST /api/heapdump-reports/{id}/analyze`：内部调 leak-suspects + 关键视图 → 喂 LLM → 写 `ai_conclusion`，计 llm 配额。
- 验收：报告显示 AI 结论。

### T3.2 Agent 工具（注入现有 ReAct loop）
- [ ] 扩展 `report_context` type=`heapdump`（**只传 report_id**，agent 按需取，不塞数据）。
- [ ] 新增 mat_* 工具（首参 report_id）：`mat_overview/histogram/dominator/threads/threadlocals/oql/path2gc/leak_suspects/...`
      - 工具内部：report_id → dump_dir → 反代 8090 → 出参已瘦身。
      - 就绪校验：status≠DONE 返回"解析中 X%"。
      - 可选 `mat_list_tools(filter)` → 反代 8090/tools（精简版），让 agent 检索长尾。
- 验收：对话"有没有内存泄漏" → agent 调 mat_leak_suspects 给带证据结论。

### T3.3 跨数据源联合诊断（最大价值）
- [ ] agent 工具箱同时有 gc/jstack/heapdump，可关联（jstack 线程卡 getConnection + heapdump 连接池满 → 连接池泄漏）。
- 验收：挂多个 report 的对话能给关联结论。

---

## 关键依赖与顺序
```
阶段一(解析闭环) ─┬─ 阶段二(查询) ── 阶段三(Agent)
                  └─ stats回填(T2.2) 依赖 阶段一DONE + query-service
query-service 必须先在内网起好(阶段二/三前置)
```

## 复用物料（直接抄/改，别重写）
| 用途 | 文件 |
|---|---|
| worker 调解析器 + 文本进度 | `parser-cli/python-worker-snippet.py` |
| 进度映射器（已实测修过 bug） | `parser-cli/v1-parsehd/mat_parse_runner.py` |
| 表结构/接口/反代/agent 设计 | `mat-study/07` + `TODO-PYTHON-TEAM.md` |
| Java 端点契约 + curl 示例 | `query-service/DEPLOY.md` |
| agent 工具粒度/出参瘦身 | `mat-study/04` |

## 风险点
- `com.sun.net.httpserver` 若 query-service 启动报不可见 → config.ini 暴露包（DEPLOY.md 有）。这是 Java 侧问题，阶段二前确认 8090 健康。
- 大 hprof 解析内存/耗时 → 解析机 `-Xmx` 按最大 dump 估算；worker 单并发起步。
- SSE 长连接 + 解析分钟级 → 注意超时/重连。
- 重操作（leak/retained）耗时 → 必须走异步 taskId，别在请求里同步等。

## 建议节奏
1. **先做阶段一**（解析闭环）——它独立、价值即时（能传能看进度），且不依赖 query-service 联调。
2. 再做阶段二（查询）——query-service 已实测，主要是反代管道工 + stats 回填。
3. 阶段三（Agent）——平台差异化，放最后但价值最高。
