# MAT Inspector 能力盘点与实现建议

> 基于对 MAT 源码 `InspectorView.java` 的实读。先盘点 MAT Inspector 到底有哪些能力，
> 再对照我们已实现的（query-service `/object` + 前端 Inspector 面板），给出实现建议，由你定范围。

---

## 1. MAT Inspector 的真实结构（读源码确认）

MAT Inspector = **顶部固定信息表** + **4 个 Tab**：

### 1.1 顶部信息表（Top Table，固定几行 key-value）
读 `InspectorView` 的 `details.add(...)`，固定显示：
| 行 | 来源 | 我们有? |
|---|---|---|
| 对象地址 `0x...` | `getObjectAddress()` | ✅ |
| 对象的类（可点跳到类对象） | `getClazz()` → ObjectNode | ⚠️ 有 type 名，但不可点跳到类 |
| 父类 superclass | `getSuperClass()` | ⚠️ 仅类对象给了 superclass |
| **ClassLoader**（可点） | `getClassLoaderId()` → 对象 | ❌ 没有 |
| Shallow size | `getUsedHeapSize()` | ✅ |
| Retained size | `getRetainedHeapSize()` | ✅ |
| **GC Root 信息**（具体类型，如 "System Class"/"Thread"/"Busy Monitor"...） | `getGCRootInfo()` + 类型字符串 | ⚠️ 只给了布尔 gcRoot，没给**具体根类型** |
| Unindexed/未索引提示 | — | ❌（边缘场景，可不做） |

### 1.2 Tab 1：**Statics（静态字段）**
- 类的静态字段（`IClass.getStaticFields()`）。
- 点类对象时显示该类静态字段；点实例时显示**其类的**静态字段。
- 列：**Type（类型）/ Name（字段名）/ Value（值）**。
- ✅ 我们已实现（staticFields）。⚠️ 但没给"Type 列"（字段的声明类型）。

### 1.3 Tab 2：**Attributes（属性/实例字段）**
- 实例字段（`IInstance.getFields()`）。
- **对数组**：显示数组元素（`IPrimitiveArray`/`IObjectArray`，懒加载 LazyFields，因为可能几百万元素）。
- 列：Type / Name / Value。
- ✅ 实例字段、基本类型数组元素已实现。⚠️ 没给 Type 列；对象数组元素未展开（走了 outbound）。

### 1.4 Tab 3：**Class Hierarchy（类继承层次）**
- 显示该对象的类 → 父类 → 父父类 …一直到 Object 的**继承链树**。
- 数据：从 `getClazz()` 沿 `getSuperClass()` 向上。
- ❌ **我们没有**。这是个有用的视图（看类继承关系、判断 instanceof）。

### 1.5 Tab 4：**Value（解析值）**
- 对象的"语义化值"——`getClassSpecificName()`。
- 比如 String 显示其文本内容、URL 显示完整 URL、char[] 显示字符串、Date 显示日期。
- 这是 MAT 的 resolver 机制产出（文档03 §2.2 提过）。
- ⚠️ 我们 label 用了 displayName（含部分 resolver 名），但没有**独立的完整 Value**（长字符串会被 label 截断）。

### 1.6 其它交互能力
- **Pin（钉住）**：钉住当前对象，切换选择时不变（对比用）。❌ 未做（前端可加）。
- 顶部表格 + 各 Tab 的行**可右键**（List objects / Path to GC roots 等联动）。✅ 我们的联动已覆盖。
- 字段/引用里的对象**可点跳转**。✅ 已实现（reflink）。

---

## 2. 差距汇总（我们 vs MAT）

| 能力 | 状态 | 价值 |
|---|---|---|
| 地址/shallow/retained | ✅ | — |
| 实例字段 Fields | ✅ | — |
| 静态字段 Statics | ✅（含 Type 列、搜索、排序） | — |
| 出/入引用 | ✅（MAT Inspector 本身其实没"入引用"，是我们加的增强，好） | 加分 |
| 基本类型数组元素 | ✅ | — |
| **字段 Type 列 / 搜索 / 排序** | ✅ 已实现（§5.1） | — |
| **属性值：基础类型直接看 / String内容 / char码点 / Unicode** | ✅ 已实现+实测（§5.2-5.3） | — |
| **引用值可跳转** | ✅（§5.4） | — |
| **ClassLoader 行**（可点） | ❌ | 中高（ClassLoader 泄漏排查要） |
| **GC Root 具体类型** | ⚠️ 只有布尔 | 中（"为什么是根"——System Class? Thread? Monitor?） |
| **类可点跳转**（type → 类对象） | ⚠️ | 中（钻取流畅度） |
| **Class Hierarchy Tab** | ❌ | 中（看继承链） |
| **Value Tab**（完整解析值） | ⚠️ | 中（长 String/URL 看全） |
| **Pin 钉住** | ❌ | 低（对比时有用） |
| 对象数组元素展开 | ⚠️ 走 outbound | 低（outbound 已够用） |

---

## 3. 实现建议（分三档，你选）

### A 档 · 高性价比补全（建议做，改动小）
1. **GC Root 具体类型**：后端 `getGCRootInfo()` 已取，把**类型字符串**也返回（如 "Thread"/"System Class"/"Busy Monitor"），不只布尔。→ 排查"为什么没被回收"更直接。
2. **ClassLoader 行**：后端加 `classLoaderId` + label（`getClassLoaderId()` → getObject）。前端顶部信息加一行，可点。→ ClassLoader 泄漏排查刚需。
3. **字段 Type 列**：后端 Field 已能拿类型（`FieldDescriptor.getType()` 类型码 → 可读类型）。三列 Type/Name/Value 对齐 MAT。
4. **类可点跳转**：顶部 type 行 → 点击跳到类对象（classId = `getClazz().getObjectId()`）。

> A 档把"信息完整度"补齐到 MAT 水平，改动都在 ObjectView + 前端 Inspector，1~2 处小改。

### B 档 · 新增 Tab（中等改动）
5. **Class Hierarchy**：后端加 `/object` 返回 `hierarchy:[类, 父类, ...]`（沿 getSuperClass）。前端 Inspector 加一块"继承链"。
6. **Value（完整解析值）**：后端返回 `classSpecificName`（完整，不截断）。前端单独显示（长 String/URL 看全）。

### C 档 · 交互增强（可选，前端为主）
7. **Pin 钉住**：前端加按钮，钉住后切换选择不刷新（对比两对象用）。
8. **对象数组元素**：Inspector 里专门列数组元素（目前走 outbound 也能看）。

---

## 4. 我的推荐
- **先做 A 档**（GC Root 类型 + ClassLoader + Type 列 + 类可点）——这几项是"信息完整度"短板，且都是泄漏排查实际要用的，改动小。
- B 档的 **Class Hierarchy** 按需（看继承关系时有用）；**Value** 如果常看长字符串值则值得做。
- C 档优先级最低。

> 注：MAT Inspector 本身**没有"入引用"Tab**（看入引用要用单独的 "Path to GC roots" / references 视图）。我们在 Inspector 里直接给了 Incoming，是**比 MAT 更方便**的增强，保留。

---

## 5. 已实现：属性表（Fields/Statics）增强 ✅（高频能力）

聚焦"看属性/静态属性"这个最高频场景，已落地以下能力（query-service `ObjectView` + 前端 Inspector）：

### 5.1 Type / Name / Value 三列 + 搜索 + 排序
- **三列**：Type（`Field.getVerboseSignature()` → ref/int/char/boolean…）/ Name / Value，对齐 MAT。
- **按字段名/值搜索**：每个表（Fields、Static Fields 各自独立）上方搜索框，实时过滤。
- **排序**：点表头 Type/Name/Value 排序，再点切升降序，表头显示 ▲/▼。
- 字段数据存前端 `State.fieldData`，搜索/排序前端重渲染（字段量小，最流畅）。

### 5.2 属性值的完整显示 ✅（本节重点）
| 值类型 | 显示 | 实现 |
|---|---|---|
| 基础类型 int/long/boolean/byte/short/float/double | 直接值 | `String.valueOf(v)` |
| **char** | `'用' (29992)`（字符+码点；不可见字符显示 `\uXXXX`） | `formatPrimitive()` |
| **String / char[] 字段** | **直接显示字符串内容**（带引号，过长截断），不再只是 `String @ 0x..` | `resolveRefValue()`：取目标 `getClassSpecificName()` |
| 其它引用对象 | 类名 @ 地址，**可点跳转** | `NamedReference.getObjectId()` → refId |
| 对象本身的语义值（点进 String/URL/Date） | 顶部 **Value 区**显示完整内容（≤4096 截断） | `detail()` 加 `value` = `getClassSpecificName()` |

### 5.3 Unicode / 多语言（已实测验证）★
字符串值含中文/日文/emoji 时**正确显示不乱码**。验证过的完整链路：
```
char[] (Java 字符本就是 Unicode) → getClassSpecificName() → 正确 String
  → Json.str()（只转义控制字符与 JSON 特殊字符，Unicode 原样保留）
  → HTTP 响应 Content-Type: application/json; charset=utf-8
  → 前端 fetch 默认 UTF-8 解析 → DOM 正常显示
```
沙箱实测后端 Json 输出：`{"zh":"用户名:张三","jp":"日本語テスト","emoji":"🚀💡"}` —— 全部原样正确。
> 关键点：`Json.str()` 的转义逻辑**只对 `< 0x20` 的控制字符转 `\uXXXX`**，中文/emoji（≥0x20）原样输出；
> 配合 HTTP UTF-8 charset，Unicode 全链路无损。**不要**把所有非 ASCII 转成 `\u`（那样虽合法但 payload 膨胀且难读）。

### 5.4 引用可跳转（钻取）✅
- 字段值若是引用 → 渲染成链接（reflink），点击 Inspector 刷新到目标对象。
- String 字段 → 点值跳到该 String / 其底层 char[]。
- 配合 Outgoing/Incoming，可像 MAT 一样在 Inspector 里一路钻取。

### 5.5 性能权衡（记录）
`resolveRefValue` 对**每个引用字段**调一次 `getObject().getClassSpecificName()`（每引用一次对象读）。
单对象、字段几十个时可接受。若实测慢，可优化为：只对 `java.lang.String`/已知文本类解析，其余字段值保持引用名。

---

## 6. 剩余可选（按需）
- GC Root 具体类型（目前布尔）、ClassLoader 行（可点）、类可点跳转 —— A 档剩余项。
- Class Hierarchy Tab、Pin 钉住 —— B/C 档。
- 对象数组元素在 Inspector 内展开（目前走 Outgoing）。
