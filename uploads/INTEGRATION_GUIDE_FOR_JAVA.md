# JVMind (ReAct Agent) — 接入对接文档

> 面向对象：使用 **Java** 开发的纯后端新模块团队。
> 接入方式：**HTTP / JSON** 调用本项目对外接口，并**共享同一套数据库**。
> 本文档描述本项目的整体架构、数据库结构、认证与加密机制、对外 HTTP 接口约定，以及共享数据库时需要遵守的契约与注意事项。

---

## 1. 项目概览

本项目（内部代号 JVMind / ReAct Agent）是一个 **Python 实现的智能体后端 + 自研前端**，核心能力：

- 基于 OpenAI 兼容接口的自实现 **ReAct 推理循环**（`react_agent/agent.py`）。
- **多用户 / 多会话**对话与长期记忆管理。
- **JDK GC 日志分析** 与 **jstack 线程转储分析**（含 AI 诊断）。
- **计费 / 套餐 / 配额**体系（Paddle 集成）。
- **组织 / 团队（org）**协作与席位管理。
- 用户级 **API Key 加密存储**、JWT 登录态、CSRF 防护。

技术栈：

| 层 | 技术 |
|----|------|
| Web 框架 | FastAPI + uvicorn（入口 `server.py`） |
| ORM | SQLAlchemy |
| 数据库 | SQLite（默认） / PostgreSQL（生产推荐） |
| 实时输出 | SSE（`sse-starlette`） |
| 前端 | 原生 ES Modules + Vite（与 Java 模块无关） |

> Java 模块作为纯后端，主要关注：**第 2 章数据库结构**、**第 3 章认证与加密**、**第 4 章 HTTP 接口**、**第 6 章共享数据库契约**。

---

## 2. 数据库结构（共享对象）

- 连接串由环境变量 `DATABASE_URL` 决定，默认 `sqlite:///./data/app.db`，生产支持
  `postgresql+psycopg://user:password@host:5432/dbname`。
- 建表与轻量迁移由本项目 `react_agent/db.py::init_db()` 在启动时执行：`create_all` + 自动补列 + 套餐种子数据。
- **所有时间字段为 `TEXT`**，格式统一为本地时间字符串 `"%Y-%m-%d %H:%M:%S"`（非 ISO8601、非 UTC、不带时区）。Java 侧读写务必遵循同一格式，避免比较/排序错乱。
- **主键策略**：大多数业务表主键为 `TEXT`（应用层生成的字符串 id，如 `uuid4().hex` 前缀），少数为自增整型（见下表）。

### 2.1 表清单与关键字段

ORM 定义见 `react_agent/models.py`。下面列出 Java 侧最可能读写的核心表。

#### `users` — 用户
| 列 | 类型 | 说明 |
|----|------|------|
| `id` | TEXT PK | 用户 id（应用生成） |
| `username` | TEXT UNIQUE | 用户名（邮箱注册时等于邮箱） |
| `password_hash` | TEXT | PBKDF2 哈希，格式见 §3.3 |
| `is_admin` | INT | 0/1 |
| `plan` | TEXT | 套餐 slug：`free` / `pro` / `team` 等 |
| `plan_id` | TEXT FK→plans.id | 套餐外键 |
| `email` | TEXT | |
| `oauth_provider` / `oauth_id` | TEXT | 第三方登录 |
| `file_upload_count` / `file_upload_month` | INT/TEXT | 文件配额计数（按月） |
| `llm_call_count` / `llm_call_month` | INT/TEXT | LLM 调用配额计数（按月） |
| `api_call_count` / `api_call_month` | INT/TEXT | API 调用配额计数（按月） |
| `last_llm_call_time` | TEXT | |
| `config` | TEXT(JSON) | 用户 LLM 配置，**含加密字段**，见 §3.4 |
| `created_at` | TEXT | |

#### `sessions` — 会话
| 列 | 类型 | 说明 |
|----|------|------|
| `id` | TEXT PK | |
| `user_id` | TEXT FK→users.id | |
| `org_id` | TEXT FK→organizations.id NULL | 团队会话归属 |
| `title` | TEXT | |
| `created_at` / `updated_at` | TEXT | |

#### `messages` — 会话消息
| 列 | 类型 | 说明 |
|----|------|------|
| `id` | INT PK 自增 | |
| `session_id` | TEXT FK→sessions.id | |
| `role` | TEXT | `user` / `assistant` / `system` 等 |
| `content` | TEXT | |
| `ts` | TEXT | |

#### `facts` — 会话长期记忆
| 列 | 类型 | 说明 |
|----|------|------|
| `id` | INT PK 自增 | |
| `session_id` | TEXT FK | |
| `content` | TEXT | |
| `created_at` | TEXT | |

#### `gc_reports` / `jstack_reports` — 分析报告
两表结构基本一致：
| 列 | 类型 | 说明 |
|----|------|------|
| `id` | TEXT PK | |
| `session_id` | TEXT FK | |
| `org_id` | TEXT FK NULL | |
| `filename` | TEXT | |
| `size` | INT | |
| `file_id` | TEXT NULL | 关联 `uploaded_files.file_id` |
| `stats` | TEXT(JSON) | 解析统计结果 |
| `ai_conclusion` | TEXT | AI 诊断结论 |
| `created_at` | TEXT | |

#### `uploaded_files` — 上传文件
| 列 | 类型 | 说明 |
|----|------|------|
| `file_id` | TEXT PK | |
| `user_id` | TEXT FK | |
| `content_type` | TEXT | |
| `content` | TEXT | 文件内容（可能 base64/文本） |
| `storage_backend` | TEXT | `db` 等 |
| `storage_key` | TEXT | 外部存储 key |
| `size` | INT | |
| `sha256` | TEXT | |
| `expires_at` | TEXT | 过期清理用 |
| `created_at` | TEXT | |

#### `organizations` / `org_members` / `org_invitations` — 团队
- `organizations`：`id`, `name`, `owner_id`, `seats_total`，以及**共享配额计数器**（`llm_call_count`/`file_upload_count`/`api_call_count` 及对应 `_month`）。团队套餐额度由成员共享。
- `org_members`：`id`(INT 自增), `org_id`, `user_id`, `role`（`owner`/`member`），唯一约束 `(org_id, user_id)`。
- `org_invitations`：邀请 token（存哈希），唯一约束 `(org_id, email)`。

#### `plans` / `user_subscriptions` / `billing_invoices` / `billing_webhook_events` — 计费
- `plans`：套餐定义，含 `slug`、各类 `*_limit` 配额、`features_json`、Paddle price id。
- `user_subscriptions`：用户订阅状态（`status`、周期、Paddle 订阅/客户 id）。
- `billing_invoices`：账单流水，`provider_transaction_id` 唯一。
- `billing_webhook_events`：Paddle webhook 幂等记录，`(event_id, provider)` 唯一。

#### `api_tokens` / `api_usage_logs` — API 令牌与用量
- `api_tokens`：`id`, `user_id`, `token_hash`（SHA-256），`name`, `last_used_at`, `created_at`。**仅存哈希，不存明文。**
- `api_usage_logs`：`id`(INT 自增), `user_id`, `api_token_id`, `endpoint`, `status`, `ip`, `timestamp`。

#### 其他
- `email_verification_codes`：邮箱验证码（哈希存储，含 `purpose`/`expires_at`/`attempts`）。
- `revoked_tokens`：JWT 黑名单（`jti` PK，`expires_at` 为 epoch 秒）。
- `skills`：用户自定义技能/指令。
- `system_settings`：键值系统配置（`key` PK）。
- `audit_logs`：审计日志（`id` INT 自增）。
- `blog_posts`：博客内容。

---

## 3. 认证与加密机制（务必与本项目保持一致）

Java 模块若要复用同一套登录态、读取用户密码/配置，必须**完全复刻**以下算法，否则无法互通。

### 3.1 登录态：自实现 HS256 JWT
本项目**未使用标准 JWT 库**，而是手写 HS256（实现见 `react_agent/user_manager_db.py`）：

- Header：`{"alg":"HS256","typ":"JWT"}`
- Payload（访问令牌）：
  ```json
  { "sub": "<user_id>", "jti": "<uuid hex>", "iat": <epoch秒>, "exp": <epoch秒> }
  ```
  刷新令牌额外带 `"typ":"refresh"`。
- 签名：`HMAC-SHA256(secret, base64url(header) + "." + base64url(payload))`。
- 编码：**base64url 去掉尾部 `=` 填充**（`base64.urlsafe_b64encode(...).rstrip("=")`）。
- 校验：重算签名 `hmac.compare_digest` 比对；再校验 `exp > now`；若带 `jti`，查 `revoked_tokens` 是否吊销。

> 与标准 JWT 兼容点：标准库可解析此 token；但 Java 侧用标准库**签发**时，需确保使用相同 secret、相同 base64url 无填充、payload 字段顺序无关（JSON 解析按 key），即可被本项目 `_verify_jwt` 接受。
>
> **关键**：secret 来自环境变量 `JWT_SECRET`（必须两边一致）。默认访问令牌 TTL = `JWT_ACCESS_TTL_SECONDS`（默认 7200 秒）。

### 3.2 登录态传递：Cookie + CSRF
本项目前端走 Cookie，Java 模块如果要调用本项目 HTTP 接口需注意：

- 登录成功后通过 `Set-Cookie` 下发：
  - `jwt`（HttpOnly）— 访问令牌。
  - `csrf_token`（非 HttpOnly）— CSRF 双提交令牌。
  - 可选 `refresh_jwt`（当 `JWT_REFRESH_TTL_SECONDS > 0`）。
- **所有非 GET/HEAD/OPTIONS 请求**（除豁免项）必须同时携带：
  - Cookie `jwt` + Cookie `csrf_token`；
  - Header `X-CSRF-Token`，且其值与 `csrf_token` cookie **完全相等**（`secrets.compare_digest`）。
- CSRF 豁免端点：`/api/auth/login`、`/api/auth/register`、`/api/webhooks/paddle`。
- CORS：`allow_origins` 由 `state._ALLOWED_ORIGINS` 控制，`allow_credentials=True`，允许 header 仅 `Content-Type`、`X-CSRF-Token`。Java 侧若跨域调用需被加入允许来源。

> 当前对外 HTTP 接口**以 Cookie + CSRF 为唯一鉴权方式**，不存在 `Authorization: Bearer` 或 `X-API-Key` 的服务端校验入口（`api_tokens` 表已建模但尚无校验中间件）。详见 §5 推荐接入方式。

### 3.3 密码哈希：PBKDF2-HMAC-SHA256
实现见 `react_agent/user_manager_db.py::_hash_password`：

- 算法：`PBKDF2-HMAC-SHA256`
- 迭代次数：`_HASH_ITERATIONS = 100_000`
- salt：`uuid4().hex[:16]`（16 位十六进制字符，作为字符串直接 UTF-8 编码当 salt）
- 存储格式：`"<salt>$<base64(dk)>"`（`$` 分隔，dk 为标准 base64）
- 校验：`_verify_password` 用同 salt 重算后 `hmac.compare_digest` 比对 base64 字符串。

> Java 复刻：`SecretKeyFactory("PBKDF2WithHmacSHA256")`，salt = `saltStr.getBytes(UTF_8)`，iterations=100000，keyLength = dkLen*8（dkLen 为默认 PBKDF2 输出，Python 默认输出长度=哈希长度 32 字节 → 256 位）。注意 salt 是**字符串字节**而非解码后的二进制。

### 3.4 用户配置/密钥加密：自定义 enc:v1
用户 LLM API Key 等敏感字段在 `users.config`（JSON）中**加密存储**，实现见 `react_agent/config.py`：

- 密文前缀：`enc:v1:`
- 主密钥来自环境变量 `CONFIG_ENCRYPTION_KEY`（两边必须一致）：
  - 若可 base64url 解码且 ≥32 字节，取前 32 字节；否则 `SHA-256(raw)` 作为 32 字节 key。
- 加密：随机 16 字节 nonce → 用 `HMAC-SHA256(key, nonce||counter)` 生成 keystream（CTR 风格）异或明文 → `tag = HMAC-SHA256(key, nonce||cipher)` → 输出 `enc:v1:` + base64url(`nonce(16) || tag(32) || cipher`)。
- 解密：校验 tag（支持 `CONFIG_ENCRYPTION_FALLBACK_KEYS` 多 key 轮换）。
- 敏感字段集合：`SECRET_FIELDS = {"openai_api_key", "free_tier_api_key"}`。

> Java 侧若需读取/写入用户 API Key，必须复刻此自定义流式加密（非标准 AES-GCM）。**建议优先避免直接读写该字段**，改由本项目接口处理。

---

## 4. 对外 HTTP 接口（REST + SSE）

Base URL：默认 `http://127.0.0.1:8000`，所有业务接口以 `/api` 前缀挂载。路由注册见 `server.py`。

> 通用约定：JSON 请求/响应；鉴权见 §3.2；错误以 `{"detail": "..."}` + 对应 HTTP status 返回。

### 4.1 认证 `/api/auth`
| Method | Path | 说明 | 备注 |
|--------|------|------|------|
| POST | `/api/auth/login` | 登录，下发 `jwt`/`csrf_token` cookie | CSRF 豁免；body `{username,password}` |
| POST | `/api/auth/register` | 注册（邮箱+密码，可带验证码） | CSRF 豁免 |
| GET | `/api/auth/me` | 当前用户信息 | 需登录 |
| POST | `/api/auth/logout` | 注销，吊销 token | |
| POST | `/api/auth/refresh` | 刷新令牌轮换 | 需 `refresh_jwt` cookie |
| GET | `/api/auth/check-email` | 邮箱是否已注册 | query `email` |
| POST | `/api/auth/send-email-code` | 发送注册验证码 | |
| POST | `/api/auth/*-password*` | 改密 / 重置密码 | |

登录/`/me` 返回的用户对象（`_user_response`）形如：
```json
{
  "id": "...", "username": "...", "is_admin": false, "created_at": "...",
  "email": "...", "plan": "free",
  "file_upload_count": 0, "file_upload_month": "",
  "llm_call_count": 0, "llm_call_month": "",
  "config": { /* 脱敏后的 LLM 配置，密钥不返回明文 */ },
  "org_id": "...", "org_role": "owner"   // 属于组织时附带
}
```

### 4.2 会话 `/api/sessions`
| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/sessions?org_id=&personal=` | 列出会话 |
| POST | `/api/sessions` | 创建会话 `{title?,org_id?}` → `{id}` |
| GET | `/api/sessions/{sid}` | 会话详情（含消息/记忆） |
| DELETE | `/api/sessions/{sid}` | 删除（团队会话需创建者/owner） |
| PATCH | `/api/sessions/{sid}` | 重命名 `{title}` |
| POST | `/api/sessions/{sid}/clear` | 清空消息 |
| GET/POST | `/api/sessions/{sid}/facts` | 列出/新增长期记忆 |
| DELETE | `/api/sessions/{sid}/facts/{index}` | 删除记忆 |

### 4.3 对话（SSE 流式）`/api/chat`
| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/chat/stream` | ReAct 流式对话，返回 `text/event-stream` |

请求体（`ChatReq`）：
```json
{
  "session_id": "...",
  "message": "用户输入",
  "lang": "zh|en (可选)",
  "report_context": { "type":"gc|jstack", "session_id":"...", "report_id":"..." },
  "report_contexts": [ /* 同上，最多 3 个 */ ]
}
```
SSE 事件流：`event:` 取值有 `message`（思考/回答增量）、`error`、`done` 等；`data` 为 JSON。
**限制**：会话级单飞锁（同会话并发返回 409）；频率限制、LLM 配额、内容安全过滤、输入长度上限（默认 100000 字符）。Java 侧消费需按 SSE 逐行解析。

### 4.4 GC / jstack 报告 `/api/gc-reports`、`/api/jstack-reports`
- 上传日志 → 解析统计 → 可触发 AI 诊断 → 报告持久化到对应表。
- 详细请求体见 `app/routes/gc_reports.py` / `jstack_reports.py` 与 `AnalyzeReq`/`SaveConclusionReq`。

### 4.5 其他
| 前缀 | 模块 | 说明 |
|------|------|------|
| `/api/config` | 用户 LLM 配置 | 设置 base_url/model/api_key（加密存储），连接测试 |
| `/api/orgs` | 组织/团队 | 创建、成员、邀请、席位 |
| `/api/plans` | 套餐列表 | 公开套餐信息 |
| `/api/billing` + `/api/webhooks/paddle` | 计费 | Paddle checkout/webhook（webhook CSRF 豁免，走签名校验） |
| `/api/skills` | 自定义技能 | |
| `/api/settings` | 公共/系统设置 | |
| `/api/blog` | 博客 | |
| `/api/admin/*` | 管理后台 | 需 `is_admin` |
| `/api/health` | 健康检查 | |

---

## 5. Java 模块推荐接入方式

当前服务端鉴权是 **Cookie(jwt) + 双提交 CSRF**，没有现成的服务间 token 校验中间件。两种可行路径：

1. **作为受信任的同库后端（推荐，最小耦合）**
   - Java 模块**只直连共享数据库**，不调用本项目 HTTP 接口，自行实现读写。
   - 必须复刻 §3 的密码哈希、JWT、配置加密算法，并共享 `JWT_SECRET` / `CONFIG_ENCRYPTION_KEY`。
   - 严格遵守 §6 的写入契约（id 生成、时间格式、配额计数、外键、迁移）。

2. **作为 HTTP 客户端调用本项目接口**
   - Java 侧先 `POST /api/auth/login` 拿到 `jwt` + `csrf_token` cookie。
   - 后续非 GET 请求回传两个 cookie，并把 `csrf_token` 值放进 `X-CSRF-Token` header。
   - 也可由 Java 侧用共享 `JWT_SECRET` 自签 HS256 token（payload 含 `sub/jti/iat/exp`，base64url 无填充），直接作为 `jwt` cookie 使用——本项目 `_verify_jwt` 即可校验。
   - 跨域时需将 Java 服务来源加入 `ALLOWED_ORIGINS`。

> 若需要标准的服务间 API（如 `Authorization: Bearer`/`X-API-Key` 直连校验），目前**服务端尚未实现校验入口**（`api_tokens` 表已就绪但无中间件）。这属于需要双方确认的接口缺口，建议作为对接前置项与本项目团队对齐。

---

## 6. 共享数据库契约与注意事项

为避免破坏现有系统，Java 模块写库时必须遵守：

1. **不要并发跑两套 `create_all`/迁移**：本项目启动会执行 `init_db()`（建表+补列+套餐种子）。两边同时迁移可能产生竞态；建议**仅由本项目负责 schema 演进**，Java 侧只读写既有表。SQLite 下尤其要避免多进程写入争用。
2. **id 生成**：TEXT 主键由应用层生成（多为带前缀的 `uuid4().hex`，如 `evc_`/`prc_` 等），Java 侧需保证全局唯一且不与既有前缀冲突。
3. **时间格式**：所有时间列写入 `yyyy-MM-dd HH:mm:ss`（本地时区，与本项目一致）。`revoked_tokens.expires_at` 与 JWT `iat/exp` 是 **epoch 秒（整型）**。
4. **配额计数**：`llm_call_count`/`file_upload_count`/`api_call_count` 与 `*_month` 字段配合按月重置。团队套餐配额计在 `organizations` 而非个人。Java 侧改动配额需理解此共享逻辑，避免与本项目并发更新冲突（本项目用进程内锁，跨进程不互斥——建议靠 DB 事务/行锁，PostgreSQL 下用 `SELECT ... FOR UPDATE`）。
5. **敏感字段**：`password_hash`、`users.config` 中的密钥、`api_tokens.token_hash`、验证码均为哈希/密文，**禁止写入明文**。
6. **外键完整性**：写 `sessions`/`messages`/`reports`/`org_members` 等需保证引用的 `user_id`/`session_id`/`org_id` 存在。SQLite 默认不强制外键，Java 侧需自行校验。
7. **JWT 黑名单**：注销/吊销会写 `revoked_tokens`。Java 自签 token 若要支持吊销，需写入相同表并带 `jti`。
8. **数据库引擎**：生产推荐 PostgreSQL（`postgresql+psycopg://...`）。SQLite 仅适合单进程/开发，**不建议** Java + Python 双进程共享同一 SQLite 文件做并发写。

---

## 7. 关键环境变量（两侧需对齐）

| 变量 | 用途 | 是否需两侧一致 |
|------|------|----------------|
| `DATABASE_URL` | 数据库连接串 | ✅ 必须指向同一库 |
| `JWT_SECRET` | JWT 签名密钥 | ✅ 共享登录态必须一致 |
| `CONFIG_ENCRYPTION_KEY` | 用户配置/密钥加密主密钥 | ✅ 若读写加密字段必须一致 |
| `CONFIG_ENCRYPTION_FALLBACK_KEYS` | 加密 key 轮换（逗号分隔） | 视轮换策略 |
| `JWT_ACCESS_TTL_SECONDS` | 访问令牌 TTL（默认 7200） | 建议一致 |
| `JWT_REFRESH_TTL_SECONDS` | 刷新令牌 TTL（0 关闭） | 建议一致 |
| `ALLOWED_ORIGINS` | CORS 允许来源 | Java 跨域调用需加入 |
| `COOKIE_SECURE` | HTTPS 下置 1 | 部署一致 |

---

## 8. 待对齐 / 风险点（建议对接前确认）

- **服务间鉴权缺口**：当前没有 `Bearer`/`X-API-Key` 服务端校验中间件，`api_tokens` 表未被使用于鉴权。若 Java 模块需要标准服务间认证，需要本项目侧补一个鉴权依赖/中间件。
- **Schema 演进归属**：建议明确「只有本项目执行迁移」，Java 侧只读写。否则需协商统一的迁移工具（如 Alembic / Flyway）。
- **SQLite vs PostgreSQL**：跨进程共享务必切到 PostgreSQL。
- **加密/哈希为自实现**：非标准库实现，Java 复刻时需逐字节对齐（base64url 无填充、salt 为字符串字节、CTR-HMAC keystream）。
- **时间无时区**：跨服务统一时区，避免 `created_at`/配额月份判断出错。
