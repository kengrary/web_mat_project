# JVMind Heapdump 模块实施计划

> 本文档是 `IMPLEMENTATION_GUIDE.md` 的**执行侧配套**，将设计拆解为可跟踪、可验收的任务清单。
> 每个任务标注：交付物、依赖、预估工时、验收标准、风险点。
>
> 时间估算基于 1 名熟悉 JVMind 代码库的后端 + 1 名前端工程师，串行开发。并行可缩短 30~40%。

---

## 总体节奏

```
Week 1        Week 2        Week 3        Week 4        Week 5
├─ P1 ────────┤
├── DB/API骨架─┤
              ├─ P2 ────────┤
              ├─ Worker+异步框架─┤
                            ├─ P3 ─────────┤
                            ├─ 反代+Agent──┤
                                          ├─ P4 ─────────────────┤
                                          ├─ 前端渐进式面板─────┤
                                                                ├─ 联调
                                                                ├─ P5
```

**里程碑评审点**：每个 P 阶段结束前 1 天，全员评审验收标准，确认可进入下一阶段。

---

## P1：Python 报告骨架（预估 5 人日）

**目标**：建立数据层 + 上传/管理 API，让"上传 hprof → DB 有 QUEUED 记录 → API 可查"这条链路跑通。

### P1.1 数据模型（0.5 人日）

| # | 任务 | 交付物 | 依赖 |
|---|---|---|---|
| P1.1.1 | 在 `react_agent/models.py` 追加 `HeapdumpReportModel` | 代码 + 单测（建表成功） | — |
| P1.1.2 | 在 `SessionModel` 添加 `heapdump_reports` relationship | 代码 | P1.1.1 |
| P1.1.3 | 本地跑 `python -c "from react_agent.db import init_db; init_db()"` 验证自动建表 | 数据库有新表 | P1.1.1~2 |

**验收**：`sqlite3 data/app.db ".schema heapdump_reports"` 返回完整表结构。

### P1.2 Memory 层 CRUD（1 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P1.2.1 | `react_agent/memory_db.py` 加 `add_heapdump_report` | 代码 |
| P1.2.2 | 加 `list_heapdump_reports` / `get_heapdump_report` | 代码 |
| P1.2.3 | 加 `update_heapdump_report` / `delete_heapdump_report` | 代码 |
| P1.2.4 | 加 `get_heapdump_report_by_id`（跨 session 查询，Worker 用） | 代码 |
| P1.2.5 | 修改 `load()` 增加 `heapdump_reports` 字段 | 代码 |
| P1.2.6 | 修改 `list_all_reports()` 支持 `type="heapdump"` | 代码 |
| P1.2.7 | 单测：`_tests/test_heapdump_memory.py` 覆盖 CRUD | 测试通过 |

**验收**：`pytest _tests/test_heapdump_memory.py -v` 全绿。

### P1.3 分块上传 API（2 人日）

| # | 任务 | 交付物 | 备注 |
|---|---|---|---|
| P1.3.1 | 新建 `app/routes/heapdump_upload.py` 骨架 | 文件 + router | 复用 `helpers._get_current_user` 等 |
| P1.3.2 | 实现 `POST /uploads` 创建会话 | 返回 `{upload_id, chunk_size: 8MB}` | 会话存 `/tmp/heapdump-chunks/{uid}/` |
| P1.3.3 | 实现 `PUT /uploads/{uid}/chunks/{i}` 流式写盘 | 边读边写边算 MD5 | ⚠️ 必须用 `async for chunk in request.stream()` |
| P1.3.4 | 实现 `GET /uploads/{uid}/status` 断点续传 | 返回已收 chunk 索引数组 | 扫描目录下 `*.part` |
| P1.3.5 | 实现 `POST /uploads/{uid}/complete` 合并+落 NFS+建记录 | 返回 `{report_id, status: QUEUED}` | 校验 hprof 魔数；扣 `file_upload_count` |
| P1.3.6 | 单测：模拟 3 块 chunk 上传 → complete → 校验 DB 记录 | 测试通过 | 用 pytest tempdir 模拟 NFS |

**验收**：
```bash
# 用 curl 端到端测试
UID=$(curl -X POST /api/heapdump-reports/uploads | jq -r .upload_id)
curl -X PUT --data-binary @chunk0.bin /api/heapdump-reports/uploads/$UID/chunks/0
curl -X POST /api/heapdump-reports/uploads/$UID/complete
# → DB 中有 hd_xxx 记录，status=QUEUED，NFS 有 app.hprof
```

**风险**：
- FastAPI `request.stream()` 与 `request.body()` 只能消费一次，注意别混用。
- Windows 上 `/tmp` 路径问题：用 `tempfile.gettempdir()`。

### P1.4 报告管理 API（1 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P1.4.1 | 新建 `app/routes/heapdump_reports.py` 骨架 | 文件 + router |
| P1.4.2 | 实现 `GET /api/heapdump-reports?session_id=&org_id=` 列表 | 代码 |
| P1.4.3 | 实现 `GET /api/heapdump-reports/{id}` 详情 | 代码 |
| P1.4.4 | 实现 `GET /api/heapdump-reports/{id}/progress` **SSE** 进度流 | 代码，用 `sse-starlette` |
| P1.4.5 | 实现 `POST /api/heapdump-reports/{id}/cancel` 置 CANCEL_REQUESTED | 代码 |
| P1.4.6 | 实现 `DELETE /api/heapdump-reports/{id}` 删 DB + NFS | 代码，`shutil.rmtree(dump_dir)` |
| P1.4.7 | 实现 `POST /api/heapdump-reports/{id}/analyze` AI 诊断（先桩） | 代码，扣 `llm_call` |

**验收**：
```bash
curl /api/heapdump-reports?session_id=xxx        # 返回列表
curl -N /api/heapdump-reports/hd_xxx/progress    # SSE 保持连接
curl -X POST /api/heapdump-reports/hd_xxx/cancel # 状态变更
```

### P1.5 注册 + 集成（0.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P1.5.1 | `server.py` 注册 `heapdump_reports_router` + `heapdump_upload_router` | 代码 |
| P1.5.2 | `app/routes/chat.py` 的 `_remember_report_context` 加 `heapdump` 分支 | 代码 |
| P1.5.3 | `_build_report_context_message` 加 `heapdump` 分支（工具提示 `mat_overview({rid})`） | 代码 |
| P1.5.4 | 冒烟测试：`pytest _tests -x --no-cov` 不破坏现有测试 | 全绿 |

**P1 阶段验收标准**：
- [ ] 上传 hprof（模拟分块） → DB 有 QUEUED 记录 → NFS 有 app.hprof
- [ ] `GET /api/heapdump-reports` 返回列表
- [ ] SSE 进度流可连接（虽然没 Worker，进度=0）
- [ ] Cancel/Delete API 正常
- [ ] 对话可挂 heapdump report_context（type=heapdump）
- [ ] 现有测试全部通过

---

## P2：Worker + 解析闭环 + 异步任务框架（预估 6 人日）

**目标**：Worker 独立进程能自动抢任务解析，前端能看到进度推进，解析完 stats 有值；异步任务框架就绪。

### P2.1 Worker 骨架（1 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P2.1.1 | 新建 `react_agent/heapdump_worker/__init__.py` 模块 | 文件 |
| P2.1.2 | 新建 `__main__.py`，支持 `python -m react_agent.heapdump_worker` 启动 | 文件 |
| P2.1.3 | 实现 `worker_loop()` 主循环 + `stop_event` 优雅关闭 | 代码 |
| P2.1.4 | 实现 `claim_task()` 用 PG `FOR UPDATE SKIP LOCKED` | 代码，兼容 SQLite 单进程模式 |
| P2.1.5 | 实现 `_update_report()` DB 写操作辅助函数 | 代码 |

**验收**：`python -m react_agent.heapdump_worker` 启动后每 5s 空转打印 "no task"。

### P2.2 集成 V1 解析器（1.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P2.2.1 | 复制 `mat_parse_runner.py` 到 `react_agent/heapdump_worker/mat_parse_runner.py` | 文件 |
| P2.2.2 | 实现 `run_parse_v1()` 调用（asyncio 子进程） | 代码 |
| P2.2.3 | 实现 `on_update(progress, phase)` → 更新 DB | 代码，节流 1s |
| P2.2.4 | 实现 `should_cancel()` → 查 DB `status == CANCEL_REQUESTED` | 代码 |
| P2.2.5 | 退出码处理：0=DONE, 79=FAILED(OOM), 130=CANCELLED, 其他=FAILED | 代码 |
| P2.2.6 | 失败/中断时清理半成品 `*.index` | 代码，`_clean_partial_indexes` |

**验收**：手动放一个 hprof 到 NFS + DB 插入 QUEUED 记录，Worker 抢到 → 进度推进 → 最终 DONE。

### P2.3 心跳 + 看门狗（1 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P2.3.1 | Worker 内启动心跳协程，每 30s 更新 `heartbeat` | 代码 |
| P2.3.2 | 实现 `watchdog_loop()` 每 60s 扫描超时任务 | 代码 |
| P2.3.3 | 超时任务 (`heartbeat < now - 5min`) 且 `attempts < 3` 重置为 QUEUED | 代码 |
| P2.3.4 | `attempts >= 3` → 置 FAILED | 代码 |
| P2.3.5 | 单测：模拟 Worker 死亡，验证看门狗重置 | 测试通过 |

**验收**：kill Worker 进程 → 5 分钟后另一个 Worker 抢到该任务，`attempts=1`。

### P2.4 Stats 回填（0.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P2.4.1 | 解析完成后调 `MAT_QUERY_SERVICE_URL/overview?dumpDir=&full=true` | 代码，`httpx.AsyncClient` |
| P2.4.2 | 将 JSON 写入 `heapdump_reports.stats` | 代码 |
| P2.4.3 | 失败时容忍：stats 留空，state 仍置 DONE | 代码，warning 日志 |

**验收**：DONE 记录的 `stats` 字段包含 `numObjects` / `numClasses` / `usedHeapSize` 等。

### P2.5 前端异步任务框架（1.5 人日）

> ⚠️ **提前到 P2**：为 P4 的异步诊断视图（Leak Suspects / OOM 复合诊断）铺路。

| # | 任务 | 交付物 |
|---|---|---|
| P2.5.1 | 新建 `frontend/src/heapdump-analysis/task-poller.js` | 文件 |
| P2.5.2 | 实现 `pollTask(taskId, opts)` 轮询助手 | 代码，覆盖 RUNNING/DONE/FAILED/resultEvicted/NOT_FOUND |
| P2.5.3 | 实现 `AsyncTaskUI` 组件：4 状态渲染（运行中/完成/失败/结果过期） | 代码 |
| P2.5.4 | 实现 `cancelTask(taskId)` → `DELETE /tasks/{id}` | 代码 |
| P2.5.5 | Vitest 单测：mock fetch 覆盖 4 状态 + 超时 | 测试通过 |

**验收**：`AsyncTaskUI` 可独立测试，模拟 5s RUNNING → DONE 场景正确渲染。

### P2.6 部署脚本 + 文档（0.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P2.6.1 | 在 `AGENTS.md` 添加 Worker 启动命令 | 文档 |
| P2.6.2 | 在 `.env.example` 添加 heapdump 相关环境变量 | 文件 |
| P2.6.3 | 编写 `scripts/start_heapdump_worker.sh`（可选） | 脚本 |

**P2 阶段验收标准**：
- [ ] `python -m react_agent.heapdump_worker` 独立进程启动
- [ ] 上传 hprof → Worker 自动抢到 → SSE 进度 0→100%
- [ ] 解析完成后 `status=DONE`，`stats` 字段有真实数据
- [ ] Cancel API 触发 → Worker 检测到 → 清理 index → `status=CANCELLED`
- [ ] Worker 死亡后看门狗重置任务，第二个 Worker 抢到
- [ ] 前端 `pollTask` + `AsyncTaskUI` 单测通过

---

## P3：查询反代 + Agent 工具（预估 5 人日）

**目标**：Python 反代所有 query-service 端点，Agent 可通过 mat_* 工具查询 heapdump。

### P3.1 反代基础设施（1 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P3.1.1 | 新建 `app/routes/heapdump_proxy.py` | 文件 + router |
| P3.1.2 | 实现 `mat_client` 单例（`httpx.AsyncClient`） | 代码，在 lifespan 打开/关闭 |
| P3.1.3 | 实现 `_check_report_ready(report_id)` 校验权限 + 状态 | 代码 |
| P3.1.4 | 实现 `_proxy_get(url, params)` / `_proxy_post(url, params, body)` 通用反代 | 代码，超时分级 |
| P3.1.5 | 实现 `_slim_response(kind, raw)` 出参瘦身 | 代码 |

**超时分级**：
```python
TIMEOUT_MAP = {
  "overview": 10, "histogram": 30, "dominator": 30,
  "threads": 15, "object": 10, "oql": 60,
  "path2gc": 30, "leak-suspects": 300, "diagnose-oom": 300,
}
```

### P3.2 同步端点反代（1.5 人日）

按端点表逐个实现（每个 ~15 分钟）：

- [ ] `GET /overview`（支持 `full=true`）
- [ ] `GET /histogram`
- [ ] `GET /dominator`
- [ ] `GET /threads`（不传/thread/frame 三级）
- [ ] `GET /threadlocals`
- [ ] `POST /oql`
- [ ] `GET /object` ⭐ **Inspector 关键**
- [ ] `GET /path2gc`
- [ ] `GET /top-consumers`
- [ ] `GET /oom-diagnosis` ⚠️ 同步轻量
- [ ] `GET /sql-in-threads`
- [ ] `GET /connection-pools`
- [ ] `GET /pool-leaks`

**验收**：curl 测试每个端点，参数透传 + dumpDir 注入正确。

### P3.3 异步端点反代（1 人日）

- [ ] `POST /leak-suspects` → 返回 taskId
- [ ] `GET /diagnose-oom` → 返回 taskId ⚠️ 异步重
- [ ] `POST /retained-set`
- [ ] `POST /merge-paths`
- [ ] `GET /tasks/{task_id}` 轮询
- [ ] `DELETE /tasks/{task_id}` 取消

**验收**：`POST /leak-suspects` → 拿 taskId → 轮询 `/tasks/{id}` → 最终 DONE 返回 result。

### P3.4 Agent 工具实现（1.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P3.4.1 | 新建 `react_agent/mat_tools.py` | 文件 |
| P3.4.2 | 实现 `mat_tool_dispatch(memory, session_id, name, arg)` 分发器 | 代码 |
| P3.4.3 | 实现 11 个 mat_* 工具（overview/histogram/dominator/threads/oql/object/path2gc + 4 复合） | 代码 |
| P3.4.4 | 每个工具内部：查 DB → 校验 status=DONE → 调反代 → 瘦身返回 | 代码 |
| P3.4.5 | 异步工具（leak_suspects / diagnose_oom）内置轮询逻辑 | 代码 |
| P3.4.6 | `react_agent/tools.py` 注册 mat_* 工具（描述用中英双语） | 代码 |
| P3.4.7 | `react_agent/agent/tools_exec.py` 加 `mat_` 前缀分发 | 代码 |

**验收**：单测 mock query-service → 调用 `mat_overview("hd_xxx")` 返回瘦身 JSON。

**P3 阶段验收标准**：
- [ ] 所有反代端点 curl 通过（同步 + 异步）
- [ ] Agent 对话中调 `mat_leak_suspects("hd_xxx")` 走通异步轮询，最终返回结论
- [ ] 未 DONE 的报告调工具返回"解析中(进度 X%)"
- [ ] 每次交互查询扣 `api_call` 配额

---

## P4：前端渐进式面板（预估 8 人日）

**目标**：从概览页到全功能分析器，完整复刻 web-prototype 交互，适配 JVMind 风格。

### P4.1 模块骨架 + 状态管理（0.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.1.1 | 新建 `frontend/src/heapdump-analysis/` 目录 | — |
| P4.1.2 | `state.js` 加 `openHeapdumpReports` / `currentHeapdumpReport` 等字段 | 代码 |
| P4.1.3 | `heapdump-analysis/index.js` 统一导出 | 文件 |
| P4.1.4 | `gc-analysis/context.js` 的 `LABEL_MAP` 加 `heapdump: "Heapdump"` | 代码 |
| P4.1.5 | `tabs.js` 的 `openReport` 增加 heapdump 分支 | 代码 |

### P4.2 分块上传 UI（1 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.2.1 | `heapdump-analysis/upload.js`：拖放/选择/分块 | 代码 |
| P4.2.2 | 分块进度条 + 单块失败重传 | UI |
| P4.2.3 | 断点续传：刷新页面后 `GET /uploads/{uid}/status` 恢复 | 代码 |
| P4.2.4 | 上传完成自动打开报告标签页 + 连 SSE 进度 | 代码 |

### P4.3 精简概览面板（模式 A，1.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.3.1 | `heapdump-analysis/summary.js` 渲染概览面板 | 代码 |
| P4.3.2 | 数据源：`/overview?full=true` | 代码 |
| P4.3.3 | 5~6 个核心指标卡片（对象数/类数/堆大小/GC Roots/类加载器/不可达） | UI |
| P4.3.4 | **OOM 横幅**：调 `/oom-diagnosis`（同步）→ 若检测到 OOM 触发线程显示醒目横幅 | 代码 |
| P4.3.5 | 6 个快速诊断按钮 | UI |
| P4.3.6 | AI 诊断折叠区（复用 gc/jstack 样式） | UI |
| P4.3.7 | 「添加到对话」按钮 → `bindReportContext("heapdump", report)` | 代码 |

### P4.4 全功能分析器骨架（模式 B，1 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.4.1 | `heapdump-analysis/analyzer.js` 三栏布局 | 文件 |
| P4.4.2 | 左导航（`HEAPDUMP_VIEWS`）+ 视图切换路由 | 代码 |
| P4.4.3 | 中央内容区（视图 mount/unmount） | 代码 |
| P4.4.4 | 右 Inspector 面板骨架 | 代码 |
| P4.4.5 | 响应式：>1400px 三栏 / 中等折叠 / 移动端全屏切换 | CSS |
| P4.4.6 | `heapdump-analysis/analyzer.css` 复用现有暗色变量 | CSS |

### P4.5 Histogram 视图（0.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.5.1 | `views/histogram.js` 表格 + 列点击排序 | 代码 |
| P4.5.2 | 分页（`top/offset` cursor） | 代码 |
| P4.5.3 | 行点击 → Inspector；右键菜单 | 代码 |
| P4.5.4 | 支持 `objectSet=rs-xxx` 参数（结果集复用） | 代码 |

### P4.6 Dominator Tree（0.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.6.1 | `views/dominator.js` 懒加载树 | 代码 |
| P4.6.2 | 点击 `▶` 展开子节点（`?parent=<objectId>`） | 代码 |
| P4.6.3 | percent 列 + 进度条条形图 | UI |

### P4.7 Threads 三级树（0.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.7.1 | `views/threads.js` 三级懒加载 | 代码 |
| P4.7.2 | 线程 → 栈帧 → 局部变量 | 代码 |
| P4.7.3 | 局部变量显示 retained + 点击进 Inspector | 代码 |

### P4.8 OQL + 结果集复用（1 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.8.1 | `views/oql.js` textarea 编辑器 | 代码 |
| P4.8.2 | 执行按钮 + 结果表格 | 代码 |
| P4.8.3 | 返回 `resultSetId` → 显示"结果集操作条" | UI |
| P4.8.4 | 结果集操作条：[Histogram] [Retained Set] [Merge Paths] | 代码 |
| P4.8.5 | 点击 [Histogram] → 切到 Histogram 视图 + `objectSet=rs-xxx` | 代码 |

### P4.9 诊断视图（0.5 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.9.1 | `views/connection-pools.js` 同步渲染 | 代码 |
| P4.9.2 | `views/sql-in-threads.js` 同步渲染 | 代码 |
| P4.9.3 | `views/leak-suspects.js` 异步（复用 `AsyncTaskUI`） | 代码 |
| P4.9.4 | `views/diagnose-oom.js` 异步（复用 `AsyncTaskUI`） | 代码 |
| P4.9.5 | `views/threadlocals.js` 同步渲染 | 代码 |

### P4.10 Inspector 面板（1 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.10.1 | `inspector.js` 骨架 | 代码 |
| P4.10.2 | 数据源：`GET /object?id=<objectId>` | 代码 |
| P4.10.3 | 字段表（可搜索/排序） | UI |
| P4.10.4 | 出入引用列表（点击跳到该对象） | 代码 |
| P4.10.5 | 快捷动作：`🔍` / `🌲` / `🔗 Path` / `📈 Histogram of` | 代码 |
| P4.10.6 | 底部「💬 问 Agent」按钮 → 发对话消息（附带 reportId + objectId） | 代码 |

### P4.11 联调与打磨（1 人日）

| # | 任务 | 交付物 |
|---|---|---|
| P4.11.1 | 端到端测试：上传 → 解析 → 打开分析器 → 各视图 → Inspector → 问 Agent | 手动测试 |
| P4.11.2 | 修复视觉不一致（与 gc/jstack 对比） | UI 调整 |
| P4.11.3 | 处理边界：空数据/加载失败/网络断开/结果过期 | 代码 |
| P4.11.4 | 补 Vitest 关键单测（context 扩展、pollTask、resultSetId 复用） | 测试 |

**P4 阶段验收标准**：
- [ ] 上传 hprof → 分块进度 → 解析进度 → DONE
- [ ] 点击「Histogram」→ 表格数据正常 → 点击行 → Inspector 显示对象详情
- [ ] Inspector 点击「Path to GC」→ 切到 Path2GC 视图
- [ ] OQL 执行 → 拿到 resultSetId → 点击「对结果集做 Histogram」 → 表格显示
- [ ] 点击「Leak Suspects」→ 显示"运行中..." → 完成后显示结论；失败时有重试按钮
- [ ] Inspector 底部「💬 问 Agent」→ 对话区出现新消息 → Agent 调 mat_path2gc 返回结论
- [ ] 「添加到对话」→ 后续对话中 Agent 能识别 heapdump report_context

---

## P5：增强与压测（预估 5 人日，可选延后）

| # | 任务 | 预估 |
|---|---|---|
| P5.1 | 秒传（sha256 匹配已 DONE 记录） | 1 人日 |
| P5.2 | 前端 Histogram / Dominator 图表可视化（复用 ECharts） | 2 人日 |
| P5.3 | 两 dump 对比（delta_histogram） | 1 人日 |
| P5.4 | V1 → V2 解析器切换（JSONL 精细进度） | 1 人日 |
| P5.5 | 大 hprof（GB 级）性能压测 + 内存调优 | — |
| P5.6 | 定时清理任务（过期报告 / LRU 磁盘清理） | — |

---

## 依赖关系图

```
P1 (5d) ──▶ P2 (6d) ──▶ P3 (5d) ──▶ P4 (8d) ──▶ P5 (5d)
   │            │           │           │
   │            └── P2.5 (前端异步框架，提前铺路) ──▶ P4.9
   │
   └── P1.5.2/3 (chat.py) ──▶ P3.4 (mat_tools) ──▶ P4.10.6 (问 Agent)
```

**关键路径**：P1.3 分块上传 → P2.2 V1 解析 → P3.2 反代 `/object` → P4.10 Inspector 是 MVP 的完整闭环。

---

## 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| PostgreSQL 部署延迟（现有测试用 SQLite） | Worker `SKIP LOCKED` 无法测 | P2 先用 SQLite 单进程模式跑通，PG 到位后 5 分钟切换 |
| MAT 发行版路径 / MAT_HOME 配置错 | Worker 启动失败 | P2.1 增加启动自检脚本 |
| query-service 未部署 | P3 反代无法端到端测 | P3 mock query-service 响应，等真实服务到位后仅需切换 base_url |
| NFS 权限配置繁琐 | 跨机部署卡壳 | P1 先用本地路径跑通，P2 部署时按需切 NFS |
| 前端异步任务 UI 复杂度低估 | P4 时间超预算 | 已提前到 P2.5，复用组件 |
| 大 hprof 内存/超时 | 用户体验差 | P5.5 压测，P3 反代加分级超时 |

---

## 单元测试策略

| 测试文件 | 覆盖内容 | 阶段 |
|---|---|---|
| `_tests/test_heapdump_memory.py` | Memory 层 CRUD | P1 |
| `_tests/test_heapdump_upload.py` | 分块上传、断点续传、魔数校验 | P1 |
| `_tests/test_heapdump_reports_api.py` | 列表/详情/SSE/cancel/delete | P1 |
| `_tests/test_heapdump_worker.py` | claim_task, 进度更新, 心跳, 看门狗 | P2 |
| `_tests/test_mat_parse_runner.py` | TextProgressMapper（复用现有测试） | P2 |
| `_tests/test_heapdump_proxy.py` | 反代同步/异步端点，权限校验 | P3 |
| `_tests/test_mat_tools.py` | mat_* 工具，就绪校验，瘦身 | P3 |
| `frontend/src/heapdump-analysis/*.test.js` | 关键组件（pollTask、context、resultSetId） | P4 |

**覆盖率目标**：新增代码整体覆盖率 ≥ 70%，关键路径（Worker/反代/上传）≥ 85%。`pytest.ini` 现有 `--cov-fail-under=63` 需保持不下降。

---

## 团队协作建议

1. **P1 完成前不要开始 P2 编码**：数据模型是所有后续工作的基础，改动成本递增。
2. **P2 和 P3 可并行**（不同工程师）：P2 是 Worker + 独立进程，P3 是 Web 反代，仅共享 DB。
3. **P4 前端可与 P3 后端并行**：P3 完成任一端点即可开始 P4 对应视图，用 mock 数据打底。
4. **每日站会关注**：Worker 心跳 / 反代超时 / NFS 权限 / 配额扣减这几个易踩坑点。
5. **代码评审重点**：
   - Worker 的异常路径（OOM/kill/crash 后 index 清理）
   - 反代层的权限 + 状态校验（避免绕过 Python 直连 8090 的隐患）
   - 前端 pollTask 的 4 状态处理

---

## 总工时汇总

| 阶段 | 后端 | 前端 | 合计 |
|---|---|---|---|
| P1 | 5 人日 | — | 5 人日 |
| P2 | 4.5 人日 | 1.5 人日 | 6 人日 |
| P3 | 5 人日 | — | 5 人日 |
| P4 | — | 8 人日 | 8 人日 |
| P5 | 3 人日 | 2 人日 | 5 人日 |
| **合计** | **17.5 人日** | **11.5 人日** | **29 人日** |

**MVP（P1~P4）**：24 人日 ≈ 5 周（1 后端 + 1 前端并行）或 8 周（单人串行）。

---

*文档版本：1.0*  
*配套设计文档：`IMPLEMENTATION_GUIDE.md`（v1.1）*
