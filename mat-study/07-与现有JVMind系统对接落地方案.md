# MAT Heapdump 模块 与 现有 JVMind 系统对接落地方案

> 依据：用户提供的 `INTEGRATION_GUIDE_FOR_JAVA.md`（JVMind / ReAct Agent 接入对接文档）。
> 核心转变：**MAT 模块不是从零起的独立系统，而是接入一个已成熟运行的平台**——
> 它已有 FastAPI 后端、ReAct Agent（自写 loop）、多用户/会话/记忆、计费配额、org 团队、
> 以及**同构的 GC 日志分析 / jstack 分析**两个先例。
> 本文把前 6 份设计**对齐**到这个现有系统，识别复用点、冲突点、需补齐项。

---

## 1. 最重要的认知更新：MAT 模块要"长得像 GC/jstack 模块"

现有系统已经有两个**和 heapdump 高度同构**的分析模块：
- `gc_reports` 表 + `/api/gc-reports` 路由 + AI 诊断
- `jstack_reports` 表 + `/api/jstack-reports` 路由 + AI 诊断

它们的范式是：**上传文件 → 解析统计(stats JSON) → 触发 AI 诊断(ai_conclusion) → 报告持久化 → 可作为 report_context 喂给 ReAct Agent 对话**。

> **结论：MAT heapdump 模块应复刻这个范式**，新增一套 `heapdump_reports`（或 `mat_reports`）表 +
> `/api/heapdump-reports` 路由，让它无缝融入现有"上传-分析-AI诊断-对话追问"闭环。
> 这比我前几份文档里"独立 dumpId/parse_task 体系"更贴合现状——应**二者融合**（见 §3）。

但 heapdump 与 GC/jstack 有一个本质差异，必须特殊处理：
| | GC 日志 / jstack | Heapdump |
|---|---|---|
| 文件大小 | KB ~ 几十 MB | **GB ~ 几十 GB** |
| 解析耗时 | 秒级，同步即可 | **分钟级，必须异步队列** |
| 解析产物 | 一份 stats JSON | **一组 index 文件(GB) + 可交互查询** |
| 后续分析 | 主要看 AI 结论 | **大量交互式查询(histogram/支配树/线程树/OQL)** |
| 实现语言 | Python 内解析 | **独立 Java 服务(复用 MAT)** |

所以 heapdump 模块 = "GC/jstack 的报告范式" + "重型异步解析 + Java 交互查询服务"的结合体。

---

## 2. 接入方式选择：受信任的同库 Java 后端（方式1）

对接文档 §5 给了两条路。结合我们前几份设计（Java MAT 服务 + 共享 NFS + DB 队列），**选方式 1：作为受信任的同库后端**，但做**职责切分**：

```
┌────────────────────── 现有 JVMind (Python/FastAPI) ──────────────────┐
│  · 用户/会话/记忆/计费/org/鉴权(JWT+CSRF)  ← 全部沿用，不碰          │
│  · ReAct Agent loop                         ← 复用，新增 MAT 工具     │
│  · /api/heapdump-reports 路由(新增)         ← 上传(分块)/列表/进度/中断│
│  · 解析队列调度(复用 DB)                    ← 单消费者                │
└───────────┬──────────────────────────────────┬──────────────────────┘
            │ fork 子进程                       │ HTTP/JSON 代理
            ▼                                   ▼
   parser-cli.jar (独立 Java 进程)      Java MAT Query 服务(常驻)
   hprof → index (写 NFS)               读 NFS index, 提供四大视图查询
            └──────────── 共享 NFS ────────────┘
                         └──── 共享 DB ────┘
```

**职责划分原则**（关键）：
- **Python 侧**：所有"平台能力"（鉴权/用户/会话/配额/计费/Agent/上传入口/队列调度/报告元数据）——**完全复用现有体系**。
- **Java 侧**：只做"MAT 专业能力"（解析 + 四大视图交互查询）。**不碰用户/鉴权/计费**——避免复刻文档 §3 那套自实现 JWT/PBKDF2/enc:v1 加密（成本高、易错）。
- Java MAT Query 服务**不直接面向浏览器**，而是由 Python 做反向代理（带上已鉴权的用户上下文）。这样 Java 侧无需实现 §3 的鉴权，规避了对接文档 §8 点名的"服务间鉴权缺口"。

> 这一选择直接解决了对接文档最大的风险点（§8 鉴权缺口、自实现加密复刻）：
> **让 Python 继续当鉴权/计费的唯一权威，Java 只当被 Python 调用的无状态计算服务。**

---

## 3. 数据库设计：融入现有 schema（遵守 §6 契约）

新增表，**风格与 `gc_reports`/`jstack_reports` 完全对齐**（同样的 id/时间/外键约定）：

```sql
-- heapdump 报告(对齐 gc_reports/jstack_reports 结构 + 异步解析所需字段)
heapdump_reports(
  id            TEXT PK,         -- 应用层生成, 带前缀如 'hd_' + uuid4().hex(避免与现有前缀冲突)
  session_id    TEXT FK→sessions.id,
  org_id        TEXT FK→organizations.id NULL,
  user_id       TEXT FK→users.id,
  filename      TEXT,
  size          INT,
  file_id       TEXT NULL,       -- 关联 uploaded_files(若沿用)
  -- 异步解析状态机(heapdump 特有)
  status        TEXT,            -- QUEUED/PARSING/DONE/FAILED/CANCELLED
  progress      REAL,            -- 0~1
  phase         TEXT,            -- Pass1/Pass2/GC/DominatorTree
  dump_dir      TEXT,            -- NFS 上 dump 目录: /nfs/heapdumps/<id>/
  parse_args    TEXT,            -- JSON: keep_unreachable/discard...
  -- 解析完成后(对齐 gc/jstack 的 stats + ai_conclusion)
  stats         TEXT,            -- JSON: 对象数/类数/堆大小/GC root 数/JVM 信息(= dump_meta)
  ai_conclusion TEXT,            -- AI 诊断结论(Leak Suspects/连接池等)
  error         TEXT,
  -- 水平扩展(确认点5): 多 worker 安全 + 僵尸任务接管
  worker_id     TEXT,            -- 处理该任务的 worker
  heartbeat     TEXT,            -- worker 心跳, 超时则任务可被接管
  attempts      INT,             -- 重试次数
  created_at    TEXT,            -- 'yyyy-MM-dd HH:mm:ss' 本地时间(严格遵守 §6.3)
  queued_at TEXT, started_at TEXT, finished_at TEXT
)
-- 注: 不使用 uploaded_files 表(确认点2)，文件全在 NFS，DB 仅存 dump_dir。
--     id 前缀 hd_(确认点1)；解析配额复用 file_upload(确认点3)。
```

**严格遵守对接文档 §6 契约**：
1. **Schema 演进只由 Python 负责**（§6.1）：`heapdump_reports` 的建表/补列加进 Python 的 `init_db()`，**Java 侧只读写既有表，绝不跑 create_all/迁移**。
2. **id 生成**（§6.2）：用带前缀的 `uuid4().hex`，前缀选未占用的（如 `hd_`），由 **Python 生成**（Java 需要时也遵循同规则）。
3. **时间格式**（§6.3）：所有时间列 `yyyy-MM-dd HH:mm:ss` 本地时区；Java 侧（若写 progress）必须用同格式。
4. **配额计数**（§6.4）：heapdump 解析应计入 `file_upload_count`，AI 诊断计入 `llm_call_count`；团队会话计在 `organizations`。**配额检查/扣减由 Python 做**（它有现成逻辑 + 进程内锁 + 建议的 DB 行锁）。Java 不碰配额。
5. **外键完整性**（§6.6）：写报告前确保 session_id/user_id/org_id 存在——由 Python 保证。
6. **PostgreSQL**（§6.8）：双进程(Python+Java)共享，**必须用 PostgreSQL**，不能 SQLite。队列用 `SELECT ... FOR UPDATE SKIP LOCKED`（§6.4 已提到 PG 行锁）。

> 谁写 `heapdump_reports`？建议：**进度/状态由 Python worker 写**（它 fork 并监控 parser-cli 子进程）。
> Java MAT 服务**只读** `heapdump_reports`（拿 dump_dir 去 openSnapshot）+ 解析查询，**不写业务表**——
> 进一步降低双写冲突风险，契合 §6.4 的并发告诫。

---

## 4. HTTP 接口：新增 `/api/heapdump-reports`（Python 侧，对齐现有风格）

复用现有鉴权（Cookie+CSRF）、错误格式（`{detail}`）、SSE 进度。

```
# 上传(分块, heapdump 特有, 因文件大)
POST   /api/heapdump-reports/uploads               创建上传会话
PUT    /api/heapdump-reports/uploads/{uid}/chunks/{i}
POST   /api/heapdump-reports/uploads/{uid}/complete → 落 NFS + 建报告 + 入队 → {id}
GET    /api/heapdump-reports/uploads/{uid}/status   断点续传

# 报告(对齐 gc-reports 风格)
GET    /api/heapdump-reports?session_id=&org_id=    列表
GET    /api/heapdump-reports/{id}                   详情(status/progress/stats/ai_conclusion)
GET    /api/heapdump-reports/{id}/progress          SSE 进度流(解析中)
POST   /api/heapdump-reports/{id}/cancel            中断解析
DELETE /api/heapdump-reports/{id}                   删除(清 NFS + DB)
POST   /api/heapdump-reports/{id}/analyze           触发 AI 诊断(计 llm 配额)

# 交互查询(Python 反代到 Java MAT 服务, 解析完成后可用)
GET    /api/heapdump-reports/{id}/histogram?...     ┐
GET    /api/heapdump-reports/{id}/dominator?...     │ 透传到 Java
GET    /api/heapdump-reports/{id}/threads?...       │ (四大视图, 见文档06)
POST   /api/heapdump-reports/{id}/oql               ┘
```

**Python 反向代理层要点**：
- 鉴权后，校验该 report 属于当前 user/org（行级权限），再用 `httpx.AsyncClient` 转发到 Java MAT 服务内网地址。
- 转发时**带上 dump_dir / report_id**，Java 侧据此 openSnapshot。Java 服务**只信任来自 Python 内网的请求**（网络隔离 + 内网 token），不做用户级鉴权。
- 交互查询计入 `api_call_count` 配额（§6.4）。

---

## 5. ReAct Agent 集成：把 MAT 工具加入现有 loop（复用 §4.3）

现有系统已有成熟的：
- ReAct loop（`react_agent/agent.py`）
- `/api/chat/stream` SSE 对话
- `report_context` / `report_contexts` 机制：**对话时可挂最多 3 个报告作为上下文**（type=`gc|jstack`）

**集成动作**：
1. **扩展 report_context 的 type**：新增 `type:"heapdump"`，`{type:"heapdump", session_id, report_id}`。这样用户能在对话里挂一个 heapdump 报告追问。
2. **新增 MAT agent 工具**（文档 04 设计的工具集）注入现有 loop 的工具箱：
   - 原子：`mat_histogram` / `mat_dominator` / `mat_threads` / `mat_oql` / `mat_inspect` / `mat_paths2gc`
   - 复合诊断：`mat_diagnose_connection_pools` / `mat_diagnose_leak_suspects` ...
   - 工具实现 = 调 `/api/heapdump-reports/{id}/...`（即间接调 Java MAT 服务）。
3. **跨数据源联合诊断**（现有系统的独特优势）：agent 工具箱里同时有 gc / jstack / heapdump 三类数据。
   - 例：jstack 看到 50 个线程卡在 `getConnection` + heapdump 看到 HikariPool active=50/50 → **关联出连接池泄漏**。这是单一 MAT 给不了的价值，正是接入现有平台的最大红利。
4. **配额/单飞锁/内容安全**：全部复用现有 `/api/chat/stream` 的机制（§4.3），无需重造。

---

## 6. 与前 6 份文档的差异/修正清单

| 项 | 前文档设计 | 对齐现有系统后修正 |
|---|---|---|
| 上传/队列/进度 | 新建独立体系 | **复用** FastAPI + 共享 DB；新增 `/api/heapdump-reports`，风格对齐 gc/jstack |
| 任务表 | 独立 `parse_task`/`dump_meta` | **合并进** `heapdump_reports` 单表（含 status/progress/stats），由 Python 写 |
| 鉴权 | Java 服务自管 | **Java 不做鉴权**；Python 当唯一权威，反代时网络隔离 + 内网 token |
| 数据库 | "可用数据库" | **必须 PostgreSQL**（双进程共享，§6.8）；schema 只由 Python 迁移 |
| Agent | 新建工具 | **注入现有 ReAct loop**；扩展 report_context type=heapdump |
| 存储 | NFS | NFS 不变（✅ 已确认，支持随机 seek） |
| 解析 | 独立 jar 子进程 | 不变（✅），由 Python worker fork |
| 配额 | 未细化 | **接入现有配额体系**：解析计 file_upload，诊断计 llm，查询计 api_call |

---

## 7. 对接文档点名的风险点 → 我们的应对

| 对接文档 §8 风险 | 本方案应对 |
|---|---|
| 服务间鉴权缺口（无 Bearer/API-Key 中间件） | **绕过**：Java 不直面外部，由 Python 反代；Java 只认内网调用 |
| 自实现 JWT/PBKDF2/enc:v1 需逐字节复刻 | **避免**：Java 不碰鉴权/用户配置加密，全交给 Python |
| Schema 演进归属 | **明确**：只 Python 跑迁移，Java 只读写既有表 |
| SQLite 不能双进程共享 | **强制 PostgreSQL** |
| 时间无时区 | Java 侧若写库，统一 `yyyy-MM-dd HH:mm:ss` 本地时区 |

---

## 8. 修订后的里程碑（落地顺序）

1. **M1 · Python 报告骨架**：`heapdump_reports` 表(加进 init_db) + `/api/heapdump-reports` 分块上传 + 落 NFS + 入队(DB SKIP LOCKED) + worker fork parser-cli + SSE 进度/中断。复用现有鉴权/配额。
2. **M2 · Java MAT Query 服务**：嵌 Equinox + SnapshotManager(NFS openSnapshot + 三层软引用) + 内网鉴权 + `/overview` `/histogram`。Python 反代打通。
3. **M3 · 四大视图**：支配树/线程树/OQL 透传端点 + 前端"类 MAT 菜单"。
4. **M4 · AI 诊断 + 报告闭环**：`/analyze` 生成 ai_conclusion（对齐 gc/jstack）；report 可作 report_context。
5. **M5 · Agent 工具**：MAT 工具注入现有 ReAct loop；扩展 report_context type=heapdump；跨 gc/jstack/heapdump 联合诊断。
6. **M6 · 领域诊断**：连接池/线程池等 Inspector（文档 03），暴露为复合诊断工具。

---

## 9. 与现有项目团队对齐结果（已确认）

| # | 问题 | 确认结论 | 设计影响 |
|---|---|---|---|
| 1 | id 前缀 | **先用 `hd_`**，冲突再调整 | `heapdump_reports.id = "hd_" + uuid4().hex` |
| 2 | 文件存储 | **独立，直接用 `heapdump_reports.dump_dir`** | 不用 `uploaded_files` 表；hprof+index 全在 NFS，DB 只存 dump_dir 路径 |
| 3 | 配额口径 | **先复用 `file_upload`** | 解析计 file_upload_count；不新增配额项 |
| 4 | Java 服务鉴权 | **仅靠网络 ACL，全内网通讯** | Java MAT 服务无需任何 token 校验，靠网络隔离；Python 反代在内网调用 |
| 5 | 解析机 | **目前单台，但需支持水平扩展** | 队列设计必须为多 worker 安全（见 §10） |
| 6 | Agent 感知上下文 | **report_context 只传 report_id，agent 用工具按需读数据** | 不把大数据挂进对话上下文，根治 token 爆炸（见 §11） |

---

## 10. 解析机水平扩展设计（确认点 5）

> 当前单台解析机，但架构必须**从一开始就为水平扩展留好**——靠 DB 队列实现，零额外组件。

### 10.1 队列即 DB，天然支持多 worker
```sql
-- 每个解析 worker 的取任务操作(PostgreSQL)
BEGIN;
SELECT * FROM heapdump_reports
 WHERE status = 'QUEUED'
 ORDER BY queued_at
 LIMIT 1
 FOR UPDATE SKIP LOCKED;     -- ★ 多 worker 并发安全的核心: 互不抢同一行
-- 取到则:
UPDATE heapdump_reports SET status='PARSING', started_at=now, worker_id=:wid WHERE id=:id;
COMMIT;
```
- `FOR UPDATE SKIP LOCKED` 让 N 个 worker 并发取任务时**各取各的、互不阻塞、不重复**——这就是水平扩展的全部秘密，无需 MQ。
- 单台 = 跑 1 个 worker；扩展 = 多机各跑 1+ worker，连同一个 PostgreSQL 即可。

### 10.2 多 worker 需要补的字段与机制
```sql
-- heapdump_reports 增加:
worker_id   TEXT,        -- 哪个 worker 在处理(排障/接管用)
heartbeat   TEXT,        -- worker 心跳时间(yyyy-MM-dd HH:mm:ss)
attempts    INT,         -- 重试次数
```
- **僵尸任务接管**：worker 解析中定期更新 `heartbeat`；一个看门狗（或任意 worker）发现 `status=PARSING` 且 `heartbeat` 超时（如 >5 分钟）→ 判定该 worker 死亡 → 重置为 `QUEUED`（attempts+1，超上限则 FAILED）。**注意**：重置前清理该 dump_dir 下半成品 index（原子 rename 方案天然规避——半成品在 `.tmp/`，正式目录始终干净）。
- **NFS 共享是前提**：任一 worker 写的 index 都落同一 NFS，任一查询机都能读 → worker 和查询机解耦，可各自独立扩展。
- **顺序性放宽**：之前"单解析服务器顺序处理"在多 worker 下变为"并发处理不同 dump"，**单个 dump 仍只被一个 worker 处理**（SKIP LOCKED 保证），这正是想要的。

### 10.3 资源约束下的调度（单机时）
- 单 worker = 同一时刻只解析一个 dump → 内存峰值可控（解析最吃内存，文档 02/05）。
- 想在单机谨慎并发：限制并发 worker 数 = `min(机器内存 / 单 dump 解析峰值估算, CPU 核数)`。资源紧就保持 1。

---

## 11. Agent 上下文范式：只传 report_id，工具按需读取（确认点 6）★

> 这是比"把数据塞进上下文"**更优的设计**，确认点 6 的思路直接根治了文档 04 反复强调的 token 爆炸问题。

### 11.1 范式
```jsonc
// 对话请求里挂 heapdump 报告 —— 只给指针, 不给数据
{
  "session_id": "...",
  "message": "这个堆转储有没有连接池泄漏？",
  "report_contexts": [
    { "type": "heapdump", "session_id": "...", "report_id": "hd_xxx" }
    // 可同时挂 gc/jstack 报告做联合诊断
  ]
}
```
- 系统提示词告诉 agent：「你有一个 heapdump 报告 `hd_xxx`，可用 `mat_*` 工具按需查询它」。
- agent 自己决定调哪些工具、读多少数据、读到够了就停——**LLM 主导的惰性数据获取**。

### 11.2 为什么更好
| 对比 | 数据塞进上下文（旧想法） | 只传 report_id + 工具按需读（确认方案） |
|---|---|---|
| token 消耗 | histogram/支配树几千行直接撑爆 | agent 只取它需要的 Top-N/某子树 |
| 灵活性 | 预先决定塞什么，常常不对 | agent 按问题动态决定查什么 |
| 多报告 | 3 个报告数据叠加更爆 | 3 个 report_id 几乎零成本，按需查 |
| 出参瘦身 | 仍需要 | **仍需要**（工具单次返回也要瘦身，文档04 §3） |

### 11.3 对工具层的要求（强化文档 04）
1. **每个 mat 工具第一个参数就是 report_id**：`mat_histogram(report_id, groupBy, top)`、`mat_oql(report_id, q)` …
2. **工具内部**：report_id → 查 DB 拿 dump_dir → 调 Java MAT 服务（Python 反代，内网 ACL）→ 出参瘦身 → 返给 agent。
3. **复合诊断工具优先**：`mat_diagnose_connection_pools(report_id)` 一步给结论，减少 agent 轮数（资源有限时尤其重要）。
4. **跨数据源**：agent 工具箱同时有 `gc_*` / `jstack_*` / `mat_*`，且都只认 report_id → 天然支持「jstack 报告 X + heapdump 报告 Y 联合分析」。
5. **就绪校验**：工具调用前检查该 report `status=DONE`；若仍 PARSING，返回友好提示「解析中(进度 62%)，稍后再试」，agent 可据此回复用户。

---

## 12. 修订后的里程碑（落地顺序，已纳入确认项）

1. **M1 · Python 报告骨架**：`heapdump_reports` 表(加 init_db，含 worker_id/heartbeat/attempts) + `/api/heapdump-reports` 分块上传 + 落 NFS(dump_dir) + DB 队列(SKIP LOCKED) + worker fork parser-cli + 僵尸任务接管 + SSE 进度/中断。复用鉴权 + file_upload 配额。
2. **M2 · Java MAT Query 服务**：嵌 Equinox + SnapshotManager(NFS openSnapshot + 三层软引用)，**无鉴权(内网 ACL)** + `/overview` `/histogram`。Python 内网反代打通。
3. **M3 · 四大视图**：支配树/线程树/OQL 透传 + 前端类 MAT 菜单。
4. **M4 · AI 诊断闭环**：`/analyze` 生成 ai_conclusion(对齐 gc/jstack，计 llm 配额)。
5. **M5 · Agent 工具**：`mat_*` 工具(均以 report_id 为首参)注入现有 ReAct loop；扩展 report_context type=heapdump；就绪校验 + 跨 gc/jstack/heapdump 联合诊断。
6. **M6 · 领域诊断**：连接池/线程池 Inspector(文档03) → 复合诊断工具。
7. **M7 · 水平扩展验证**：多 worker / 多查询机压测；NFS 性能调优(文档06 §2.2)。
