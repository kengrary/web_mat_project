# Heapdump 前端落地设计（对齐真实端点）

> 本文是 heapdump 前端的**可执行落地设计**，区别于文档 09（通用"复刻 MAT 体验"建议）：
> 这里把**端点映射、异步任务状态机、架构红线、分期验收**全部写死，对齐 query-service 真实端点，
> 让执行的人不必再猜。
>
> **前置约定（来自既有架构决策，勿推翻）**：前端是 JVMind 现有 Web（FastAPI 后端 + 既有 GC/JStack 面板）。
> heapdump 复用现有 `report_context` 机制（对话最多挂 3 个报告）。

---

## 0. 核心认知：heapdump 是"分析器"，不是"报告"

| 维度 | GC / JStack | Heapdump |
|---|---|---|
| 内容 | 解析后一份 stats JSON | 多维交互视图（Histogram/Dominator/Threads/OQL…）|
| 交互深度 | 浅：看统计、看 AI 结论 | 深：逐层展开、对象 Inspector、右键联动 |
| 面板形态 | 单页滚动 | 类 IDE 三栏（导航 / 内容 / Inspector）|
| 与对话的关系 | 报告挂上下文，问 AI 结论 | 分析中随时"把当前对象发给 Agent 诊断" |

**结论**：不能把 heapdump 塞进 GC/JStack 的"单页报告"模板。用**渐进披露**：
**模式 A 精简概览（默认，类 GC/JStack）→ 一键展开 → 模式 B 全功能分析器（类 MAT 三栏）**。
web-prototype 的三栏设计就是"模式 B"的形态。

---

## 🔴 架构红线（最重要，写在最前防止跑偏）

> **前端只跟 Python（FastAPI）说话，绝不直连 query-service（:8090）。**

- query-service **无鉴权、无 CORS、绑内网 IP**（`MAT_QUERY_BIND`），**Python 是唯一入口**。
- 前端调用形如 `/api/heapdump/<reportId>/histogram?...`，由 **Python 反代**到 query-service 的 `/histogram?dumpDir=...`。
- 前端**不持有 dumpDir**，只持有 `reportId`；`reportId → dumpDir` 的映射在 Python 侧（DB 查 heapdump_reports）。
- "问 Agent" **不是前端直接调工具**，而是**往对话发一条带 objectId 的消息**，由 Python 侧 ReAct loop 决定调哪个工具。

⚠️ 以下是**待核实的占位**（对接文档只确认了后端 FastAPI + report_context，未确认前端模块拆分）：
具体前端文件名（state/tabs/context 之类）、组件框架、路由——**执行前需对现有前端代码核实**，本文只描述"在哪类逻辑里加分支"，不写死文件名。

---

## 1. 端点映射表（前端用什么、Python 反代到哪、同步还是异步）

> 前端路径（Python 侧，待最终定名）→ query-service 真实端点。**dumpDir 由 Python 注入，前端不传。**
> ✅=已实现并由用户本地/沙箱验证过基础视图；其余为已实现待真实环境单测。

### 同步端点（请求即返回 JSON）

| 视图 / 用途 | query-service 端点 | 关键参数（前端可控） |
|---|---|---|
| 概览（模式 A） | `GET /overview` | `full=true`（含 Heap Dump Info + System Properties）|
| Histogram | `GET /histogram` | `top=50` `offset=0` `sort=shallow\|retained\|count` `approx=true` `objectSet=rs-xxx`(结果集复用) |
| Dominator（懒加载，一层） | `GET /dominator` | `parent=ROOT\|<objectId>` `top=50` `offset=0` |
| Threads（三级树） | `GET /threads` | 不传=线程列表；`thread=<id>`=取栈帧；`frame=<...>`=取局部变量；`top/offset` |
| ThreadLocals | `GET /threadlocals` | `groupBy=valueClass\|thread` `top=100` |
| **Inspector 数据源** | `GET /object` | `id=<objectId>`（字段+静态字段+出/入引用+GC root+值）|
| OQL | **`POST /oql`** body `{"q":"<语句>","limit":200,"offset":0}` | query 参数 `view=list\|histogram` `sort=shallow\|retained\|count`；返回含 `resultSetId` |
| Path to GC Roots | `GET /path2gc` | `object=<id>` `excludeWeakSoft=true`（单对象通常快，同步）|
| Top Consumers | `GET /top-consumers` | — |
| OOM 横幅（轻、同步） | `GET /oom-diagnosis` | `culpritPct=30` |
| SQL in Threads | `GET /sql-in-threads` | `onlyRisky=true` |
| Connection Pools | `GET /connection-pools` | —（含 Druid/HikariCP 空闲/活跃连接明细）|
| Pool Leaks（泄漏归因） | `GET /pool-leaks` | `thresholdMs=300000` |

### 异步端点（提交→拿 taskId→轮询 `/tasks/{id}`）

| 视图 / 用途 | query-service 端点 | 备注 |
|---|---|---|
| **OOM 复合诊断 🧪** | `GET /diagnose-oom` | `culpritPct=30` `onlyRisky=true`；聚合 OOM触发线程+SQL+Leak Suspects，**含 leakhunter（重）** |
| Leak Suspects 🩺 | `POST /leak-suspects` | leakhunter 全图分析 |
| Retained Set | `GET/POST /retained-set` | body `{"objects":[ids]}` 或 `objectSet=rs-xxx` |
| Merge Paths | `GET/POST /merge-paths` | 同上 |

> **注意点（建议里常踩错的）**：
> - `oom-diagnosis`（同步、轻）和 `diagnose-oom`（异步、重、出 summary）**是两个不同端点**：
>   - 概览顶部 **OOM 横幅** 用 `/oom-diagnosis`（快）；
>   - 菜单里 🧪 **OOM 复合诊断** 用 `/diagnose-oom`（一步出结论，符合"快速诊断"语义）。
> - **Inspector 全靠 `/object?id=`**，别忘了它是 M2 的硬依赖。

---

## 2. 异步任务状态机（M2 就要搭好，否则 M4 卡死）

后端 TaskManager 真实状态（**不是 ERROR**）：`RUNNING / DONE / FAILED / CANCELLED / NOT_FOUND`，
结果是**软引用缓存**，可能被回收 → `DONE` 但带 `resultEvicted:true`（无 `result`）。

`GET /tasks/{id}` 返回示例（字段名按真实 TaskManager 输出）：
```jsonc
// 运行中（带进度 + 当前阶段，可驱动进度条/阶段文案）
{"taskId":"t-xxx","kind":"diagnose-oom","status":"RUNNING","progress":0.4,"phase":"leak-suspects (leakhunter)"}
// 完成
{"taskId":"t-xxx","kind":"diagnose-oom","status":"DONE","result": { /* 已是 JSON */ }}
// 完成但结果被回收（软引用），无 result
{"taskId":"t-xxx","kind":"diagnose-oom","status":"DONE","resultEvicted":true}
// 失败 / 超时（后端超时也置 FAILED，error="timeout after Ns"）
{"taskId":"t-xxx","kind":"diagnose-oom","status":"FAILED","error":"..."}
// 不存在
{"status":"NOT_FOUND"}
```

**前端轮询助手（已在 web-prototype 验证可用）**：
```js
async function pollTask(taskId, intervalMs=1000, maxTries=600){
  for(let i=0;i<maxTries;i++){
    const t = await api(`/tasks/${taskId}`);
    if(t.status==='DONE'){
      if(t.resultEvicted) throw new Error('结果已过期（软引用被回收），请重新分析');
      return t.result;
    }
    if(['FAILED','CANCELLED','NOT_FOUND'].includes(t.status))
      throw new Error(t.error || ('task '+t.status));
    await sleep(intervalMs);   // RUNNING → 继续
  }
  throw new Error('task timeout');
}
```

**异步视图必须设计的 4 个 UI 状态**（建议里完全没提，是会卡 M4 的坑）：
1. **运行中**：进度条/转圈 + "正在做全图分析，可能几分钟"（leakhunter/diagnose-oom 真的慢）。
2. **完成**：渲染结果。
3. **失败/超时**：FAILED/timeout 提示 + **重试按钮**（重新提交）。
4. **结果被回收**：`resultEvicted` → "结果已过期，点击重新分析"。

可选：`DELETE /tasks/{id}` 取消任务（用户切走视图时调，省后端资源）。

---

## 3. 模式 A：精简概览（默认，嵌入现有报告面板区）

上传解析完成后，heapdump 报告面板**就放在现有 GC/JStack 面板旁**，默认是这个概览：

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
- 「添加到对话」→ 走现有 `report_context`（type=heapdump），把报告挂进对话给 AI。
- 快速诊断按钮点击 → 切换到模式 B 并定位到对应视图。

---

## 4. 模式 B：全功能分析器（类 MAT 三栏）

点任一快速诊断按钮后，面板扩展为三栏（web-prototype 形态）：

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

### 4.1 左导航分组
```js
const HEAPDUMP_VIEWS = [
  { group:"概览", items:[ {id:"overview", icon:"📊", label:"Overview"} ]},
  { group:"视图", items:[
    {id:"histogram", icon:"📈", label:"Histogram"},
    {id:"dominator", icon:"🌲", label:"Dominator Tree"},
    {id:"threads",   icon:"🧵", label:"Threads"},
    {id:"threadlocals", icon:"🧷", label:"ThreadLocals"},
  ]},
  { group:"诊断", items:[
    {id:"diagnoseOom",     icon:"🧪", label:"OOM 复合诊断"},   // → /diagnose-oom（异步）
    {id:"leakSuspects",    icon:"🩺", label:"Leak Suspects"},  // → /leak-suspects（异步）
    {id:"connectionPools", icon:"🔌", label:"Connection Pools"},
    {id:"sqlInThreads",    icon:"🗄️", label:"SQL in Threads"},
  ]},
  { group:"高级", items:[ {id:"oql", icon:"🔎", label:"OQL"} ]},
];
```
> 图标用 emoji 即可（见 §6 沙箱限制）。诊断组里 🧪/🩺 是异步视图，需走 §2 状态机。

### 4.2 各视图要点（数据源 → 交互）
- **Histogram**：`/histogram`。列点击排序（shallow/retained/count）；行点击→Inspector；右键→`List objects`/`Path to GC Roots`/`Histogram of this`。虚拟滚动 + `top/offset` 分页。
- **Dominator**：`/dominator?parent=ROOT`，展开节点→`?parent=<objectId>` 取下一层（懒加载）。带 `percent`（占总堆%）列。
- **Threads**：三级懒加载——列表(`/threads`)→栈帧(`?thread=<id>`)→局部变量(`?frame=<...>`)。局部变量直接显示 retained（连接泄漏金矿）。
- **OQL**：`POST /oql`，返回 `resultSetId` → 见 §5 结果集复用。
- **诊断视图**：Connection Pools(`/connection-pools`+`/pool-leaks`)、OOM 复合(`/diagnose-oom` 异步)、Leak Suspects(`/leak-suspects` 异步)、SQL in Threads(`/sql-in-threads`)。

### 4.3 Inspector（右栏，数据源 `/object?id=`）
```
┌────── Inspector ──────┐
│ #5567 CacheManager     │
│ Shallow 48 B           │   ← /object?id=5567
│ Retained 523 MB(45.7%) │
│ [🔍][🌲][🔗 Path][📈]  │   ← 快捷动作（打开新视图，带 objectId）
│ 字段表(可搜索/排序)     │
│ 出站引用(12) 入站(3)   │   ← 点引用 → 跳 /object 该对象
│ ───────────────────── │
│ [💬 问 Agent：为什么没回收] │  ← 见 §5.2，发对话消息，不直调工具
└────────────────────────┘
```

### 4.4 响应式三档
- `>1400px`：三栏并排（左 180 / 中 自适应 / 右 280）。
- 中等：Inspector 可折叠/点对象时从右侧滑出。
- 移动端：单栏 + 底部 Tab 切视图，Inspector 全屏弹层。

---

## 5. 两个"分析流灵魂"交互（heapdump 区别于看报告的关键）

### 5.1 结果集复用（resultSetId）
`POST /oql` / `retained-set` / `merge-paths` 返回 `resultSetId`。前端把它当"一批对象的句柄"：
- 喂给 `Histogram`：`/histogram?objectSet=rs-xxx`（对这批对象按类聚合）。
- 喂给 `retained-set`/`merge-paths`：`?objectSet=rs-xxx`。

UI：结果表上方放一条「结果集 rs-xxx — 对这批对象：[Histogram] [Retained Set] …」操作条。
这是"选一批对象→换角度再分析"的闭环，**比看静态报告强一个量级**。

### 5.2 Inspector → 问 Agent（遵守红线）
Inspector 底部「💬 问 Agent」**不直调 query-service**，而是：
1. 往当前对话**发一条消息**，附带 `reportId` + `objectId`（+ 预置问题，如"为什么 #5567 没被回收？"）。
2. Python 侧 ReAct loop 决定调 `/path2gc` 或 `/diagnose-oom` 等，返回结论到对话。

前端职责仅"组消息 + 触发发送"，工具编排归 Python。

---

## 6. 沙箱/预览限制（实现时别踩）

- Arena 预览在 `sandbox="allow-scripts"`、**无网络**的 iframe 里：外部 CSS/JS/字体/图片/CDN **都加载不了**。
- 图标**用 emoji 或内联 SVG**，别引 icon font / 外部图标库（预览会全裂）。
- 样式用内联或 `<style>` 内嵌；下载到本地浏览器打开则一切正常（仅预览降级）。
- query-service 用 JDK17/21 语法，沙箱 JDK11 编译不了——前端纯 JS 不受影响，但前端原型改完用 `node --check` 校验 JS。

---

## 7. 分期与验收（异步状态机提前到 M2）

| 阶段 | 交付 | 关键依赖（对齐真实端点） | 验收 |
|---|---|---|---|
| **M1** | 精简概览（模式 A） | `/overview?full=true` | 上传后看到指标卡 + 快速诊断按钮；可「添加到对话」 |
| **M2** | Histogram + Inspector + **异步任务框架** | `/histogram`、**`/object?id=`**、`pollTask`/`tasks/{id}` | 点行→Inspector出详情；轮询助手对 RUNNING/DONE/FAILED/resultEvicted 全覆盖 |
| **M3** | Dominator + Threads | `/dominator`(懒加载)、`/threads`(三级) | 树点开取一层不卡；线程局部变量显示 retained |
| **M4** | OQL + 诊断视图（含异步） | `POST /oql`(resultSetId)、`/leak-suspects`+`/diagnose-oom`(异步)、`/connection-pools` | 异步视图 4 状态都能正确显示；resultSetId 能喂 Histogram |
| **M5** | Agent 对话集成 | 发消息（**不直调工具**）；红线：前端不直连 8090 | 选中对象→「问 Agent」→对话里出现 Python 调工具的结论 |

---

## 8. 一句话总结

战略正确：**分析器≠报告、渐进披露（A→B）、复用三栏、结果集复用**。
落地要钉死的四件事：**① 前端只连 Python（红线）② 异步任务 4 状态机（M2 就搭）③ OOM 两个端点别混（横幅用 oom-diagnosis、菜单用 diagnose-oom）④ Inspector 数据源是 /object**。
端点映射见 §1，状态机见 §2，集成红线见红线区与 §5.2。

> 关联文档：09（通用复刻 MAT 体验）、04（Agent 工具化/复合工具）、05（系统集成/部署拓扑）、
> 06（四大视图 API）、10（Inspector 能力）、12（Python 串通执行计划）、13（OOM 线程级诊断）。
