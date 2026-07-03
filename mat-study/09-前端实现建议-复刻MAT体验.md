# 前端实现建议：在 Web 上复刻 MAT 使用体验

> 目标：用 Web 前端达到接近 MAT 桌面端的分析体验——**流畅的树形钻取、多视图联动、行内右键跳转、
> 海量数据不卡**。后端能力（query-service 端点）已就绪，本文聚焦前端怎么用好它们。
>
> 与现有 JVMind 前端一致：原生 ES Modules + Vite（按对接文档）。建议视图层用一个组件框架
> （React/Vue 均可，本文用通用描述），核心是**虚拟滚动表格 + 懒加载树**两个基础组件。

---

## 0. MAT 体验的本质（要复刻什么）
拆解 MAT 桌面端"好用"的几个核心特征，逐一对应到 Web：

| MAT 特征 | 本质 | Web 复刻手段 |
|---|---|---|
| 打开几十万行 histogram 不卡 | 虚拟滚动 + 懒加载 | 虚拟列表（只渲染可视行） |
| 支配树/引用树无限展开 | 树节点点开才取子节点 | 懒加载树（onExpand 调一层） |
| 行上右键"List objects / Path to GC roots / Merge paths" | 上下文动作 + 视图跳转 | 行右键菜单 → 打开新 Tab/面板 |
| 多个分析结果同时开着切换 | 多 Tab / 多 pane | Tab 容器，每个 Tab 一个视图实例 |
| 选一批对象→对它们再分析 | 对象集在视图间传递 | 把 objectId 集/resultSetId 传给新视图 |
| Inspector 看单对象字段/出入引用 | 详情侧栏 | 右侧 Inspector 面板，随选中行刷新 |
| 进度条（解析/重查询） | 异步 + 进度 | SSE 进度 / 任务轮询 |

> 一句话：**MAT = 一堆"表/树视图" + 它们之间靠对象 id 互相跳转 + 全程懒加载**。
> 前端只要把"虚拟滚动表/懒加载树 + Tab + 右键跳转 + Inspector"这套做扎实，体验就接近了。

---

## 1. 整体布局（贴 MAT 三区式）
```
┌─────────────────────────────────────────────────────────┐
│ 顶部工具条：dump 选择 / 概览 / 刷新 / 全局搜索(OQL入口)    │
├──────────────┬──────────────────────────────┬───────────┤
│ 左：导航/菜单 │ 中：视图 Tab 容器             │ 右：       │
│ (类 MAT 菜单) │  [Histogram][Dominator]...    │ Inspector │
│ - Overview    │  ┌──────────────────────────┐ │ (选中对象 │
│ - Histogram   │  │ 虚拟滚动表 / 懒加载树     │ │  详情/字段│
│ - Dominator   │  │                          │ │  /出入引用│
│ - Threads     │  └──────────────────────────┘ │  /retained│
│ - Leak Susp.  │                              │ )         │
│ - OQL         │                              │           │
└──────────────┴──────────────────────────────┴───────────┘
```
- **左侧菜单** = MAT 的功能入口（对应后端端点）。
- **中间 Tab** = 每次分析开一个 Tab（可同时开 Histogram + Dominator + 某对象的 Path2GC）。
- **右侧 Inspector** = 选中任意行 → 显示该对象详情（调 `/query` 的 inspect 或 outbound/inbound）。

---

## 2. 两个核心基础组件（做好这两个，体验就成了一半）

### 2.1 虚拟滚动表格（VirtualTable）
用于 histogram / oql 结果 / top-consumers 等"大列表"。
- **只渲染可视区行**（react-window / vue-virtual-scroller / 自写 IntersectionObserver）。
- **游标分页对接**：后端返回 `{rows, totalRows, cursor}`。滚到底 → 用 cursor 取下一页 append。
- 列：name/label、count、shallow、retained（带 human 字符串 + 原始 bytes 供排序）。
- 排序：点列头 → 重新请求（`?sort=retained|shallow|count`），**后端排序**（数据量大，不在前端排）。
- retained 列：`retainedApprox=true` 时显示 `≈`（近似值，呼应后端）。

### 2.2 懒加载树（LazyTree）
用于 dominator / threads / path2gc / 各种 IResultTree。
- 节点结构：`{objectId, label, retained, percent, expandable}`。
- **onExpand(node)** → 调后端取**一层**子节点：
  - dominator：`GET /dominator?dumpDir=&parent=<objectId>`
  - threads：线程→`?thread=<id>`(栈帧)→`&frame=<idx>`(局部变量)
  - path2gc 深层：用节点 objectId 重新发起 `path2gc(object=该id)`（后端树节点无跨请求 id，见 query-service 设计）
- 每层也分页（子节点可能很多）：节点下"加载更多"按钮用 cursor。
- 视觉：缩进 + 展开三角 + retained 条形（percent 画一个占比小条，MAT 风格）。

> 这两个组件 + 一个 Tab 容器，就能承载几乎所有视图。其余视图只是"配置不同列/不同端点"。

---

## 3. 各视图的前端实现要点

### Overview（概览）
- 卡片展示 `/overview` 的 numObjects/classes/gcRoots/usedHeapSize/jvmInfo。
- 顶部放几个"快速入口"按钮：Leak Suspects / Top Consumers / Histogram。

### Histogram
- VirtualTable，默认 `sort=shallow`，支持切 retained（近似）/count。
- 行右键菜单："List objects"（该类实例列表）/ "Show in Dominator" / "Path to GC roots (merge)"。
- **分组切换**（后续后端补 by package/classloader 时）：顶部下拉 → 重新请求。
- 提示：会有一堆 shallow=0 的无实例类（正常），默认可加"隐藏空类"开关。

### Dominator Tree
- LazyTree，根层 `parent=ROOT`。
- 关键列 **percent**（占总堆 %）——画占比条，MAT 的"一眼看出大头"靠这个。
- 行右键："Path to GC roots" / "List objects of this class" / "Inspect"。

### Threads（三级树）
- LazyTree 三层：线程 → 栈帧(text) → 局部变量(对象)。
- 线程行显示 name/daemon/retained/maxLocalRetained。
- **局部变量节点**是泄漏归因金矿：右键直接 "Path to GC roots" / "Inspect"。

### ThreadLocals（全局视图，泄漏排查）
- `/threadlocals?groupBy=valueClass`（默认）：按值类聚合表，retained 降序 + 占比条。
  `count 大 + 业务类` = 泄漏嫌疑；行可点进 Inspector。
- `groupBy=thread`：LazyTree（线程 → 它的 ThreadLocal 值）。
- 顶部聚合切换下拉。专治线程池复用导致的 ThreadLocal 残留。

### Overview（概览 + JVM 信息）
- `/overview?full=true`：数字卡片 + **Heap Dump Info**（堆格式/压缩指针/标识符大小/文件/创建时间）
  + **System Properties** 可搜索表（java.version/os.name/启动命令…）。
- 顶部放快速入口按钮（Histogram/Dominator/Leak Suspects/ThreadLocals）。

### Leak Suspects（王牌，复合结果）
- 后端 `/leak-suspects` 返回 `{kind:composite, sections:[...]}`（异步 taskId）。
- 前端：提交 → 进度条（轮询 `/tasks/{id}`）→ 渲染 composite：
  - `kind:text` 段 → 富文本/HTML（结论描述，MAT 的 "occupies 45.7%..."）。
  - `kind:tree/table` 段 → 复用 LazyTree/VirtualTable。
- 这是给非专家看的"自动诊断报告"，应放在最显眼位置。

### OQL
- 代码编辑器（CodeMirror/Monaco，带 SQL 高亮）+ 执行按钮。
- 结果按 `kind` 渲染：objectList→VirtualTable(对象行)，table→VirtualTable(自定义列)，scalar→文本。
- 错误高亮：后端返回 `{error, position}` → 编辑器标红那个位置。
- **结果集复用**：结果带 `resultSetId` → "对这批对象做 Histogram/Dominator"按钮（投喂回那些视图）。

### Path to GC Roots / Merge Paths
- LazyTree（引用链）。merge-paths 是异步（taskId）。
- 节点上标注引用类型（强/弱/软），支持"排除弱软引用"开关（后端参数）。

---

## 4. 视图联动（MAT 体验的灵魂）
**统一"对象动作"协议**：任何视图里选中一行（有 objectId）或一批行（objectId[]），都能触发：
```
inspect(objectId)           → 右侧 Inspector 刷新
listOutbound/Inbound(id)    → 新 Tab：引用列表
path2gc(id)                 → 新 Tab：GC root 路径树
histogramOfSet(ids)         → 新 Tab：对这批对象的 histogram
dominatorOfSet(ids)         → 新 Tab：对这批对象的支配树
```
- 实现：一个全局 `openView(type, params)` 调度器 → 在 Tab 容器开新视图。
- 右键菜单根据"选中的是单对象还是对象集"动态给可用动作。
- 这套做好，用户就能像 MAT 那样"从直方图某类 → 看实例 → 看谁引用 → 看 GC 路径"一路钻下去。

---

## 5. Inspector 面板（右侧）
选中任意对象行时刷新，显示：
- 基本：类名、地址（mapIdToAddress）、shallow / retained。
- 字段值（基本类型直接显示，引用类型显示 displayName + 可点进去）。
- 出引用 / 入引用（前 N 个 + 展开）。
- "Path to GC roots" 快捷按钮。
> 对应后端：`/query` 跑 inspect 类，或专门加 `/object/{id}` 端点（建议后端补一个对象详情端点，
> 目前可用 oql `SELECT * FROM ... WHERE ...` 或 dominator/outbound 拼）。

---

## 6. 异步与进度（别让用户干等）
- **解析阶段**：上传后 SSE 订阅 `/api/heapdump-reports/{id}/progress`，进度条显示 phase（Pass1/支配树…）。
- **重查询**（leak-suspects/retained-set/merge-paths）：提交得 taskId → 轮询 `/tasks/{id}`，
  显示进度 + phase；完成渲染，失败提示。给"取消"按钮（DELETE /tasks/{id}）。
- **就绪态**：report status≠DONE 时，分析菜单置灰 + 提示"解析中 X%"。

---

## 7. 性能与体验细节
1. **永远分页/懒加载**，绝不一次拉全量（后端也不会给）——靠 cursor。
2. **请求去抖/取消**：切 Tab 或快速点展开时，取消上一个未完成请求（AbortController）。
3. **结果缓存**：同一 dump 的 overview/histogram 前端缓存（后端也缓存），切回 Tab 不重拉。
4. **大数字人性化**：直接用后端给的 `shallow`/`retained` 字符串（已 "523.4 MB"），排序用 `*Bytes`。
5. **objectId 是稳定游标**：前端一律用 objectId 标识对象、做跳转入参；地址只在 Inspector 显示。
6. **空态/错误态**：解析失败、查询超时、结果被回收（resultEvicted）都要友好提示 + 重试按钮。
7. **快捷键**（进阶，更像桌面）：Enter 展开、方向键导航树、Ctrl+F 视图内过滤。

---

## 8. 与 Agent 的结合（JVMind 特色，超越 MAT）
MAT 只有手工分析，本平台还有 Agent——前端要把两者打通：
- **报告页有"问 AI"入口**：把当前 report 作为 `report_context(type=heapdump, report_id)` 带进对话。
- **Agent 回答里的对象/类可点**：agent 结论里提到的 objectId/类名，渲染成链接 → 点击跳到对应视图（Inspector/Path2GC）。这让"AI 给方向 + 人工钻取验证"闭环。
- **手工分析中"求助 AI"**：在某个视图右键 → "让 AI 解释这个" → 把当前对象/选中集喂给 agent。

---

## 9. 技术选型建议（落地）
- **虚拟表格**：react-window / TanStack Virtual（React）；vue-virtual-scroller（Vue）。自写也不难。
- **树**：基于虚拟列表的树（扁平化 + 缩进），避免递归 DOM 卡顿。
- **代码编辑器**（OQL）：CodeMirror 6（轻）或 Monaco（重，功能全）。
- **图表**（占比条/饼图，可选）：轻量用 SVG 自画；要饼图用 ECharts。
- **状态/请求**：TanStack Query（缓存+取消+重试天然适配分页懒加载）。
- 与现有 JVMind 前端（ES Modules + Vite）融合：作为一个新路由模块接入即可。

---

## 10. MVP 优先级（先做哪些）
1. **Overview + Histogram（VirtualTable）** —— 最高频，先让"能看堆里有什么"。
2. **Dominator Tree（LazyTree + percent 条）** —— "谁占内存"核心。
3. **视图联动 openView + 右键菜单 + Inspector** —— 体验从"能用"到"像 MAT"的关键。
4. **Leak Suspects（异步 + composite 渲染）** —— 给非专家的自动诊断。
5. **Threads / OQL / Path2GC** —— 补全。
6. **Agent 打通（report_context + 结论可点跳转）** —— 平台差异化。

> 先把 1~3 做扎实（虚拟表 + 懒加载树 + 联动），就已经有了 MAT 七成体验。
