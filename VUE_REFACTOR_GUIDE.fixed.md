# JVMind 前端 Vue 3 + TypeScript 重构指南（修订版）

> **目标**：在现有 Vanilla JS 前端并行运行期间，逐步迁移到 Vue 3 (Composition API) + TypeScript 架构，实现零停机、按页面灰度切换。

---

## 修订摘要（本版相对初稿的改动）

本版在保留原结构和战略的前提下，修正了 4 个会真正导致 bug/安全问题的硬伤，并统一了灰度策略、标注了推测性内容。**带 `> ⚠️ 修订` 标记的段落即改动点。**

| # | 问题 | 严重度 | 修正 |
|---|------|--------|------|
| 1 | 技术栈列了 `useEventSource`，但聊天是 POST+body 的 SSE，`useEventSource`/原生 `EventSource` **只支持 GET、不能带 body** | 🔴 用错 API | 移除 `useEventSource`；明确用 `fetch(POST)`+手动读流 |
| 2 | `useStreamChat` 没有 `AbortController`，与风险表"切会话防错位"自相矛盾 | 🔴 会出 bug | composable 内置 `AbortController`，切会话即 `abort()` |
| 3 | `MessageBubble` 直接 `v-html` 渲染 LLM Markdown，全文未提 DOMPurify | 🔴 存储型 XSS | 强制 `marked → DOMPurify → v-html`，给出 `renderedFinal` 正确实现 |
| 4 | Pinia store 顶层直接用 `orgStore/chatStore`（未 import、pinia 未激活） | 🔴 运行时报错 | 改为 **action 内** `useXxxStore()`，并提示循环依赖 |
| 5 | `useDark` 配置 + theme storageKey 不一致，灰度期新旧前端主题打架 | 🟠 体验 bug | 用显式 `data-theme` 切换，统一 storageKey；不依赖 `useDark` 隐式行为 |
| 6 | 灰度粒度（整体 env / `/v2` 路由 / 按路由 if）散落各处、SPA 深链无 fallback | 🟠 易困惑 | 统一为"Phase 0-3 走 `/v2/*` 并行，Phase 4 才整体切"；补 SPA catch-all |
| 7 | 类型"手翻 OpenAPI"与风险表 zod 矛盾；vue-i18n `missing` 吞警告与 ESLint 拦截矛盾 | 🟡 不一致 | 统一为 openapi-typescript 自动生成 + 关键路径 zod；明确 `missing` 取舍 |

### ⚠️ 待核实假设（初稿当成事实，实为推测，执行前务必对照真实代码）
- "**state.js 有 29 个字段**"、api.js/shared.js/messages.js 约 200 行等**具体数字与形态**——以仓库实际为准。
- **`app/frontend.py` 的现状与改法**、报告页路由 `/report/{sid}/{rid}` 的现有实现——本文给的是建议骨架，需核对真实代码。
- **现有前端存的 theme / lang 的 localStorage key 与取值**（dark/light/空？）——决定灰度期能否共享，必须先查清（见 §4.7 修订）。

---

## 一、现状诊断

### 1.1 当前架构

| 维度 | 现状 |
|------|------|
| **构建工具** | Vite 8，多入口 MPA (`landing`, `pricing`, `blog`, `docs`, `index(app)`, `report`, `admin`) |
| **框架** | 无框架，Vanilla JS ES Modules |
| **状态管理** | 全局 `state.js` 对象，模块通过 `app.js` 空对象挂载方法 |
| **DOM 操作** | 原生 `document.getElementById` + `innerHTML` 手动渲染 |
| **样式** | CSS 变量主题系统（dark/light），10+ CSS 文件通过 `@import` 聚合 |
| **i18n** | 自建 `t()` 函数，JSON 字典文件，事件派发 `langchange` |
| **API** | `fetch` 封装，CSRF Token 自动注入，401 统一跳转 |
| **流式通信** | SSE (`/api/chat/stream`)，手动 `ReadableStream` 解析 |
| **外部依赖** | ECharts (CDN)、Marked (CDN)、DOMPurify (CDN)、Paddle.js (CDN) |

### 1.2 核心痛点

1. **状态分散**：`state.js` + `app.js` 空对象 + 模块级闭包变量，数据流不清晰
2. **DOM 维护成本高**：`innerHTML` 拼接 + 事件委托，GC/JStack 分析面板逻辑尤其复杂
3. **无类型安全**：运行时错误多（如 `state.currentUser` 空值访问）
4. **组件复用困难**：无组件化，Landing 和 App 的 nav/footer 各自维护
5. **测试覆盖低**：仅 vitest + jsdom，DOM 操作难以单元测试

---

## 二、新架构设计

### 2.1 技术栈

```
Vue 3.4+          (Composition API + <script setup>)
TypeScript 5.x    (strict mode)
Vue Router 4      (App / Admin 各自独立 SPA)
Pinia 2.x         (全局状态管理，替代 state.js)
vue-i18n 9.x      (替代自建 i18n)
VueUse            (常用 composables: useLocalStorage, useScriptTag 等)
Vite 6.x          (保持构建工具一致)
```

> ⚠️ 修订（#1）：**不要用 VueUse 的 `useEventSource`**。它封装的是原生 `EventSource`，而原生 `EventSource` **只支持 GET、无法发送请求体（body）**（SSE 标准不提供 method/body 参数）。本项目聊天是 `POST /api/chat/stream` 带 JSON body，必须用 `fetch(POST)` + 手动读 `ReadableStream`（见 §4.3）。同理 `useDark` 也不直接用（见 §4.7 修订）。

> **样式策略**：保留现有 CSS 变量体系（`base.css`），新组件使用 `<style scoped>` + CSS 变量，**不引入 Tailwind/UnoCSS**（避免双体系并行增加迁移复杂度）。

### 2.2 部署模式：「新前端独立目录 + 后端路由灰度」

```
repo-root/
├── frontend/           ← 现有前端（冻结维护，只修关键 bug）
├── frontend-vue/       ← 新 Vue 前端（本方案产出）
│   ├── apps/
│   │   ├── app/        ← /app  核心应用 SPA
│   │   ├── admin/      ← /admin 管理后台 SPA
│   │   └── marketing/  ← landing/pricing/blog/docs 多页（或 SSR）
│   └── packages/
│       ├── shared/     ← 跨应用共享（API、类型、组件、工具、i18n）
│       └── ui/         ← 通用 UI 组件库（Button, Modal, Input 等）
```

**后端 `app/frontend.py` 修改策略**：

```python
# 新增环境变量控制灰度
_USE_VUE_FRONTEND = os.environ.get("USE_VUE_FRONTEND", "0") == "1"

_fe = _base / ("frontend-vue/dist" if _USE_VUE_FRONTEND else "frontend/dist")
```

> ⚠️ 修订（#6）：**统一灰度策略，避免三种粒度散落各处让人困惑**。本版规定：
> - **Phase 0–3（开发/灰度期）**：新前端挂在**独立前缀 `/v2/*`**（`/v2/app`、`/v2/admin`、`/v2/`…），与旧前端**并行共存**，互不影响。团队和测试用 `/v2/*` 验证。
> - **Phase 4（切流）**：才用 `USE_VUE_FRONTEND` 环境变量把 `/app`、`/admin`、`/` 等**主路径整体指向新前端**，出问题秒级回滚（改回 `0`）。
> - **不要**在过渡期对单个业务路由写零散的 `if _USE_VUE_FRONTEND` 分支（难维护、易漏）；要么 `/v2` 整组并行，要么整体切。
>
> 下面的 `USE_VUE_FRONTEND` 写法用于 **Phase 4 的整体切换**；Phase 0–3 的 `/v2/*` 挂载见 §6 修订。

---

## 三、目录结构（完整版）

```
frontend-vue/
├── package.json
├── tsconfig.json
├── vite.config.ts
├── env.d.ts
│
├── apps/
│   ├── marketing/                    # 营销站点（MPA 或 SSR）
│   │   ├── index.html                # landing 入口
│   │   ├── pricing.html
│   │   ├── blog.html
│   │   ├── docs.html
│   │   ├── src/
│   │   │   ├── pages/
│   │   │   │   ├── Landing.vue
│   │   │   │   ├── Pricing.vue       # Paddle checkout 集成
│   │   │   │   ├── Blog.vue
│   │   │   │   └── Docs.vue
│   │   │   ├── components/
│   │   │   │   ├── MarketingNav.vue  # 共享顶部导航
│   │   │   │   ├── MarketingFooter.vue
│   │   │   │   └── PricingCard.vue
│   │   │   └── main.ts               # 各页面独立 entry
│   │   └── vite.config.ts            # 多入口配置
│   │
│   ├── app/                          # 核心应用 SPA (/app)
│   │   ├── index.html
│   │   ├── src/
│   │   │   ├── main.ts
│   │   │   ├── App.vue               # 根布局（sidebar + main + gc-panel）
│   │   │   ├── router/
│   │   │   │   └── index.ts          # 路由：chat, report/:type/:sid/:rid, settings
│   │   │   ├── stores/
│   │   │   │   ├── auth.ts           # 认证状态（替代 auth.js + state.currentUser）
│   │   │   │   ├── session.ts        # 会话列表、当前会话
│   │   │   │   ├── chat.ts           # 消息、流式状态
│   │   │   │   ├── gc.ts             # GC 分析面板状态
│   │   │   │   ├── jstack.ts         # JStack 分析面板状态
│   │   │   │   ├── billing.ts        # 账单中心（替代 billing.js 闭包变量）
│   │   │   │   └── org.ts            # 团队/组织信息
│   │   │   ├── composables/
│   │   │   │   ├── useAuth.ts        # 登录/注册/重置密码逻辑
│   │   │   │   ├── useStreamChat.ts  # SSE 流式聊天（替代 messages.js sendMessage）
│   │   │   │   ├── useGCAnalysis.ts  # GC 上传、解析、图表
│   │   │   │   ├── useJstackAnalysis.ts
│   │   │   │   ├── useSessionPolling.ts  # 团队会话更新轮询
│   │   │   │   ├── usePaddle.ts      # Paddle checkout 封装
│   │   │   │   └── useTheme.ts       # dark/light 切换
│   │   │   ├── views/
│   │   │   │   ├── ChatView.vue      # 主聊天界面
│   │   │   │   ├── ReportView.vue    # 独立报告页（兼容 /report/:sid/:rid）
│   │   │   │   └── SettingsView.vue  # 账户设置、配置对话框
│   │   │   └── components/
│   │   │       ├── layout/
│   │   │       │   ├── AppSidebar.vue
│   │   │       │   ├── AppTopbar.vue
│   │   │       │   ├── GCAnalysisPanel.vue    # 右侧分析面板
│   │   │       │   └── TeamPanel.vue
│   │   │       ├── chat/
│   │   │       │   ├── ChatArea.vue
│   │   │       │   ├── ChatInput.vue
│   │   │       │   ├── MessageBubble.vue
│   │   │       │   ├── ThinkingSection.vue    # ReAct 思考折叠区
│   │   │       │   ├── TracePanel.vue         # 步骤轨迹
│   │   │       │   └── MsgActions.vue         # 复制/反馈/保存
│   │   │       ├── gc/
│   │   │       │   ├── GCUploadZone.vue
│   │   │       │   ├── GCReportTabs.vue
│   │   │       │   ├── GCReportView.vue
│   │   │       │   ├── GCHistoryList.vue
│   │   │       │   └── GCChartPanel.vue       # ECharts 封装
│   │   │       ├── jstack/
│   │   │       │   └── ... (镜像 gc/ 结构)
│   │   │       ├── modals/
│   │   │       │   ├── ConfigModal.vue        # 模型配置（替代 config-dialog.js）
│   │   │       │   ├── BillingModal.vue       # 账单中心（替代 billing.js）
│   │   │       │   ├── AccountSettingsModal.vue
│   │   │       │   └── ResetPasswordModal.vue
│   │   │       └── shared/
│   │   │           ├── SessionList.vue
│   │   │           ├── FactsPanel.vue
│   │   │           ├── QuotaBar.vue
│   │   │           └── HealthBanner.vue
│   │   └── vite.config.ts
│   │
│   └── admin/                        # 管理后台 SPA (/admin)
│       ├── index.html
│       ├── src/
│       │   ├── main.ts
│       │   ├── App.vue
│       │   ├── router/
│       │   │   └── index.ts          # /dashboard, /users, /plans, /billing, /audit...
│       │   ├── stores/
│       │   │   └── admin.ts
│       │   ├── views/
│       │   │   ├── DashboardView.vue
│       │   │   ├── UsersView.vue
│       │   │   ├── PlansView.vue
│       │   │   ├── BillingView.vue
│       │   │   ├── TeamsView.vue
│       │   │   ├── SkillsView.vue
│       │   │   ├── AuditView.vue
│       │   │   ├── FeedbackView.vue
│       │   │   ├── ConfigView.vue
│       │   │   └── BlogView.vue
│       │   └── components/
│       │       ├── AdminNav.vue
│       │       ├── UserDetailPane.vue
│       │       ├── AuditFilterBar.vue
│       │       └── BlogEditor.vue
│       └── vite.config.ts
│
├── packages/
│   ├── shared/                       # 跨应用共享包
│   │   ├── src/
│   │   │   ├── api/
│   │   │   │   ├── client.ts         # 带类型的 fetch 封装（替代 api.js）
│   │   │   │   ├── types/
│   │   │   │   │   ├── auth.ts       # User, LoginPayload, RegisterPayload...
│   │   │   │   │   ├── session.ts    # Session, Message, Fact...
│   │   │   │   │   ├── gc.ts         # GCReport, GCStats...
│   │   │   │   │   ├── jstack.ts     # JstackReport, ThreadStats...
│   │   │   │   │   ├── billing.ts    # Plan, Subscription, Invoice...
│   │   │   │   │   └── org.ts        # Organization, Member...
│   │   │   │   └── endpoints/
│   │   │   │       ├── auth.ts       # api.auth.me(), api.auth.login()...
│   │   │   │       ├── sessions.ts
│   │   │   │       ├── gc.ts
│   │   │   │       ├── billing.ts
│   │   │   │       └── admin.ts
│   │   │   ├── i18n/
│   │   │   │   ├── index.ts          # vue-i18n 初始化
│   │   │   │   ├── zh.json           # 直接复用现有翻译文件
│   │   │   │   └── en.json
│   │   │   ├── utils/
│   │   │   │   ├── csrf.ts           # getCookie, csrfHeaders
│   │   │   │   ├── format.ts         # fmtDate, money, formatN, parseServerTime...
│   │   │   │   ├── html.ts           # escapeHtml
│   │   │   │   ├── sse.ts            # parseSSE
│   │   │   │   └── gc.ts             # calculateGCHealth, calculateGCStatsClasses...
│   │   │   └── constants.ts          # MAX_FILE_SIZE, POLL_INTERVAL 等
│   │   └── package.json
│   │
│   └── ui/                           # 通用 UI 组件（可选，初期可合并到 shared）
│       ├── src/
│       │   ├── components/
│       │   │   ├── BaseButton.vue
│       │   │   ├── BaseModal.vue
│       │   │   ├── BaseInput.vue
│       │   │   ├── BaseSelect.vue
│       │   │   └── BaseTable.vue
│       │   └── styles/
│       │       └── variables.css      # 复用现有 CSS 变量
│       └── package.json
│
└── public/
    └── image/                          # favicon, logo 等静态资源
```

---

## 四、关键模块映射指南

### 4.1 状态管理：全局 `state.js` → Pinia Stores

**现有状态（state.js）**：

```ts
// 旧：frontend/src/state.js
export const state = {
  currentSessionId: null,
  sessions: [],
  isStreaming: false,
  currentUser: null,
  currentReport: null,
  // ... 约 30 个字段（⚠️ 实际数量/字段名以仓库 state.js 为准，迁移前先导出一份完整清单）
};
```

**新设计**：按领域拆分为多个 Pinia Store，避免单个巨型 store。

```ts
// apps/app/src/stores/auth.ts
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@jvmind/shared/api/types'

export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<User | null>(null)
  const authMode = ref<'login' | 'register'>('login')
  const isAuthenticated = computed(() => !!currentUser.value)

  async function checkAuth() {
    const user = await api.auth.me()
    currentUser.value = user
    return user
  }

  async function login(email: string, password: string) {
    const { user } = await api.auth.login({ username: email, password })
    currentUser.value = user
  }

  function logout() {
    currentUser.value = null
  }

  return { currentUser, authMode, isAuthenticated, checkAuth, login, logout }
})
```

```ts
// apps/app/src/stores/session.ts
export const useSessionStore = defineStore('session', () => {
  const sessions = ref<Session[]>([])
  const currentSessionId = ref<string | null>(null)
  const currentSession = computed(() =>
    sessions.value.find(s => s.id === currentSessionId.value) ?? null
  )
  const sessionTab = ref<'personal' | 'org'>('personal')

  async function loadSessions() {
    const orgStore = useOrgStore()        // ⚠️ 修订(#4)：在 action 内取 store，不能在模块顶层
    const url = sessionTab.value === 'org' && orgStore.currentOrg
      ? `/api/orgs/${orgStore.currentOrg.id}/sessions`
      : '/api/sessions?personal=true'
    const res = await api.sessions.list(url)
    sessions.value = res.sessions
  }

  async function selectSession(id: string) {
    const chatStore = useChatStore()      // 同上：action 内调用，此时 pinia 已激活
    const factStore = useFactStore()
    // 切会话防错位：策略二选一（与 §4.3 修订一致）——
    //   A) 流式中直接拦截不切：  if (chatStore.isStreaming) return
    //   B) 主动中断旧流再切（推荐，体验更顺）：
    chatStore.abort()
    currentSessionId.value = id
    const data = await api.sessions.get(id)
    chatStore.setMessages(data.messages)
    factStore.setFacts(data.facts)
    // ... 重置报告面板
  }

  return { sessions, currentSessionId, currentSession, sessionTab, loadSessions, selectSession }
})
```

> ⚠️ 修订（#4）：**Pinia store 之间互相调用，必须在 action（函数）内部 `useXxxStore()`**，不能在 store 的 setup 顶层或模块作用域直接拿另一个 store 的实例——那时 `getActivePinia()` 可能还没就绪，会抛 *"getActivePinia was called with no active Pinia"*，且容易形成模块循环引用。初稿示例里直接写 `orgStore.currentOrg` / `chatStore.isStreaming` 是错的，照抄会运行时报错。

**迁移原则**：
- `ref` 替代原 `state.xxx` 字段
- `computed` 替代派生状态（如 `canDelete` 逻辑）
- Store 间允许通过 `import` 引用（如 `sessionStore` 调用 `chatStore`），保持与旧代码相似的协作模式

### 4.2 API 层：`api.js` → 带类型的 API Client

**现有模式**：

```js
// 旧：frontend/src/api.js
export async function api(path, opts = {}) {
  const res = await fetch(API + path, {
    credentials: "same-origin",
    ...opts,
    headers: csrfHeaders({ "Content-Type": "application/json", ...(opts.headers || {}) }),
  });
  if (res.status === 401) { /* ... */ }
  if (!res.ok) { /* ... */ }
  return res.json();
}
```

**新设计**：分层封装，请求/响应全类型化。

```ts
// packages/shared/src/api/client.ts
import { csrfHeaders } from '../utils/csrf'

const BASE = ''

class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

async function request<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, {
    credentials: 'same-origin',
    ...opts,
    headers: csrfHeaders({
      'Content-Type': 'application/json',
      ...(opts.headers || {}),
    }),
  })

  if (res.status === 401) {
    authEvents.emit('unauthorized')
    throw new ApiError(401, 'Session expired')
  }

  if (!res.ok) {
    const text = await res.text()
    let detail = text
    try {
      const json = JSON.parse(text)
      detail = json.detail || json.error || json.message || text
    } catch { /* ignore */ }
    throw new ApiError(res.status, i18nText(detail))
  }

  return res.json() as Promise<T>
}

// 按领域组织端点
export const api = {
  auth: {
    me: () => request<User>('/api/auth/me'),
    login: (payload: LoginPayload) => request<{ user: User }>('/api/auth/login', { method: 'POST', body: JSON.stringify(payload) }),
    // ...
  },
  sessions: {
    list: (url: string) => request<{ sessions: Session[] }>(url),
    get: (id: string) => request<SessionDetail>(`/api/sessions/${id}`),
    // ...
  },
  chat: {
    // ⚠️ 修订(#2)：第二个参数接收 AbortSignal，透传给 fetch 以支持取消
    stream: (payload: ChatStreamPayload, signal?: AbortSignal) => {
      return fetch('/api/chat/stream', {
        method: 'POST',
        credentials: 'same-origin',
        headers: csrfHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(payload),
        signal,
      })
    }
  },
  // ... billing, gc, jstack, admin
}
```

**全局 401 处理**：使用 `mitt` 或 Vue 的 provide/inject 建立事件总线，在 `router.beforeEach` 或 `App.vue` 中统一监听 `unauthorized` 事件并跳转登录。

### 4.3 SSE 流式聊天：`messages.js` → `useStreamChat` Composable

**现有核心逻辑**（约 200 行）：手动 `fetch` + `ReadableStream` + `TextDecoder` + SSE 解析 + 分 phase 更新 DOM。

**新设计**：封装为可复用的 Vue composable。

```ts
// apps/app/src/composables/useStreamChat.ts
import { ref, computed } from 'vue'
import { parseSSE } from '@jvmind/shared/utils'
import { api } from '@jvmind/shared/api'
import { useSessionStore } from '../stores/session'

export type StreamPhase = 'idle' | 'reasoning' | 'final' | 'done' | 'error'

export function useStreamChat() {
  const sessionStore = useSessionStore()

  const isStreaming = ref(false)
  const phase = ref<StreamPhase>('idle')
  const thinkingContent = ref('')
  const finalContent = ref('')
  const steps = ref<Step[]>([])
  const error = ref('')
  const messageId = ref<number | null>(null)

  const thinkingExpanded = ref(false)

  // ⚠️ 修订(#2)：用 AbortController 支持取消，切会话/卸载时中断流，防止消息错位
  let controller: AbortController | null = null

  /** 中断当前流（切会话、组件卸载、用户主动停止时调用）。 */
  function abort() {
    controller?.abort()
    controller = null
    isStreaming.value = false
  }

  async function sendMessage(text: string, contexts: ReportContext[] = []) {
    if (isStreaming.value) return
    if (!sessionStore.currentSessionId) {
      await sessionStore.createSession()
    }

    abort()                      // 兜底：确保没有上一个流仍在跑
    controller = new AbortController()

    isStreaming.value = true
    phase.value = 'reasoning'
    thinkingContent.value = ''
    finalContent.value = ''
    steps.value = []
    error.value = ''

    try {
      // 注意：这是 POST + body 的 SSE，必须用 fetch 手动读流（不能用 EventSource/useEventSource）
      const res = await api.chat.stream({
        session_id: sessionStore.currentSessionId!,
        message: text,
        lang: i18n.global.locale.value,
        report_contexts: contexts,
      }, controller.signal)      // ⚠️ 把 signal 传给 fetch（见 §4.2 修订的 api.chat.stream 签名）

      if (!res.ok) {
        const text = await res.text()
        throw new Error(parseError(text))
      }

      const reader = res.body!.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // SSE 解析（与现有 parseSSE 一致）
        let match: RegExpExecArray | null
        while ((match = /\r?\n\r?\n/.exec(buffer))) {
          const idx = match.index
          const raw = buffer.slice(0, idx)
          buffer = buffer.slice(idx + match[0].length)
          const ev = parseSSE(raw)
          if (ev) handleEvent(ev.data)
        }
      }

      // 处理剩余 buffer
      const remaining = decoder.decode()
      if (remaining.trim()) {
        const ev = parseSSE(remaining)
        if (ev) handleEvent(ev.data)
      }
    } catch (e: any) {
      if (e?.name === 'AbortError') {
        // 主动中断（切会话/卸载），不当作错误，静默收尾
      } else {
        phase.value = 'error'
        error.value = e.message
      }
    } finally {
      controller = null
      isStreaming.value = false
      sessionStore.loadSessions()
      sessionStore.refreshBaseline()
    }

    function handleEvent(data: StreamEventData) {
      switch (data.type) {
        case 'token':
          if (data.phase === 'reason') {
            thinkingContent.value += data.content
          } else if (data.phase === 'final') {
            phase.value = 'final'
            finalContent.value += data.content
          }
          break
        case 'step':
          steps.value.push(data.step)
          break
        case 'final':
          phase.value = 'final'
          if (data.content !== finalContent.value) {
            finalContent.value = data.content
          }
          if (data.message_id != null) messageId.value = data.message_id
          break
        case 'fact_added':
          // 触发 facts 刷新
          break
        case 'error':
          phase.value = 'error'
          error.value = data.content || ''
          break
        case 'done':
          phase.value = 'done'
          if (data.message_id != null) messageId.value = data.message_id
          break
      }
    }
  }

  return {
    isStreaming, phase,
    thinkingContent, finalContent, steps, error, messageId,
    thinkingExpanded,
    sendMessage, abort,         // ⚠️ 导出 abort，供切会话/卸载调用
  }
}
```

> ⚠️ 修订（#1+#2）：
> - **聊天用 `fetch(POST)` 手动读流，不用 `EventSource`/`useEventSource`**（后者只支持 GET，无法带 body）。
> - composable 内置 `AbortController`：`sendMessage` 前 `abort()` 上一个、`fetch` 传 `signal`、捕获 `AbortError` 不当错误。
> - **切会话与组件卸载必须调 `abort()`**：在 `sessionStore.selectSession` 里先 `chatStore.abort()`（或在 ChatView `onUnmounted(abort)`），否则用户快速切会话会出现"上个会话的流写到新会话气泡"的错位——这正是初稿风险表点名要防、但代码没实现的问题。

**组件层使用**：

```vue
<!-- MessageBubble.vue -->
<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@jvmind/shared/utils/html'

const props = defineProps<{
  thinkingContent: string
  finalContent: string
  steps: Step[]
  messageId: number | null
}>()

// ⚠️ 修订(#3)：v-html 之前必须经 marked + DOMPurify，绝不能直出 LLM 文本
const renderedFinal = computed(() => renderMarkdown(props.finalContent))
const thinkingExpanded = ref(false)
</script>

<template>
  <div class="msg assistant">
    <div class="bubble">
      <ThinkingSection
        v-if="thinkingContent"
        v-model:expanded="thinkingExpanded"
        :content="thinkingContent"
      />
      <!-- renderedFinal 已是消毒后的 HTML -->
      <div class="final-content" v-html="renderedFinal" />
      <TracePanel :steps="steps" />
    </div>
    <MsgActions :message-id="messageId" :content="finalContent" />
  </div>
</template>
```

`renderMarkdown` 放在 shared，统一消毒（marked 在前，DOMPurify 在后）：

```ts
// packages/shared/src/utils/html.ts
import { marked } from 'marked'           // 或保持 CDN: window.marked
import DOMPurify from 'dompurify'         // 或保持 CDN: window.DOMPurify

export function renderMarkdown(src: string): string {
  const raw = marked.parse(src ?? '', { async: false }) as string
  return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } })
}
```

> ⚠️ 修订（#3，安全！）：初稿 `MessageBubble` 直接 `v-html="renderedFinal"`，但全文没说 `renderedFinal` 怎么来——若直接把 LLM 输出塞进 `v-html`，就是**存储型 XSS**（LLM 可被诱导输出 `<img onerror>`/`<script>` 等）。**任何 `v-html` 渲染的 Markdown/富文本必须先过 DOMPurify**。旧架构清单里本就有 DOMPurify(CDN)，迁移时务必接回来，别在组件化过程中丢掉。ThinkingSection 若也用 v-html，同样要消毒。

### 4.4 认证系统：`auth.js` → `useAuth` Composable + Auth Store

旧代码特点：
- `checkAuth` → `showAuthUI` / `showLoginUI` 控制登录蒙层显隐
- 全局 401 handler 注册在 `api.js`
- 密码重置、账户设置、邮箱验证码倒计时均为 DOM 操作

新设计：

```ts
// apps/app/src/composables/useAuth.ts
export function useAuth() {
  const authStore = useAuthStore()
  const router = useRouter()

  const showLogin = computed(() => !authStore.isAuthenticated)

  async function init() {
    const user = await authStore.checkAuth()
    if (user) {
      await bootstrapApp()  // 原 initApp()
    }
  }

  async function handleLogin(email: string, password: string) {
    await authStore.login(email, password)
    await bootstrapApp()
    await processPendingInvite()  // 团队邀请
  }

  function handleLogout() {
    authStore.logout()
    router.go(0)  // 或重置到登录态
  }

  return { showLogin, init, handleLogin, handleLogout }
}
```

**Vue 模板层面**：

```vue
<!-- App.vue -->
<template>
  <LoginModal v-if="showLogin" @login="handleLogin" />
  <div v-else id="appContent">
    <AppSidebar />
    <main class="main">
      <RouterView />
    </main>
    <GCAnalysisPanel />
  </div>
</template>
```

> **注意**：原 `index.html` 中所有模态框（configMask, billingMask, resetPasswordMask, accountSettingsMask）在新架构中应拆分为独立 Vue 组件（`<ConfigModal />`, `<BillingModal />`），通过 `v-model` 或全局状态控制显隐，不再在 HTML 中静态存在。

### 4.5 路由设计

**App SPA 路由**（替代多页面中的 `/app` 和 `/report/*`）：

```ts
// apps/app/src/router/index.ts
const routes = [
  {
    path: '/',
    component: () => import('../views/ChatView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/report/:type(gc|jstack)/:sid/:rid',
    component: () => import('../views/ReportView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/settings',
    component: () => import('../views/SettingsView.vue'),
    meta: { requiresAuth: true }
  },
]
```

**后端配合**：`/app` 继续返回 `index.html`，Vue Router 接管子路径。`/report/{sid}/{rid}` 和 `/jstack-report/{sid}/{rid}` 需要重定向到 `/app/report/gc/{sid}/{rid}` 或保持现有路径但返回 App 的 `index.html`。

> 建议：在新前端中统一路径格式，后端保留旧路径兼容，返回 App 入口让 Vue Router 处理。

**Admin SPA 路由**：

```ts
const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', component: DashboardView },
  { path: '/users', component: UsersView },
  { path: '/plans', component: PlansView },
  { path: '/billing', component: BillingView },
  { path: '/teams', component: TeamsView },
  { path: '/skills', component: SkillsView },
  { path: '/audit', component: AuditView },
  { path: '/feedback', component: FeedbackView },
  { path: '/config', component: ConfigView },
  { path: '/blog', component: BlogView },
]
```

### 4.6 i18n 迁移

**旧**：自建 `t()` 函数，手动派发 `langchange` 事件，手动遍历 DOM 更新属性。

**新**：`vue-i18n`，复用现有 `zh.json` / `en.json`。

```ts
// packages/shared/src/i18n/index.ts
import { createI18n } from 'vue-i18n'
import zh from './zh.json'
import en from './en.json'

function detectLang(): string {
  const stored = localStorage.getItem('jvmind_lang')
  if (stored && (stored === 'zh' || stored === 'en')) return stored
  const nav = (navigator.language || '').toLowerCase()
  return nav.startsWith('zh') ? 'zh' : 'en'
}

export const i18n = createI18n({
  legacy: false,
  locale: detectLang(),
  fallbackLocale: 'en',
  messages: { zh, en },
  // ⚠️ 修订(#7)：开发环境保留告警（配合 ESLint vue-i18n/no-raw-text 拦截遗漏）；
  // 仅生产静默返回 key，避免与"严格拦截遗漏翻译"的目标矛盾
  missing: import.meta.env.DEV
    ? undefined                       // 开发：vue-i18n 默认打印 missing 警告，便于发现漏翻
    : (_locale, key) => key,          // 生产：兜底返回 key，不刷错
})
```

> ⚠️ 修订（#7）：初稿 `missing:(l,k)=>key` 会**静默吞掉所有缺翻译告警**，这与风险表里"配 ESLint `no-raw-text` 防遗漏"的严格目标自相矛盾。本版让开发环境保留告警、生产兜底，两个机制不再打架。

**模板中使用**：

```vue
<template>
  <button>{{ $t('sidebar.new_session') }}</button>
  <span :title="$t('topbar.theme')">🌙</span>
</template>
```

**JS 中使用**：

```ts
import { useI18n } from 'vue-i18n'

const { t, locale } = useI18n()

function toggleLang() {
  locale.value = locale.value === 'zh' ? 'en' : 'zh'
  localStorage.setItem('jvmind_lang', locale.value)
}
```

### 4.7 样式迁移策略

**核心原则**：保留 CSS 变量体系，逐步组件化。

1. **全局样式**：将现有 `frontend/src/css/*.css` 整体复制到 `packages/ui/src/styles/`，作为全局基础样式引入 `main.ts`
2. **主题切换**：`useTheme` composable 封装 `data-theme` attribute 切换，保持与现有逻辑一致
3. **组件样式**：新 Vue 组件使用 `<style scoped>`，内部仍引用 CSS 变量（如 `var(--primary)`）
4. **ECharts 主题**：主题切换时重建图表实例，复用现有 `drawJstackCharts` / `drawCombinedChart` 逻辑，封装为 Vue 组件

```ts
// apps/app/src/composables/useTheme.ts
// ⚠️ 修订(#5)：不用 useDark（它会写 data-theme=""、读系统偏好 prefers-color-scheme、
// 默认 storageKey 是 vueuse-color-scheme，三点都和现有体系打架）。改为显式可控实现。
import { ref, computed, watchEffect } from 'vue'

// ⚠️ 必须与旧前端一致：先查清现有前端用的 key 和取值（见“待核实假设”），这里以 'theme' / 'light'|'' 为例
const THEME_KEY = 'theme'

type Theme = 'dark' | 'light'

function readInitial(): Theme {
  const stored = localStorage.getItem(THEME_KEY)
  if (stored === 'light' || stored === 'dark') return stored
  return 'dark'           // 现有默认是 dark
}

const theme = ref<Theme>(readInitial())

export function useTheme() {
  watchEffect(() => {
    const root = document.documentElement
    if (theme.value === 'light') {
      root.setAttribute('data-theme', 'light')   // 现有 CSS: [data-theme="light"] {...}
    } else {
      root.removeAttribute('data-theme')         // 现有约定：无 data-theme = dark（真正“移除”，不是 ""）
    }
    localStorage.setItem(THEME_KEY, theme.value)
  })

  const isDark = computed(() => theme.value === 'dark')
  function toggle() { theme.value = theme.value === 'dark' ? 'light' : 'dark' }

  return { theme, isDark, toggle }
}
```

> ⚠️ 修订（#5）：初稿 `useDark({ valueDark:'' })` 有三个坑：
> ① VueUse 在 dark 时会 `setAttribute('data-theme','')`（写空值属性，**不是移除属性**），若现有 CSS 依赖"无 `data-theme` = dark"，`data-theme=""` 行为可能与预期不符；
> ② `useDark` 结合 `usePreferredDark`，会在无存储值时**读系统 `prefers-color-scheme`**，可能覆盖用户选择；
> ③ `storageKey` 写 `'theme'`，但 `useDark` 默认 key 是 `vueuse-color-scheme`，且要和旧前端共享——**灰度期新旧前端 key/取值不一致会导致主题互相打架**。
> 所以本版改成 5 行显式实现，`removeAttribute` 真正移除属性，不读系统偏好，key/取值需先核对旧前端再定（见"待核实假设"）。

### 4.8 GC / JStack 分析面板迁移

这是旧代码中最复杂的部分，涉及：
- 文件上传 + 进度
- 报告 Tabs（多报告并行打开）
- ECharts 图表渲染（GC 堆内存/暂停时间、JStack 火焰图）
- 历史记录列表
- 报告上下文绑定到聊天

**组件拆解**：

```
GCAnalysisPanel.vue          # 右侧滑出面板容器
├── GCModeSwitcher.vue       # GC / JStack / Reports 三个 mode-tab
├── GCUploadZone.vue         # 拖拽上传区
├── GCReportTabs.vue         # 已打开报告的 tab 栏
├── GCReportView.vue         # 单份报告渲染（图表 + 统计卡片 + 健康横幅）
│   └── GCChartPanel.vue     # ECharts 实例封装
├── GCHistoryList.vue        # 历史报告列表
└── AllReportsView.vue       # "全部历史报告" 过滤器 + 列表
```

**关键状态**：

```ts
// apps/app/src/stores/gc.ts
export const useGCStore = defineStore('gc', () => {
  const openReports = ref<GCReport[]>([])     // 对应 state.openGcReports
  const currentReportId = ref<string | null>(null)
  const history = ref<GCReportMeta[]>([])
  const isUploading = ref(false)

  async function upload(file: File) {
    isUploading.value = true
    const form = new FormData()
    form.append('file', file)
    const report = await api.gc.upload(sessionStore.currentSessionId!, form)
    openReports.value.push(report)
    currentReportId.value = report.id
    isUploading.value = false
  }

  return { openReports, currentReportId, history, isUploading, upload }
})
```

---

## 五、分阶段迁移路线图

### Phase 0: 基建搭建（1-2 周）

| 任务 | 产出 |
|------|------|
| 初始化 `frontend-vue/` 目录 | `package.json`, `tsconfig.json`, Vite Monorepo 配置 |
| 搭建 `packages/shared` | API Client + TS 类型。⚠️ 修订(#7)：**用 `openapi-typescript` 从 FastAPI 的 `/openapi.json` 自动生成类型**（FastAPI 自带该端点），不要手翻——手翻必与后端漂移。关键写路径（登录/支付/上传）再叠 `zod` 做运行时校验 |
| 搭建 `packages/ui` | Button, Modal, Input 基础组件（可直接复制现有 CSS 样式） |
| 迁移 i18n | 复用现有 `zh.json` / `en.json`，接入 `vue-i18n` |
| 验证构建 | `npm run build` 产出到 `dist/`，后端配置 `/v2/app` 并行路由验证（见 §6.1b） |

> ⚠️ 修订（#7）：**Monorepo 工具二选一并写死**——推荐 **pnpm workspaces**（也可 npm workspaces，但要确认 CI 支持）。`@jvmind/shared/*` 这类 alias 不会自动生效，需同时配置：
> - 根 `tsconfig.json` 的 `compilerOptions.paths`（让 TS/IDE 认）；
> - 各 app 的 `vite.config.ts` 的 `resolve.alias`（让构建认）。
> 两处缺一会出现"IDE 不报错但构建找不到模块"或反之。建议在 Phase 0 就把这两处配好并验证一次跨包引用。

### Phase 1: 营销页面迁移（低风险，验证流程）（1-2 周）

- **landing** → `apps/marketing/pages/Landing.vue`
- **pricing** → `apps/marketing/pages/Pricing.vue`（Paddle checkout 逻辑复用）
- **blog/docs/terms/privacy/refund** → 对应 Vue 组件

这些页面交互简单、无 SSE、无复杂状态，适合团队熟悉 Vue 技术栈。

**验证标准**：SEO 标签（meta/OG/JSON-LD）输出与现有完全一致，Paddle checkout 可正常唤起。

### Phase 2: Admin 后台迁移（中等复杂度，独立应用）（2 周）

- 9 个 tab（dashboard, users, plans, billing, teams, skills, audit, feedback, config, blog）逐个迁移
- 每个 tab 对应一个 `View` 组件
- Admin 的 `apiWithAuth` 和 401 处理逻辑复用 `packages/shared`

**验证标准**：功能对等，管理员可操作所有现有功能。

### Phase 3: 核心 App 迁移（高风险，最高优先级投入）（4-6 周）

按功能模块拆解：

| 周次 | 模块 | 关键挑战 |
|------|------|----------|
| 1 | 认证 + 布局 | LoginModal, AppSidebar, AppTopbar, 主题切换 |
| 2 | 会话管理 | SessionList, 新建/删除/重命名, 团队 Tab 切换 |
| 3 | 聊天基础 | ChatArea, MessageBubble, 发送消息, Markdown 渲染 |
| 4 | SSE 流式 | ThinkingSection, TracePanel, 消息操作按钮（复制/反馈/保存） |
| 5 | GC 分析 | 上传区, 图表(ECharts), 历史, 报告上下文绑定 |
| 6 | JStack 分析 | 同 GC, 火焰图, 死锁检测展示 |
| 7 | 账单 + 配置 + 团队 | BillingModal(Paddle), ConfigModal, TeamPanel |
| 8 | 独立报告页 + 打磨 | ReportView 路由, 端到端测试, Bug 修复 |

### Phase 4: 切流与收尾（1 周）

1. 全量功能回归测试
2. 性能测试（首屏加载、内存泄漏）
3. 后端将 `/app`, `/admin`, `/`, `/pricing` 等路由默认指向 `frontend-vue/dist`
4. 保留旧前端代码 1-2 个版本周期后归档

---

## 六、后端配合修改清单

> ⚠️ 修订（#6）：以下为**建议骨架**，`frontend.py` 的真实现状（如何 mount、报告路由怎么写）需先核对仓库。两段分别对应"Phase 0–3 `/v2` 并行"和"Phase 4 整体切"。

### 6.1 `app/frontend.py`（Phase 4：整体切换）

```python
import os

_USE_VUE_FRONTEND = os.environ.get("USE_VUE_FRONTEND", "0") == "1"

# 构建产物目录选择
_fe = _base / ("frontend-vue/dist" if _USE_VUE_FRONTEND else "frontend/dist")
```

### 6.1b Phase 0–3：`/v2/*` 并行挂载 + SPA 深链 fallback

```python
_fe_vue = _base / "frontend-vue/dist"

# 静态资源（构建产物的 assets/lib/image）
app.mount("/v2/assets", StaticFiles(directory=_fe_vue / "app" / "assets"), name="v2-assets")

# ⚠️ SPA catch-all：/v2/app 下任意深链（含刷新 /v2/app/report/xxx）都返回 index.html，
# 否则用户在子路由刷新会命中后端不存在的路径 → 404。Vue Router 接管真正的路由。
@app.get("/v2/app/{rest:path}")
def v2_app(rest: str):
    return FileResponse(_fe_vue / "app" / "index.html")

@app.get("/v2/admin/{rest:path}")
def v2_admin(rest: str):
    return FileResponse(_fe_vue / "admin" / "index.html")
```

> 切到 Phase 4 后，对 `/app/{rest:path}` 等主路径同样要有这条 SPA catch-all（返回新前端 index.html），否则深链刷新一样 404。这是 SPA 部署的必备项，初稿遗漏。

### 6.2 报告页路由兼容

当前 `/report/{sid}/{rid}` 和 `/jstack-report/{sid}/{rid}` 是独立 HTML。在 Vue SPA 中建议统一为 `/app` 内的子路由：

```python
# 新前端模式下，报告路径返回 App 入口（Vue Router 接管）
@app.get("/report/{sid}/{rid}")
def report_page(sid: str, rid: str):
    if _USE_VUE_FRONTEND:
        return FileResponse(_fe / "index.html")  # SPA 入口
    return FileResponse(_resolve_page("report.html"))

@app.get("/jstack-report/{sid}/{rid}")
def jstack_report_page(sid: str, rid: str):
    if _USE_VUE_FRONTEND:
        return FileResponse(_fe / "index.html")
    return FileResponse(_resolve_page("report.html"))
```

Vue Router 配置对应路由：

```ts
{ path: '/report/:sid/:rid', component: ReportView, props: { type: 'gc' } },
{ path: '/jstack-report/:sid/:rid', component: ReportView, props: { type: 'jstack' } },
```

### 6.3 静态资源路径

新前端构建产物目录结构需保持 `/assets`, `/lib`, `/image` 与现有一致，或更新 `frontend.py` 的 `mount` 逻辑。

---

## 七、风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| **类型定义不准确** | API 返回结构与 TS 类型不匹配导致运行时错误 | **`openapi-typescript` 从 FastAPI `/openapi.json` 自动生成**（CI 里定期重生成防漂移）+ 关键写路径用 `zod` 运行时校验。不手翻类型 |
| **ECharts 在 Vue 中的生命周期** | 图表实例泄漏或主题切换不刷新 | 封装 `VChart` 组件，在 `onUnmounted` 中 `dispose()` |
| **SSE 重连与取消** | 用户快速切换会话导致流式消息错位 | composable 中使用 `AbortController`，切会话时 `abort()` |
| **Paddle.js 全局污染** | Paddle 依赖 `window.Paddle`，在 Vue 中需确保加载顺序 | 在 `index.html` 中保持 CDN 引入，或封装为 `useScriptTag` |
| **i18n 遗漏** | 新增代码忘记包裹 `$t` | 配置 ESLint 规则 `vue-i18n/no-raw-text` |
| **构建体积膨胀** | Vue + Pinia + vue-i18n 增加 bundle size | 路由懒加载 (`() => import(...)`)。⚠️ 修订：**ECharts/Marked/DOMPurify 走 CDN（用 `window.echarts` 等全局），Vue 端只做生命周期封装、不 `npm import` 这些库**，否则会重复打进 bundle、体积优化落空。在 vite 配 `build.rollupOptions.external` 把它们标为外部 |
| **新旧前端状态不同步** | 灰度期间用户可能交替访问两个前端 | 两者共用 `localStorage` key（如 `jvmind_lang`, `theme`），Cookie/Token 天然共享 |

---

## 八、开发规范速查

### 8.1 Vue 组件模板

```vue
<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'

interface Props {
  title: string
}
const props = defineProps<Props>()
const emit = defineEmits<{ click: [] }>()

const count = ref(0)
const doubled = computed(() => count.value * 2)

const { t } = useI18n()
</script>

<template>
  <div class="my-component">
    <h3>{{ props.title }}</h3>
    <button class="btn" @click="emit('click')">
      {{ t('common.click') }} ({{ doubled }})
    </button>
  </div>
</template>

<style scoped>
.my-component {
  padding: var(--radius-md);
  background: var(--bg-2);
}
</style>
```

### 8.2 Store 模板

```ts
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useXStore = defineStore('x', () => {
  // State
  const items = ref<Item[]>([])

  // Getters
  const count = computed(() => items.value.length)

  // Actions
  async function load() {
    items.value = await api.x.list()
  }

  return { items, count, load }
})
```

### 8.3 API 端点模板

```ts
// packages/shared/src/api/endpoints/sessions.ts
import { request } from '../client'
import type { Session, SessionDetail } from '../types/session'

export const sessionsApi = {
  list: (url: string) => request<{ sessions: Session[] }>(url),
  get: (id: string) => request<SessionDetail>(`/api/sessions/${id}`),
  create: (body: { org_id?: string }) =>
    request<Session>('/api/sessions', { method: 'POST', body: JSON.stringify(body) }),
  delete: (id: string) =>
    request<void>(`/api/sessions/${id}`, { method: 'DELETE' }),
}
```

---

## 九、即刻启动 checklist

如果你是执行者，按以下顺序启动：

1. [ ] 创建 `frontend-vue/` 目录，初始化 Vite + Vue + TS（可用 `npm create vue@latest`）
2. [ ] 配置 Monorepo（推荐 `pnpm workspaces` 或 `npm workspaces`）
3. [ ] 将现有 `frontend/i18n/zh.json` 和 `en.json` 复制到 `packages/shared/src/i18n/`
4. [ ] 搭建 `packages/shared/src/api/client.ts`，把现有的 `api.js` + `shared.js` 的 API 相关函数迁移过来
5. [ ] 从后端数据库模型或现有 JS 用法中提取首批核心 TypeScript 类型（`User`, `Session`, `Message`）
6. [ ] 在 `apps/app/` 中搭建最小可用页面：登录框 + 侧边栏 + 空聊天区
7. [ ] 配置后端 `frontend.py` 的 `/v2/app` 路由指向新前端，验证端到端连通
8. [ ] 按 Phase 3 周计划逐模块迁移

---

## 十、附录：旧代码 → 新代码速查表

| 旧代码位置 | 旧模式 | 新位置 | 新模式 |
|-----------|--------|--------|--------|
| `src/state.js` | 全局对象 | `stores/*.ts` | Pinia stores (Composition API) |
| `src/api.js` | 单一 `api()` 函数 | `packages/shared/api/` | 分层类型化 API client |
| `src/shared.js` | 工具函数杂烩 | `packages/shared/utils/` | 按功能分模块 |
| `src/app.js` | 空对象挂载 | 移除 | 直接用 store/composable |
| `src/auth.js` | DOM 操作 + 状态混杂 | `stores/auth.ts` + `composables/useAuth.ts` | 状态与视图分离 |
| `src/sessions.js` | `document.getElementById` 渲染列表 | `components/SessionList.vue` + `stores/session.ts` | Vue 响应式渲染 |
| `src/messages.js` | `innerHTML` 拼接消息 | `components/MessageBubble.vue` | 组件化 + `v-html`（受控） |
| `src/billing.js` | 模块级闭包变量 | `stores/billing.ts` | Pinia ref |
| `src/config-dialog.js` | DOM 事件硬编码 | `components/modals/ConfigModal.vue` | `v-model` 控制显隐 |
| `src/gc-analysis/` | 多文件通过 `app` 对象协作 | `components/gc/*.vue` + `stores/gc.ts` | 组件 + Store |
| `src/charts.js` | 全局函数操作 ECharts | `components/gc/GCChartPanel.vue` | 组件封装 + lifecycle |
| `i18n/index.js` | 自建 `t()` + 手动派发事件 | `vue-i18n` | `$t()` + 响应式 locale |
| `css/*.css` | 全局 `@import` | `packages/ui/styles/` + `<style scoped>` | 全局变量 + 组件隔离 |
