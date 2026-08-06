# JVMind Heapdump 模块实现文档

> 本文档汇总架构设计、接口契约、前端交互与实施计划，供后续开发团队按图施工。
> 对应需求：`TODO-PYTHON-TEAM.md` + `mat-study/` 全系列设计文档。

---

## 1. 架构总览

### 1.1 部署拓扑（三节点模型）

```
                          外网入口（唯一）
   浏览器 ───────────────────▶ Python Web (FastAPI)
                                      │
    ┌─────────────────────────────────┼─────────────────────────────┐
    │  独立云服务器 / 内网（无外网入口）                              │
    │   ▼ 反代查询(httpx)               ▼ 触发解析(fork)              │
    │  Java query-service :8090       解析 Worker (独立进程)         │
    │        └──────── NFS(dump: hprof+index) ────────┘             │
    │        └──────── PostgreSQL(任务/报告) ─────────┘             │
    └───────────────────────────────────────────────────────────────┘
```

**硬性约定**：
- **Python 是唯一权威**：鉴权、配额、用户、会话、计费全在 Python。
- **Java 侧无外网入口、无鉴权、无 CORS**：安全靠网络隔离（安全组只放行 Python IP）+ `-DMAT_QUERY_BIND=<内网IP>`。
- **前端不直连 8090**：所有查询走 Python 反代 `/api/heapdump-reports/{id}/...`。
- **Worker 独立进程**：不在 `server.py` lifespan 内启动；通过 PG `SKIP LOCKED` 抢任务，天然支持水平扩展。

### 1.2 技术栈确认

| 层级 | 技术 | 说明 |
|---|---|---|
| Web / API | FastAPI + SQLAlchemy | 复用现有体系，新增路由 |
| 队列 / 状态 | PostgreSQL | 必须用 PG（SQLite 不能双进程共享） |
| 解析 | MAT 发行版 ParseHeapDump (V1) | `mat_parse_runner.py` 文本进度映射 |
| 查询 | query-service OSGi bundle (:8090) | 已实测跑通 |
| 存储 | NFS | POSIX + 随机 seek，直接 `openSnapshot` |
| Agent | 现有手写 ReAct loop | 注入 `mat_*` 工具 |
| 前端 | Vanilla JS ES modules | 渐进式：精简概览 → 全功能分析器 |

---

## 2. 数据模型

### 2.1 新增表 `heapdump_reports`

在 `react_agent/models.py` 追加：

```python
class HeapdumpReportModel(Base):
    __tablename__ = "heapdump_reports"
    __table_args__ = (
        Index("ix_heapdump_reports_session_id", "session_id"),
        Index("ix_heapdump_reports_org_id", "org_id"),
        Index("ix_heapdump_reports_status", "status"),
        Index("ix_heapdump_reports_user_id", "user_id"),
    )

    id = Column(Text, primary_key=True)           # hd_ + uuid4().hex
    session_id = Column(Text, ForeignKey("sessions.id"), nullable=False)
    org_id = Column(Text, ForeignKey("organizations.id"), nullable=True)
    user_id = Column(Text, ForeignKey("users.id"), nullable=False)
    filename = Column(Text, nullable=False)
    size = Column(Integer, default=0)
    status = Column(Text, default="QUEUED")       # QUEUED/PARSING/DONE/FAILED/CANCELLED/CANCEL_REQUESTED
    progress = Column(Integer, default=0)         # 0-100
    phase = Column(Text, default="")
    dump_dir = Column(Text, default="")           # NFS 路径
    parse_args = Column(Text, default="{}")
    stats = Column(Text, default="{}")            # overview 回填的 JSON
    ai_conclusion = Column(Text, default="")
    error = Column(Text, default="")
    worker_id = Column(Text, default="")
    heartbeat = Column(Text, default="")
    attempts = Column(Integer, default=0)
    created_at = Column(Text, default=_now_str)
    queued_at = Column(Text, default="")
    started_at = Column(Text, default="")
    finished_at = Column(Text, default="")

    session = relationship("SessionModel", back_populates="heapdump_reports")
```

**`SessionModel` 追加**：
```python
heapdump_reports = relationship("HeapdumpReportModel", back_populates="session", cascade="all, delete-orphan")
```

> `init_db()` 的 `Base.metadata.create_all` + `_auto_migrate()` 自动建表/补列，无需手写 SQL。

### 2.2 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DATABASE_URL` | `sqlite:///./data/app.db` | **必须改为 PostgreSQL** |
| `HEAPDUMP_STORAGE_ROOT` | `./data/heapdumps` | NFS 挂载点 |
| `MAT_QUERY_SERVICE_URL` | `http://127.0.0.1:8090` | query-service 地址 |
| `MAT_HOME` | `/opt/mat` | MAT 发行版目录（Worker 用） |
| `MAT_PARSE_XMX` | `4g` | 解析器堆内存 |
| `HEAPDUMP_WORKER_HEARTBEAT_TIMEOUT` | `300` | 看门狗超时（秒） |

---

## 3. Python API 设计

### 3.1 分块上传（`app/routes/heapdump_upload.py`）

| 方法 | 路径 | 职责 |
|---|---|---|
| `POST` | `/api/heapdump-reports/uploads` | 创建上传会话 → `{upload_id, chunk_size: 8MB}` |
| `PUT` | `/api/heapdump-reports/uploads/{uid}/chunks/{i}` | 接收分块，Content-MD5 校验，**流式写盘** |
| `GET` | `/api/heapdump-reports/uploads/{uid}/status` | 已收哪些块（断点续传） |
| `POST` | `/api/heapdump-reports/uploads/{uid}/complete` | 合并 → 校验 hprof 魔数 → 移 NFS → 建 `QUEUED` 记录 |

**关键约束**：
- 流式写盘：`async for chunk in request.stream(): f.write(chunk)`，O(1) 内存。
- 魔数校验：`JAVA PROFILE 1.0.x` 或 `\x1f\x8b`（gzip hprof）。
- 配额：complete 成功后扣 `file_upload_count`；失败不回滚（已占存储）。
- 秒传（可选）：complete 时算 sha256，匹配已有 DONE 记录则秒置 DONE。

### 3.2 报告管理（`app/routes/heapdump_reports.py`）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/heapdump-reports?session_id=&org_id=` | 列表 |
| `GET` | `/api/heapdump-reports/{id}` | 详情（status/progress/stats/ai_conclusion） |
| `GET` | `/api/heapdump-reports/{id}/progress` | **SSE** 进度流（`sse-starlette`） |
| `POST` | `/api/heapdump-reports/{id}/cancel` | 置 `CANCEL_REQUESTED` |
| `DELETE` | `/api/heapdump-reports/{id}` | 删 DB + NFS `dump_dir` |
| `POST` | `/api/heapdump-reports/{id}/analyze` | AI 诊断（计 `llm_call` 配额） |

### 3.3 查询反代（`app/routes/heapdump_proxy.py`）

**架构红线：前端只跟 Python 说话，绝不直连 query-service（:8090）。**

Python 鉴权后透传到 `MAT_QUERY_SERVICE_URL`。前端**不持有 dumpDir**，只传 `reportId`；`reportId → dumpDir` 由 Python 查 DB 后注入。

#### 同步端点（请求即返回 JSON）

| 方法 | 前端路径 | 反代目标 | 关键参数 |
|---|---|---|---|
| `GET` | `/api/heapdump-reports/{id}/overview` | `GET /overview?dumpDir=` | `full=true`（含 Heap Dump Info + System Properties） |
| `GET` | `/api/heapdump-reports/{id}/histogram` | `GET /histogram?dumpDir=` | `top=50` `offset=0` `sort=shallow\|retained\|count` `approx=true` `objectSet=rs-xxx` |
| `GET` | `/api/heapdump-reports/{id}/dominator` | `GET /dominator?dumpDir=` | `parent=ROOT\|<objectId>` `top=50` `offset=0` |
| `GET` | `/api/heapdump-reports/{id}/threads` | `GET /threads?dumpDir=` | 不传=线程列表（含 `address` 字段，MAT 内部 hex 形式，**仅展示用、不是 thread id**，thread id = `objectId`）；`thread=<id>`=栈帧；`frame=<...>`=局部变量 |
| `GET` | `/api/heapdump-reports/{id}/threadlocals` | `GET /threadlocals?dumpDir=` | `groupBy=valueClass\|thread` `top=100` |
| `POST` | `/api/heapdump-reports/{id}/oql` | `POST /oql?dumpDir=` | body `{"q":"...","limit":200,"offset":0}`；query `view=list\|histogram` `sort=...` |
| `GET` | `/api/heapdump-reports/{id}/object` | `GET /object?dumpDir=` | `id=<objectId>`（**Inspector 唯一数据源**：字段+静态字段+出/入引用+GC root+值） |
| `GET` | `/api/heapdump-reports/{id}/path2gc` | `GET /path2gc?dumpDir=` | `object=<id>` `excludeWeakSoft=true` |
| `GET` | `/api/heapdump-reports/{id}/top-consumers` | `GET /top-consumers?dumpDir=` | — |
| `GET` | `/api/heapdump-reports/{id}/oom-diagnosis` | `GET /oom-diagnosis?dumpDir=` | `culpritPct=30`（**同步轻量**，概览顶部 OOM 横幅用） |
| `GET` | `/api/heapdump-reports/{id}/sql-in-threads` | `GET /sql-in-threads?dumpDir=` | `onlyRisky=true` |
| `GET` | `/api/heapdump-reports/{id}/connection-pools` | `GET /connection-pools?dumpDir=` | —（含 Druid/HikariCP 空闲/活跃连接明细） |
| `GET` | `/api/heapdump-reports/{id}/pool-leaks` | `GET /pool-leaks?dumpDir=` | `thresholdMs=300000` |

#### 异步端点（提交 → 拿 taskId → 轮询 `/tasks/{id}`）

| 方法 | 前端路径 | 反代目标 | 备注 |
|---|---|---|---|
| `POST` | `/api/heapdump-reports/{id}/leak-suspects` | `POST /leak-suspects?dumpDir=` | leakhunter 全图分析 |
| `GET` | `/api/heapdump-reports/{id}/diagnose-oom` | `GET /diagnose-oom?dumpDir=` | `culpritPct=30` `onlyRisky=true`（**异步重操作**，聚合 OOM触发线程+SQL+Leak Suspects） |
| `POST` | `/api/heapdump-reports/{id}/retained-set` | `GET/POST /retained-set?dumpDir=` | body `{"objects":[ids]}` 或 `objectSet=rs-xxx` |
| `POST` | `/api/heapdump-reports/{id}/merge-paths` | `GET/POST /merge-paths?dumpDir=` | 同上 |
| `GET` | `/api/heapdump-reports/{id}/tasks/{task_id}` | `GET /tasks/{taskId}` | 轮询异步任务结果 |
| `DELETE` | `/api/heapdump-reports/{id}/tasks/{task_id}` | `DELETE /tasks/{taskId}` | 取消任务（用户切走视图时调） |

> ⚠️ **注意**：`oom-diagnosis`（同步轻量，用于概览横幅）和 `diagnose-oom`（异步重操作，用于菜单诊断）**是两个不同端点**，切勿混淆。

**反代层必须做**：
1. 行级权限：校验 `report.user_id == current_user_id` 或 org 成员。
2. 状态校验：`status != DONE` 时返回 `{"error": "解析中(进度 X%)，稍后再试"}`。
3. 出参瘦身：Histogram 只给 Top-20，保留 `objectId`。
4. 配额：每次交互查询扣 `api_call_count`；异步任务提交时扣 1 次，轮询不扣。

---

## 4. Worker 设计（独立进程）

### 4.1 进程模型

```bash
# Web 服务（无 Worker）
python server.py

# Worker 进程（可起多个，跨机部署）
python -m react_agent.heapdump_worker
```

### 4.2 核心流程

```
1. claim_task(): SELECT ... WHERE status='QUEUED' ORDER BY queued_at LIMIT 1 FOR UPDATE SKIP LOCKED
2. 抢到 → status=PARSING, started_at=now, worker_id=hostname:pid
3. fork MAT 解析子进程（run_parse_v1 / python-worker-snippet）
4. 实时解析 stdout → 映射 progress/phase → 更新 DB
5. 后台心跳协程每 30s 更新 heartbeat
6. 检测 CANCEL_REQUESTED → kill 子进程 → 清理半成品 index → status=CANCELLED
7. 解析完成（exit 0 + *.index 存在）→ 调 query-service /overview 回填 stats → status=DONE
```

### 4.3 退出码约定

| 退出码 | 含义 | 处理 |
|---|---|---|
| `0` | 成功 | `DONE` |
| `79` | OOM | `FAILED`，error="解析内存不足" |
| `130` | 被中断 (SIGINT) | `CANCELLED` |
| 其他非 0 | 异常 | `FAILED`，error="parser exited with code {rc}" |

### 4.4 看门狗（watchdog_loop）

每 60s 扫描：
- `status='PARSING'` 且 `heartbeat < now - 5min` → 判定 Worker 死亡
- `attempts < 3` → 重置为 `QUEUED`，`attempts += 1`
- `attempts >= 3` → `FAILED`

**注意**：看门狗不清理 index（Worker 可能还活着），由下一次抢到任务的 Worker 先检查 index 有效性。

---

## 5. Agent 集成

### 5.1 Report Context 扩展

```javascript
// 前端 state.activeReportContexts 已支持，只需加 label 映射
{ type: "heapdump", session_id: "...", report_id: "hd_xxx" }
```

`chat.py` 的 `_remember_report_context` / `_build_report_context_message` 增加 `heapdump` 分支：
- 工具提示：`mat_overview({rid})`、`mat_leak_suspects({rid})` 等。

### 5.2 Agent 工具注册

每个工具首参为 `report_id`，内部查 DB 拿 `dump_dir` → 反代 query-service。

| 工具名 | 类型 | 说明 |
|---|---|---|
| `mat_overview` | 原子 | 堆概况 |
| `mat_histogram` | 原子 | Top-N 类直方图 |
| `mat_dominator` | 原子 | 支配树（Top-N） |
| `mat_threads` | 原子 | 线程列表 |
| `mat_oql` | 原子 | OQL 查询 |
| `mat_object` | 原子 | 对象详情（Inspector 数据源） |
| `mat_path2gc` | 原子 | GC Root 路径 |
| `mat_leak_suspects` | 复合 | 泄漏嫌疑（异步轮询） |
| `mat_diagnose_oom` | 复合 | OOM 复合诊断（异步轮询） |
| `mat_connection_pools` | 复合 | 连接池诊断 |
| `mat_top_consumers` | 复合 | 内存占用归因 |

### 5.3 出参瘦身（硬规则）

- 原子工具默认只返回 **Top-20**，附 `total_count` 和 `"还有 N 条"` 提示。
- 数值带人性化单位（`523 MB`），保留 `objectId` 供下钻。
- 复合工具返回**结构化结论**（verdict/headline/sections），非原始数据。
- 就绪校验：工具调用前检查 `status=DONE`，否则返回 `"解析中(进度 X%)，稍后再试"`。

### 5.4 "问 Agent" 交互红线

**前端不直接调用 query-service，也不直接调 Agent 工具。**

Inspector 底部「💬 问 Agent」的实现方式：
1. 前端往当前对话**发一条消息**，附带 `reportId` + `objectId`（+ 预置问题，如"为什么 #5567 没被回收？"）。
2. Python 侧 ReAct loop 决定调 `/path2gc` 或 `/diagnose-oom` 等，返回结论到对话。

前端职责仅"组消息 + 触发发送"，工具编排归 Python。

---

## 6. 前端设计

### 6.1 渐进式两模式

| 模式 | 触发条件 | 形态 |
|---|---|---|
| **A. 精简概览** | 解析完成后默认 | 单页卡片（同 gc/jstack）：Overview 指标 + 快速诊断按钮 + AI 结论 |
| **B. 全功能分析器** | 点击任意"快速诊断按钮" | 类 MAT 三栏布局：左导航 / 中内容 / 右 Inspector |

### 6.2 精简概览面板（模式 A）

```
┌────────────────────────────────────────┐
│ Heapdump: app.hprof (2.3 GB)           │
│ [添加到对话]  [删除]                     │
├────────────────────────────────────────┤
│ 解析完成 ✅  对象 1,234,567 · 堆 1.2 GB │
│ 类 4,821 · GC Roots 530 · 类加载器 3   │   ← /overview?full=true
├────────────────────────────────────────┤
│ 📊 核心指标卡片（5~6 个）               │
├────────────────────────────────────────┤
│ 🔍 快速诊断按钮（点击 → 展开模式 B）     │
│  [Histogram][Dominator][Threads]        │
│  [🧪 OOM 复合诊断][🩺 Leak Suspects]    │
│  [🔌 Connection Pools][🔎 OQL]          │
├────────────────────────────────────────┤
│ 🤖 AI 诊断结论（同 GC/JStack 折叠区）    │
└────────────────────────────────────────┘
```

- 数据源：`/overview?full=true`（精简版会回填 stats）。
- 「添加到对话」→ 走现有 `report_context`（type=heapdump）。
- 快速诊断按钮点击 → 切换到模式 B 并定位到对应视图。

### 6.3 全功能分析器（模式 B）

```
┌─────────────────────────────────────────────────────────────┐
│ 🔙 返回概览 | app.hprof | [添加到对话] [×]关闭                │
├──────────┬──────────────────────────────────┬──────────────┤
│ 左导航    │ 内容区（随当前视图变化）           │ Inspector    │
│ 📊 概览   │  Histogram→虚拟表+排序+分页        │ 选中对象详情  │
│ 📈/🌲/🧵  │  Dominator→懒加载树               │ 字段/出入引用 │
│ 🩺🔌🧪🗄️ │  Threads→三级树                   │ /GC Root     │
│ 🔎 OQL    │  OQL→编辑器+结果表(resultSetId)    │ +快捷动作    │
├──────────┴──────────────────────────────────┴──────────────┤
│ 状态栏：选中 #5567 | retained 523 MB (45.7%)                │
└─────────────────────────────────────────────────────────────┘
```

**布局规则**：
- `>1400px`：三栏并排（左 180px / 中自适应 / 右 280px）
- 中等：Inspector 可折叠/点对象时从右侧滑出
- 移动端：单栏 + 底部 Tab 切视图，Inspector 全屏弹层

### 6.4 异步任务状态机（M2 就要搭好，否则 M4 卡死）

后端 TaskManager 真实状态：`RUNNING / DONE / FAILED / CANCELLED / NOT_FOUND`。结果是**软引用缓存**，可能被回收 → `DONE` 但带 `resultEvicted:true`。

**前端轮询助手**：
```javascript
async function pollTask(taskId, intervalMs = 1000, maxTries = 600) {
  for (let i = 0; i < maxTries; i++) {
    const t = await api(`/tasks/${taskId}`);
    if (t.status === 'DONE') {
      if (t.resultEvicted) throw new Error('结果已过期（软引用被回收），请重新分析');
      return t.result;
    }
    if (['FAILED', 'CANCELLED', 'NOT_FOUND'].includes(t.status))
      throw new Error(t.error || ('task ' + t.status));
    await sleep(intervalMs);   // RUNNING → 继续
  }
  throw new Error('task timeout');
}
```

**异步视图必须设计的 4 个 UI 状态**：
1. **运行中**：进度条/转圈 + "正在做全图分析，可能几分钟"。
2. **完成**：渲染结果。
3. **失败/超时**：FAILED/timeout 提示 + **重试按钮**。
4. **结果被回收**：`resultEvicted` → "结果已过期，点击重新分析"。

可选：用户切走视图时调 `DELETE /tasks/{id}` 取消任务，省后端资源。

### 6.5 左导航分组

```javascript
const HEAPDUMP_VIEWS = [
  { group: "概览", items: [{ id: "overview", icon: "📊", label: "Overview" }] },
  { group: "视图", items: [
    { id: "histogram", icon: "📈", label: "Histogram" },
    { id: "dominator", icon: "🌲", label: "Dominator Tree" },
    { id: "threads", icon: "🧵", label: "Threads" },
    { id: "threadlocals", icon: "🧷", label: "ThreadLocals" },
  ]},
  { group: "诊断", items: [
    { id: "diagnoseOom", icon: "🧪", label: "OOM 复合诊断" },   // → /diagnose-oom（异步）
    { id: "leakSuspects", icon: "🩺", label: "Leak Suspects" },  // → /leak-suspects（异步）
    { id: "connectionPools", icon: "🔌", label: "Connection Pools" },
    { id: "sqlInThreads", icon: "🗄️", label: "SQL in Threads" },
  ]},
  { group: "高级", items: [{ id: "oql", icon: "🔎", label: "OQL" }] },
];
```

> 诊断组里 🧪/🩺 是异步视图，需走 §6.4 状态机。
> ⚠️ `oom-diagnosis`（同步轻，用于概览横幅）和 `diagnose-oom`（异步重，用于菜单诊断）**是两个不同端点**。

### 6.6 各视图行为

**Histogram**（`/histogram`）：
- 列：Class | Count | Shallow | Retained
- 列点击排序（shallow/retained/count），默认 `retained` 降序
- 行点击 → Inspector；行右键 → `List objects` / `Path to GC Roots` / `Histogram of this`
- 虚拟滚动 + `top/offset` 分页

**Dominator Tree**（`/dominator?parent=ROOT`）：
- 懒加载树：只加载一层，点击 `▶` 展开子节点（`?parent=<objectId>` 取下一层）
- 显示 `percent`（retained 占总堆百分比）

**Threads**（`/threads`）：
- 三级懒加载——列表（不传参数）→ 栈帧（`?thread=<id>`）→ 局部变量（`?frame=<...>`）
- 局部变量直接显示 retained（连接泄漏归因金矿）

**OQL**（`POST /oql`）：
- `<textarea>` 编辑器 + 执行按钮
- 返回 `resultSetId` → 见 §6.7 结果集复用

**诊断视图**：
- Connection Pools（`/connection-pools` + `/pool-leaks`）
- OOM 复合（`/diagnose-oom` 异步，走状态机）
- Leak Suspects（`/leak-suspects` 异步，走状态机）
- SQL in Threads（`/sql-in-threads`）

### 6.7 Inspector（右栏，数据源 `/object?id=`）

```
┌────── Inspector ──────┐
│ #5567 CacheManager     │
│ Shallow 48 B           │   ← /object?id=5567
│ Retained 523 MB(45.7%) │
│ [🔍][🌲][🔗 Path][📈]  │   ← 快捷动作（打开新视图，带 objectId）
│ 字段表(可搜索/排序)     │
│ 出站引用(12) 入站(3)   │   ← 点引用 → 跳 /object 该对象
│ ───────────────────── │
│ [💬 问 Agent：为什么没回收] │  ← 见 §5.4，发对话消息，不直调工具
└────────────────────────┘
```

**快捷按钮行为**：
- `🔍 Inspect` → 刷新 Inspector（当前对象）
- `🌲 Dominator` → 切换到 Dominator 视图，`parent=该 objectId`
- `🔗 Path to GC` → 切换到 Path2GC 视图，`object=该 objectId`
- `📈 Histogram of` → 切换到 Histogram 视图，`objectSet=rs-xxx`（若当前有结果集）

**字段表 `targetClass` 约定**（`fields[]` / `staticFields[]` 每一项恒定 emit）：

| 值 | 含义 | 前端类型列渲染 |
|---|---|---|
| `"java.util.HashMap$Node"` 等非空字符串 | 后端从 `nr.getObject().getClazz().getName()` 拿到的完整类名 | 显示 `targetClass` |
| `""`（空串） | 后端尝试过但拿不到（`nr.getObject()` 抛 / `getClazz()` 为 null / synthetic class） | fallback 到 `type` 字段（通常 `"ref"`） |

> 隐式契约：`targetClass` 字段恒定存在，避免前端判空时与"字段缺失"混淆。同一约定未来可复用到其他"尝试过但失败"的字段（如 `targetSuperclass` 等）。

### 6.8 结果集复用（resultSetId）

`POST /oql` / `/retained-set` / `/merge-paths` 返回 `resultSetId`。前端把它当"一批对象的句柄"：
- 喂给 Histogram：`/histogram?objectSet=rs-xxx`（对这批对象按类聚合）
- 喂给 retained-set / merge-paths：`?objectSet=rs-xxx`

**UI**：结果表上方放一条「结果集 rs-xxx — 对这批对象：[Histogram] [Retained Set] [Merge Paths]」操作条。

这是"选一批对象→换角度再分析"的闭环，比看静态报告强一个量级。

### 6.9 沙箱/预览环境限制

- Arena 预览在 `sandbox="allow-scripts"`、**无网络**的 iframe 里：外部 CSS/JS/字体/图片/CDN **都加载不了**。
- 图标**用 emoji 或内联 SVG**，别引 icon font / 外部图标库（预览会全裂）。
- 样式用内联或 `<style>` 内嵌；下载到本地浏览器打开则一切正常（仅预览降级）。

---

## 7. 异常处理矩阵

### 7.1 解析阶段

| 场景 | Worker 动作 | DB 状态 | 前端 |
|---|---|---|---|
| 正常完成 | 回填 stats | `DONE` | 进度 100%，解锁菜单 |
| OOM (exit 79) | 清理 index；error="解析内存不足" | `FAILED` | 错误 + 重试按钮 |
| 被 kill (中断) | 清理 index | `CANCELLED` | "已中断" |
| Crash | 清理 index；error="异常退出" | `FAILED` | 同 OOM |
| 心跳超时 | 不清理；重置 QUEUED (attempts+1) | `QUEUED` | 回退到排队 |
| 无进度 >30min | kill 子进程 | `FAILED` | 超时提示 |

### 7.2 查询阶段

| 场景 | 反代层返回 | 前端 |
|---|---|---|
| 200 OK | 瘦身 JSON | 正常展示 |
| Java 500 (OOM) | `{"error": "查询服务内存不足"}` | 提示减少范围 |
| 404 (dumpDir 不存在) | `{"error": "报告文件不存在"}` | 提示已过期 |
| 超时 | `{"error": "查询超时"}` | 提示缩小范围 |
| status ≠ DONE | `{"error": "解析中(进度 X%)"}` | 灰色菜单/禁用 |

---

## 8. 存储生命周期

### 8.1 NFS 目录结构

```
HEAPDUMP_STORAGE_ROOT=/data/heapdumps
├── hd_abc123/
│   ├── app.hprof          # 原始文件（reopen 必需）
│   ├── app.idx.index      # MAT index
│   ├── app.o2c.index
│   ├── app.o2ret.index
│   └── app.threads
└── hd_def456/
    └── ...
```

### 8.2 权限

- Web 节点：NFS `ro` 挂载
- Worker 节点：NFS `rw` 挂载
- query-service 节点：NFS `ro` 挂载
- 目录 `chmod 700`

### 8.3 清理策略

| 触发条件 | 动作 | 执行者 |
|---|---|---|
| 用户删除报告 | `shutil.rmtree(dump_dir)` + DB 删行 | Web API |
| 过期（`upload_retention_days`） | 定时任务扫描 `created_at` | Web 后台 |
| 失败/CANCELLED 且 attempts >= 3 | 保留 7 天后清理 | 定时任务 |
| 磁盘 >85% | LRU 清理最旧的 DONE 目录 | 运维脚本 |

---

## 9. 配额计费点

| 操作 | 计费项 | 扣减时机 | 失败是否回滚 |
|---|---|---|---|
| 上传 complete | `file_upload_count` | 成功后 | **不回滚** |
| 交互查询 | `api_call_count` | 反代返回 200 | 失败/超时不扣 |
| AI 诊断 (`/analyze` 或 Agent 触发 LLM) | `llm_call_count` | LLM 请求前 | 失败回滚 |
| 异步任务提交 (leak-suspects 等) | `api_call_count` | 提交时扣 1 次 | 任务失败回滚 |
| 异步任务轮询 | — | 不扣 | — |

---

## 10. 实施里程碑

### P1：Python 报告骨架（数据层 + 上传 + 报告 API）

- [ ] `react_agent/models.py`：`HeapdumpReportModel` + `SessionModel` relationship
- [ ] `react_agent/memory_db.py`：heapdump CRUD 方法族
- [ ] `app/routes/heapdump_upload.py`：分块上传 4 端点
- [ ] `app/routes/heapdump_reports.py`：列表/详情/SSE 进度/取消/删除
- [ ] `server.py`：注册新路由
- [ ] `app/routes/chat.py`：report_context 扩展 `heapdump`

**验收**：上传 hprof → DB 有 QUEUED 记录 → API 列表/详情正常 → SSE 进度连接成功。

### P2：Worker + 解析闭环 + 异步任务框架（M2 必须搭好异步框架，否则 M4 卡死）

- [ ] `react_agent/heapdump_worker/`：独立进程入口
- [ ] `claim_task()` + `run_parse_v1()` + 进度映射
- [ ] 心跳 + 看门狗
- [ ] 解析完成后调 `/overview` 回填 stats
- [ ] cancel + 清理半成品 index
- [ ] **前端异步任务轮询助手**（`pollTask`）：覆盖 RUNNING/DONE/FAILED/resultEvicted/NOT_FOUND

**验收**：上传后 Worker 自动解析 → SSE 进度 0→100% → status=DONE → stats 有值；异步任务 4 状态（运行中/完成/失败/结果过期）UI 正确。

### P3：查询反代 + Agent 工具

- [ ] `app/routes/heapdump_proxy.py`：全部端点反代（含同步 + 异步）
- [ ] 明确区分 `oom-diagnosis`（同步）和 `diagnose-oom`（异步）
- [ ] `/object` 端点反代（Inspector 硬依赖）
- [ ] `react_agent/mat_tools.py`：mat_* 工具实现
- [ ] `react_agent/tools.py`：注册工具
- [ ] `react_agent/agent/tools_exec.py`：mat_ 前缀分发
- [ ] 出参瘦身 + 就绪校验

**验收**：Histogram/Dominator/Threads/OQL 返回正确数据；`/object?id=` 返回对象详情；Agent 可调 `mat_leak_suspects`。

### P4：前端渐进式面板

- [ ] 精简概览面板（模式 A）：Overview 卡片 + 快速诊断按钮 + OOM 横幅（`/oom-diagnosis`）
- [ ] 全功能分析器（模式 B）：三栏布局
- [ ] Histogram 表格 + 排序 + Inspector（数据源 `/object?id=`）
- [ ] Dominator Tree 懒加载
- [ ] Threads 三级树
- [ ] OQL 编辑器 + **结果集复用**（resultSetId → 喂 Histogram）
- [ ] 异步诊断视图（Leak Suspects / OOM 复合诊断）4 状态 UI
- [ ] 与 Agent 对话集成（选中对象 → 发消息 → Python 调工具）

**验收**：前端看到解析进度 → 点击 Histogram → 显示表格 → 点击行 → Inspector 显示详情（含字段/引用）→ 问 Agent 给出诊断；异步诊断视图能正确显示运行中/完成/失败/结果过期。

### P5：增强（可选延后）

- [ ] 秒传（sha256 匹配）
- [ ] 前端图表（histogram 可视化）
- [ ] 两 dump 对比（delta_histogram）
- [ ] V1 → V2 解析器切换（JSONL 精细进度）
- [ ] 大 hprof 压测与性能调优

---

## 11. 关键参考文件

| 用途 | 路径 |
|---|---|
| 总体对接设计 | `mat-study/07-与现有JVMind系统对接落地方案.md` |
| 前端落地设计（本文对齐的文档） | `14-heapdump前端落地设计-对齐真实端点.md` |
| Worker 调解析器 | `parser-cli/python-worker-snippet.py` |
| 文本进度映射器 | `parser-cli/v1-parsehd/mat_parse_runner.py` |
| query-service 端点 | `query-service/DEPLOY.md` |
| Agent 工具化思路 | `mat-study/04-MAT接口工具化-自然语言诊断Agent思路.md` |
| 前端原型 | `web-prototype/index.html` |
| 现有 gc 路由（复用范式） | `app/routes/gc_reports.py` |
| 现有 jstack 路由 | `app/routes/jstack_reports.py` |
| 现有 memory_db | `react_agent/memory_db.py` |

---

*文档版本: 1.1*  
*对应设计文档版本: mat-study/ 全系列 + 14-heapdump前端落地设计 (2026-06-29)*
