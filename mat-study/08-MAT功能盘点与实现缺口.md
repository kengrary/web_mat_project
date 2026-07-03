# MAT 功能盘点与实现缺口

> 目的：把 MAT 的**全部 57 个内置 query**（= MAT UI 的完整功能面）盘点一遍，
> 对照已实现的（query-service M2+M3），标出缺口并按价值排优先级。
> 数据来源：`grep @CommandName` 穷举 MAT 源码，确认共 57 个 query。

---

## 0. 已实现 vs 全景（一句话）

我们已实现**四大核心视图**（histogram / 支配树 / 线程树 / OQL）+ overview。
这覆盖了 MAT 最高频的"手工浏览"能力，但 MAT 还有 **~50 个 query**，其中若干是**高价值且不可替代**的——
尤其是 **Leak Suspects 自动诊断**、**两 dump 对比**、**GC Root 路径/引用分析**、**集合分析**。

---

## 1. 全部 57 个 query 分类盘点

### ✅ 已实现（5）
| query | 我们的端点 | 说明 |
|---|---|---|
| `histogram` | /histogram | 直方图（含近似 retained 排序）|
| `dominator_tree` / `show_dominator_tree` | /dominator | 对象级支配树懒加载 |
| `thread_overview` / `thread_stack` / `thread_details` | /threads | 三级线程树 |
| `oql` | /oql | 对象查询语言 |
| `heap_dump_overview` | /overview | 概览（也用于回填 stats）|

### 🔴 P0 — 高价值、强烈建议补（OOM 诊断核心 + 你项目的差异化能力）
| query | 作用 | 为何重要 |
|---|---|---|
| **`leakhunter` / `leakhunter2`** | **Leak Suspects 报告**：自动找出泄漏嫌疑 + 生成人类可读结论 | MAT 的招牌功能；OOM 一键诊断；**直接喂给 agent 当结论基础** |
| **`find_leaks` / `find_leaks2`** | 泄漏检测（leakhunter 的底层）| 支撑上面 |
| **`path2gc`** | 单对象 → GC Root 最短路径 | "对象为什么没被回收" 的核心；泄漏归因金矿 |
| **`merge_shortest_paths`** | 多对象合并最短路径（按类分组）| 找泄漏模式最常用 |
| **`top_consumers` / `top_consumers_html`** | 最大内存消费者（按类/包/类加载器）| 概览"谁占内存" |
| **`show_retained_set`** | 任意对象集的 retained set | 评估"删掉这批能省多少" |
| **`customized_retained_set`** | 带排除规则的 retained set | 精细泄漏分析 |

### 🟠 P1 — 重要、第二批补（引用分析 + 集合分析 = 连接池诊断基础）
| query | 作用 |
|---|---|
| `gc_roots` | 列出所有 GC Roots（按类型分组）|
| `class_references` / `references_statistics` | 类级入/出引用统计 |
| `immediate_dominators` | 直接支配者（聚合）|
| `list_objects` | 列某类/某集合的对象（incoming/outgoing）|
| `select_objects_by_field` | 按字段值筛对象 |
| `group_by_value` | 按字段值分组（找重复）|
| **`hash_entries` / `hash_set_values`** | 提取 HashMap/HashSet 内容 → **连接池连接集合提取基础**（文档03）|
| `extract_list_values` | 提取 List 内容 |
| `collection_fill_ratio` / `collections_grouped_by_size` | 集合填充率/大小分布 → 缓存无界增长 |
| `map_collision_ratio` | HashMap 碰撞率（性能问题）|
| `duplicate_classes` | 重复类 → **ClassLoader 泄漏/热部署**（文档03）|

### 🟡 P2 — 有用、按需补（专项分析）
| query | 作用 |
|---|---|
| `delta_histogram` / `comparison_report` | **两 dump 对比** → 缓慢泄漏随时间增长（文档05 提过，定位渐进泄漏利器）|
| `component_report` / `component_report_top` | 组件级综合报告 |
| `find_strings` | 查字符串（含正则）|
| `system_properties` | JVM 系统属性 |
| `waste_in_char_arrays` / `array_fill_ratio` | char[]/数组浪费 |
| `arrays_grouped_by_size` | 大数组 |
| `primitive_arrays_with_a_constant_value` | 常量数组（可压缩浪费）|
| `pie_biggest_objects` | 最大对象饼图 |
| `big_drops_in_dominator_tree` | 支配树大跌点（聚集点）|
| `unreachable_objects` | 不可达对象 |

### 🟢 P3 — 专项/低频（特定场景）
| query | 作用 |
|---|---|
| `finalizer_overview` / `finalizer_queue` / `finalizer_thread` / `finalizer_in_processing` / `finalizer_thread_locals` | Finalizer 分析（队列积压 → 内存释放阻塞）|
| `soft_references_statistics` / `weak_references_statistics` / `phantom_references_statistics` / `finalizer_references_statistics` | 各类引用统计 |
| `reference_leak` | 引用泄漏 |
| `bundle_registry` / `leaking_bundles` | OSGi bundle 分析（Equinox 应用专用）|

---

## 2. 缺口里最该优先做的（结合你的项目目标）

你的项目目标是"超越 OOM + agent 自然语言诊断"。按这个目标，缺口优先级：

### 第一优先：Leak Suspects（`leakhunter`）★★★
- **为什么**：这是 MAT 最有价值的功能——不用人工浏览，直接产出"问题嫌疑 X 占 45.7% 堆 + 泄漏链 + 结论"。
- **对 agent 的意义**：agent 调一个 `mat_diagnose_leak_suspects(report_id)` 就能拿到结构化结论，
  远胜于让它自己一层层翻支配树。**这是复合诊断工具的第一张王牌**（文档04 已规划）。
- **复用**：直接调 MAT 的 `LeakHunterQuery`，不用重写算法。但它产出的是 MAT 报告对象（IResult/SectionSpec），
  需要写一个适配器把结果转成我们的瘦身 JSON。

### 第二优先：GC Root 路径（`path2gc` / `merge_shortest_paths`）★★★
- **为什么**：泄漏归因的核心——"谁攥着这个对象不放"。连接池泄漏诊断（文档03）直接依赖它。
- **复用**：`ISnapshot.getPathsFromGCRoots` / `getMultiplePathsFromGCRoots`（已在文档02 §D 分析过，BFS）。

### 第三优先：Top Consumers + Retained Set ★★
- `top_consumers`：概览"谁占内存"，比纯 histogram 更聚焦（按支配/包/类加载器）。
- `show_retained_set`：评估"删掉这批能省多少"。

### 第四优先：集合/Map 提取（`hash_entries` 等）★★
- **为什么**：这是**连接池领域诊断的技术基础**（文档03 §2.3）——能"看懂"连接池内部的连接集合。
- 没有它，连接池 Inspector 只能硬读字段，有了它能直接提取集合元素。

### 第五优先：两 dump 对比（`delta_histogram` / `comparison_report`）★★
- **为什么**：定位**缓慢泄漏**的唯一可靠手段——对比两个时间点的 dump，看什么在持续增长。
- 需要 SnapshotManager 支持同时持有两个 snapshot（已支持，acquire 两次即可）。

---

## 3. 一个关键技术点：MAT 报告（IResult）的适配

P0/P1 里很多 query（leakhunter、top_consumers、path2gc）返回的不是简单 int[]，而是 MAT 的
**结果对象**：`IResultTree` / `IResultTable` / `SectionSpec` / `CompositeResult` / `TextResult`。

> 我们的 `OqlView` 已经处理了 `int[]` 和 `IResultTable`。要支持更多 query，需要把这个适配能力
> **抽成通用的 `ResultRenderer`**：把任意 MAT IResult 递归渲染成瘦身 JSON（树/表/文本/复合）。
> 这是补齐大量 query 的**杠杆点**——一次写好 renderer，就能低成本接入几十个 query
> （配合文档04 的 `QueryDescriptor` 反射自动导出）。

```
通用渲染器（建议下一步先做这个，而非逐个 query）：
  IResult result = query.execute(context, listener);
  switch on result type:
    IResultTree   → 懒加载树 JSON（getChildren 一层）
    IResultTable  → 表格 JSON（已有，OqlView 里）
    TextResult    → {kind:text, html/text}
    SectionSpec / CompositeResult → 递归子结果
```

---

## 4. 建议的补齐路线

1. **先做通用 `ResultRenderer`**（IResult → 瘦身 JSON），这是接入大量 query 的地基。
2. **P0 王牌**：`leakhunter`（复合诊断工具 #1）+ `path2gc`/`merge_shortest_paths`（归因）。
3. **P0 补充**：`top_consumers` + `show_retained_set`。
4. **P1 连接池基础**：`hash_entries`/集合提取 → 喂给文档03 的连接池 Inspector。
5. **P1 其余**：gc_roots / references / list_objects / duplicate_classes。
6. **P2 对比**：delta_histogram / comparison_report（缓慢泄漏）。
7. **长尾 P2/P3**：用 `QueryDescriptor` 反射 + ResultRenderer **批量自动导出**（文档04），
   `search_tools` 按需暴露给 agent，不必逐个手写端点。

> 关键判断：**不要逐个手写 50 个端点**。先做 ResultRenderer + QueryDescriptor 自动导出这套"基础设施"，
> 大部分长尾 query 就能近乎零成本接入；只对 P0 的几个王牌（leakhunter/path2gc）做精细的瘦身 + 复合诊断封装。

---

## 4.5 实现进度（持续更新）
- [x] 通用 `ResultRenderer`（IResult→JSON）+ `QueryRunner`（统一执行）—— 已在 query-service
- [x] P0 王牌：leak-suspects / path2gc / merge-paths / top-consumers / retained-set —— 已接入
- [x] 重操作异步任务化（TaskManager）+ A 方案 Equinox 接线 —— 已实现
- [x] **QueryDescriptor 反射批量自动导出长尾 query** —— 已实现（query-service ToolsView）
  - ① `GET /tools[?filter=&core=]` 反射 QueryRegistry→QueryDescriptor 产出工具清单
    （command/name/help/category/args，每个 arg 带 type/mandatory/advice，LLM 友好）；
  - ② `POST /query {command,args}` 通用直通（QueryRunner+ResultRenderer，支持 async）。
  - 分级暴露：core 工具常驻 agent，长尾靠 filter 检索，治理 context 爆炸。
- [ ] TODO：P1 连接池基础（hash_entries 集合提取）→ 喂连接池 Inspector（文档03）
- [ ] TODO：P2 两 dump 对比（delta_histogram / comparison_report）

## 5. 与已有文档的衔接
- 文档04（工具化）：`QueryDescriptor` 自动导出 + 复合诊断工具 —— 本文的"批量接入"正是它的落地。
- 文档03（连接池诊断）：依赖本文 P1 的集合提取（hash_entries）。
- 文档05（系统集成）：两 dump 对比依赖 SnapshotManager 同时持有两个 snapshot。
- query-service：下一步先加 `ResultRenderer`，再接 P0 query。
