# JVMind 前端 Vue 3 + TypeScript 重构指南

> **目标**：在现有 Vanilla JS 前端并行运行期间，逐步迁移到 Vue 3 (Composition API) + TypeScript 架构，实现零停机、按页面灰度切换。

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
VueUse            (常用 composables: useDark, useLocalStorage, useEventSource 等)
Vite 6.x          (保持构建工具一致)
```

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

- **开发环境**：`USE_VUE_FRONTEND=1` 走新前端
- **生产环境**：通过环境变量一键切换，秒级回滚
- **页面级灰度**：更精细的做法是在 `frontend.py` 中按路由判断，部分路由走新前端，部分走旧前端（见 5.3 节）

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
  // ... 29 个字段
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
    const url = sessionTab.value === 'org' && orgStore.currentOrg
      ? `/api/orgs/${orgStore.currentOrg.id}/sessions`
      : '/api/sessions?personal=true'
    const res = await api.sessions.list(url)
    sessions.value = res.sessions
  }

  async function selectSession(id: string) {
    if (chatStore.isStreaming) return
    currentSessionId.value = id
    const data = await api.sessions.get(id)
    chatStore.setMessages(data.messages)
    factStore.setFacts(data.facts)
    // ... 重置报告面板
  }

  return { sessions, currentSessionId, currentSession, loadSessions, selectSession }
})
```

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
    stream: (payload: ChatStreamPayload) => {
      return fetch('/api/chat/stream', {
        method: 'POST',
        credentials: 'same-origin',
        headers: csrfHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(payload),
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

  async function sendMessage(text: string, contexts: ReportContext[] = []) {
    if (isStreaming.value) return
    if (!sessionStore.currentSessionId) {
      await sessionStore.createSession()
    }

    isStreaming.value = true
    phase.value = 'reasoning'
    thinkingContent.value = ''
    finalContent.value = ''
    steps.value = []
    error.value = ''

    try {
      const res = await api.chat.stream({
        session_id: sessionStore.currentSessionId!,
        message: text,
        lang: i18n.global.locale.value,
        report_contexts: contexts,
      })

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
      phase.value = 'error'
      error.value = e.message
    } finally {
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
    sendMessage,
  }
}
```

**组件层使用**：

```vue
<!-- MessageBubble.vue -->
<template>
  <div class="msg assistant">
    <div class="bubble">
      <ThinkingSection
        v-if="thinkingContent"
        v-model:expanded="thinkingExpanded"
        :content="thinkingContent"
      />
      <div class="final-content" v-html="renderedFinal" />
      <TracePanel :steps="steps" />
    </div>
    <MsgActions :message-id="messageId" :content="finalContent" />
  </div>
</template>
```

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
  missing: (locale, key) => key,  // 与旧代码一致：未找到时返回 key
})
```

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
import { useDark, useToggle } from '@vueuse/core'

export function useTheme() {
  const isDark = useDark({
    attribute: 'data-theme',
    valueDark: '',        // 现有 dark 主题不设 data-theme
    valueLight: 'light',
    storageKey: 'theme',
  })
  const toggle = useToggle(isDark)
  return { isDark, toggle }
}
```

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
| 搭建 `packages/shared` | API Client + TypeScript 类型定义（先把后端 OpenAPI/schema 翻译为 TS types） |
| 搭建 `packages/ui` | Button, Modal, Input 基础组件（可直接复制现有 CSS 样式） |
| 迁移 i18n | 复用现有 `zh.json` / `en.json`，接入 `vue-i18n` |
| 验证构建 | `npm run build` 产出到 `dist/`，后端临时配置一条 `/v2/app` 路由验证 |

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

### 6.1 `app/frontend.py`

```python
import os

_USE_VUE_FRONTEND = os.environ.get("USE_VUE_FRONTEND", "0") == "1"

# 构建产物目录选择
_fe = _base / ("frontend-vue/dist" if _USE_VUE_FRONTEND else "frontend/dist")
```

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
| **类型定义不准确** | API 返回结构与 TS 类型不匹配导致运行时错误 | 后端提供 JSON Schema / OpenAPI，或用 `zod` 做运行时校验 |
| **ECharts 在 Vue 中的生命周期** | 图表实例泄漏或主题切换不刷新 | 封装 `VChart` 组件，在 `onUnmounted` 中 `dispose()` |
| **SSE 重连与取消** | 用户快速切换会话导致流式消息错位 | composable 中使用 `AbortController`，切会话时 `abort()` |
| **Paddle.js 全局污染** | Paddle 依赖 `window.Paddle`，在 Vue 中需确保加载顺序 | 在 `index.html` 中保持 CDN 引入，或封装为 `useScriptTag` |
| **i18n 遗漏** | 新增代码忘记包裹 `$t` | 配置 ESLint 规则 `vue-i18n/no-raw-text` |
| **构建体积膨胀** | Vue + Pinia + vue-i18n 增加 bundle size | 路由懒加载 (`() => import(...)`)，CDN 引入 ECharts/Marked（不打包） |
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
