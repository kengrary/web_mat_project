# 超越 OOM：用完整内存镜像排查连接池泄漏等问题

> 核心观点：堆 dump 不只是"哪些对象占内存"，它是应用某一时刻**全部运行时状态的快照**。
> 连接池、线程池、缓存、锁、定时任务、事务上下文、ClassLoader……这些"活的状态"都以对象形态完整保存在镜像里。
> MAT 的图分析能力（引用关系 + retained size + GC root 路径 + 字段读取）对它们同样适用——
> 我们要做的是给这些**领域对象**叠加"语义解读层"。

---

## 1. 为什么完整内存镜像能排查连接池泄漏

连接池泄漏的本质：**连接被借出（checkout）后没有归还（close/return）**，导致池中"活跃连接"只增不减，最终 `getConnection()` 超时或耗尽。

在堆 dump 里，这些信息都是现成的：
- 连接池对象（如 `HikariPool`）持有一个**活跃连接集合**和**空闲连接集合**——就是几个字段指向的数组/队列。
- 每个被借出的连接对象，往往记录了**借出时间戳、借用线程、借出时的调用栈（leak detection 开启时）**。
- 连接对象的 **inbound 引用链**能告诉你"是谁还攥着这个连接不放"——这正是 MAT 的 `getInboundRefererIds` / `getPathsFromGCRoots` 的强项。

所以排查连接池泄漏，本质是三步图分析：
1. **定位池对象** → 找到 `HikariDataSource` / `DruidDataSource` 等类的实例。
2. **解读池内部状态** → 读字段，区分 total / active / idle / leaked，列出每个活跃连接。
3. **归因** → 对可疑的活跃连接，沿 inbound 引用查"谁持有它"+ 读它记录的借出栈/线程，定位泄漏代码。

---

## 2. MAT 已经具备的可复用机制（建立在它的扩展架构上）

读源码确认了 MAT 早就为"领域对象语义化"留好了扩展点：

### 2.1 `IObject.resolveValue("字段.路径")` —— 任意字段深读
```java
// 例：读 java.net.URL 的内部字段拼出完整 URL（CommonNameResolver.URLResolver）
IObject protocol = (IObject) obj.resolveValue("protocol");
IObject path     = (IObject) obj.resolveValue("path");
```
支持**点路径**逐层深入对象图，原始类型直接返回值，引用类型返回 `IObject`。这是所有领域解读的基础工具。

### 2.2 `@Subject("类全名") + IClassSpecificNameResolver` —— 语义化名称
```java
public interface IClassSpecificNameResolver {
    String resolve(IObject object) throws SnapshotException;
}
```
MAT 内置了 String/Thread/URL/AtomicReference/InetAddress 等几十个 resolver（`CommonNameResolver`），
还有框架专用的 `JettyNameResolvers` / `WildflyJbossNameResolvers` / `EclipseNameResolver`。
**我们可以照葫芦画瓢，为各连接池类写 resolver**，让 UI 里一个 `HikariPool` 直接显示成
`HikariPool[active=48/50, idle=0, waiting=12]` 而不是一串地址。

### 2.3 Collection / Map Extractor —— 理解容器内部结构
`ICollectionExtractor` / `IMapExtractor` / `CollectionExtractionUtils`：MAT 已能"看懂"
HashMap/ArrayList/ConcurrentHashMap 等的内部数组、size、fill ratio、hash 碰撞。
连接池内部的连接集合往往就是这些标准容器或其变体，可直接复用提取出元素列表。

### 2.4 OQL —— 类 SQL 即席查询
```sql
SELECT pool.activeConnections.count, pool
FROM com.zaxxer.hikari.pool.HikariPool pool
```
无需写代码就能跨实例聚合查询，适合做"一次性诊断脚本"。

### 2.5 Thread / ThreadLocal 视图
MAT 有 `getThreadStack`、ThreadLocals 相关查询。连接泄漏常和"连接绑在某个线程的 ThreadLocal / 事务上下文里没释放"有关，线程栈 + 局部变量能直接看到借连接的那一帧。

---

## 3. 主流连接池的"领域模型"对照（排查时要读的字段）

> 字段名随版本变化，需以实际 dump 中的类结构为准；下面是排查时要关注的**结构性位置**。

### 3.1 HikariCP（`com.zaxxer.hikari`）
- `HikariDataSource` → `pool` (`HikariPool`)
- `HikariPool` → `connectionBag` (`ConcurrentBag`)，内部：
  - `sharedList` —— 所有连接 `PoolEntry`
  - `PoolEntry.state` —— `STATE_IN_USE` / `STATE_NOT_IN_USE`（区分 active/idle）
  - `PoolEntry.connection` —— 真实 JDBC 连接
  - `PoolEntry.lastBorrowed` —— 借出时间（判断"借出过久"= 疑似泄漏）
- 开启 `leakDetectionThreshold` 时有 `ProxyLeakTask`，可能保留借出栈。
- **泄漏信号**：`state==IN_USE` 且 `now - lastBorrowed` 远超阈值的 PoolEntry。

### 3.2 Druid（`com.alibaba.druid.pool`）
- `DruidDataSource` → `connections[]`（idle 池）、`activeConnections`（`Map<DruidPooledConnection,?>`，活跃连接）
- `activeCount` / `poolingCount` / `maxActive` 字段直接给计数
- `DruidPooledConnection.connectStackTrace` / `connectedTime` —— **借出栈和时间，泄漏归因金矿**
- `removeAbandoned=true` 时还有 `traceEnable` 记录的调用栈
- **泄漏信号**：`activeConnections` 持续接近 `maxActive`，且其中连接 `connectedTime` 很老。

### 3.3 Tomcat JDBC Pool（`org.apache.tomcat.jdbc.pool`）
- `ConnectionPool` → `busy`（活跃 `BlockingQueue<PooledConnection>`）、`idle`
- `PooledConnection.timestamp` —— 最后使用时间
- `abandonWhenPercentageFull` / `removeAbandonedTimeout` 相关
- **泄漏信号**：`busy` 队列长期满，元素 timestamp 老旧。

### 3.4 DBCP / c3p0
- DBCP：`PoolingDataSource` → `GenericObjectPool`（commons-pool）→ `allObjects` / `idleObjects`，每个 `PooledObject` 有 `state`(ALLOCATED/IDLE)、`borrowedCount`、`lastBorrowTime`。
- c3p0：`PooledDataSource` → `C3P0PooledConnectionPool` → `unmanaged/managed` 资源集合。

> 同样的方法论可推广到：**线程池**（`ThreadPoolExecutor.workers`/`workQueue` 积压）、
> **HTTP 连接池**（Apache HttpClient `PoolingHttpClientConnectionManager`、OkHttp `ConnectionPool`）、
> **缓存**（Caffeine/Guava/Ehcache 的 size 与 retained）、**Netty** ByteBuf 泄漏等。

---

## 4. 落地：在我们的 Web 后端里做成"诊断插件"

复用前两份文档的架构（嵌 Equinox + ISnapshot），在其上加一层 **领域诊断模块（Inspectors）**。

### 4.1 抽象一个诊断器接口
```java
public interface PoolInspector {
    // 该诊断器关心哪些类（精确名或前缀）
    Set<String> subjectClasses();
    // 对一个池实例，提取结构化诊断结果
    PoolDiagnosis inspect(IObject poolInstance, ISnapshot snapshot) throws SnapshotException;
}

// 结构化结果（直接转 JSON 给前端）
record PoolDiagnosis(
    String poolType,              // "HikariCP"
    String poolName,
    int total, int active, int idle, int waiting, int max,
    List<ConnectionRecord> suspectedLeaks  // 疑似泄漏连接
) {}

record ConnectionRecord(
    int objectId, long address,
    long borrowedAgeMillis,       // 借出多久了
    String borrowingThread,       // 借用线程
    String borrowStackHint,       // 借出栈（若 leak detection 开启）
    int[] inboundPathToGcRoot     // 谁持有它（懒查）
) {}
```

### 4.2 实现要点（以 HikariCP 为例的伪代码）
```java
// 1. 找所有池实例
for (IClass c : snapshot.getClassesByName("com.zaxxer.hikari.pool.HikariPool", false))
  for (int id : c.getObjectIds()) {
    IObject pool = snapshot.getObject(id);
    // 2. 读内部 bag 的 sharedList
    IObject bag = (IObject) pool.resolveValue("connectionBag");
    // 用 CollectionExtractionUtils 提取 sharedList 元素 → PoolEntry 列表
    // 3. 逐个 PoolEntry 读 state / lastBorrowed
    //    state==IN_USE 且 age 超阈值 → 加入 suspectedLeaks
    // 4. 对 suspect，按需 snapshot.getPathsFromGCRoots(entryId, excludeWeakRefs)
    //    解析持有者 + 读借用线程
  }
```

### 4.3 关键技术点
1. **复用现成能力**：`resolveValue`(深读字段)、`CollectionExtractionUtils`(读集合)、`getPathsFromGCRoots`(归因)、`getThreadStack`(借出帧)。完全不用碰底层 index 格式。
2. **版本鲁棒性**：字段名按版本变，做成**配置驱动**（YAML 描述每个连接池版本的字段路径），找不到字段时降级为"尽力而为"+ 提示，而不是崩。
3. **时间判定**：dump 里没有"当前时间"，但有连接的 `lastBorrowed`/`connectedTime`。可用"dump 中所有连接的最大时间戳"近似为 dump 时刻，算相对借出时长。
4. **性能**：池实例通常很少（个位数），遍历其连接集合是局部操作，秒级完成，可放请求线程；归因(paths2gc)较重，做懒加载/异步。
5. **扩展即插件**：每种连接池一个 `PoolInspector` 实现，用 `@Subject` 风格注册，新增框架不动核心。

---

## 5. 前端呈现（贴合 MAT UI 模式）

- **诊断概览页**：扫描所有已知池类型，卡片展示 `active/idle/max/waiting` + 泄漏告警徽标。
- **泄漏明细表**：每个疑似泄漏连接一行：借出时长、借用线程、借出栈片段、retained size。点击行 → 展开 inbound 引用链树（懒加载，复用 Path to GC Roots）。
- **一键跳转**：从连接对象跳到它的 Inspector（对象详情），或跳到借用线程的栈视图。
- **OQL 旁路**：高级用户可直接写 OQL 验证假设。

---

## 6. 能用同样方法排查的其它问题（同一套图分析能力的复用）

| 问题类型 | 在镜像里看什么 | 复用的 MAT 能力 |
|---|---|---|
| **数据库连接泄漏** | 池的 active 集合、连接借出时间/栈 | resolveValue + collection extractor + paths2gc |
| **线程泄漏 / 线程池打满** | 各 `ThreadPoolExecutor` 的 workQueue 积压、线程数；重复的线程名前缀 | getThreadStack + histogram(按线程名分组) |
| **ThreadLocal 泄漏** | Thread.threadLocals 表里残留的大对象 | ThreadLocals 查询 + retained size |
| **ClassLoader 泄漏（热部署）** | 重复加载的同名类、多个 WebappClassLoader 实例 | DuplicatedClassesQuery + ClassLoaderExplorer |
| **缓存无界增长** | Caffeine/Guava/Map 的 size、fill ratio、retained | collection/map extractor + retained set |
| **Finalizer 阻塞** | Finalizer 队列积压 | FinalizerQueueQuery（MAT 内置）|
| **大对象/重复字符串** | 重复 String/byte[]、超大集合 | FindStrings + ArraysBySize（内置）|
| **死锁/锁竞争残留** | 持有 monitor 的线程、AQS 队列 | 线程视图 + AQS 对象字段读取 |

> 这些 MAT 大多已有内置 query（见 `org.eclipse.mat.inspections` 目录），我们的 Web 后端把它们包装成 REST + 友好可视化即可，**连接池诊断是其中需要我们自己补领域知识的那一类**。

---

## 6.5 实现进度
- [x] **连接池配置 + 统计提取 ✅ 已实现**（query-service `ConnectionPoolView` + `/connection-pools`）
  - 识别主流池：**HikariCP / Druid / Tomcat JDBC / DBCP2 / c3p0**（配置驱动 Spec：类名+配置字段+统计字段，含子类）。
  - 提取配置（maxPoolSize/url/username/超时…）+ 统计（active/idle 计数）。
  - **空闲/活跃连接区分（Druid，读源码确认）**：Druid 内部是不同结构——
    空闲=`connections[]`(DruidConnectionHolder[]，**前 poolingCount 个有效**，数组按 maxActive 分配后段为 null)；
    活跃=`activeConnections`(Map\<DruidPooledConnection,?>)。两者不同类型对象，分别列出（holder.useCount/connectTime；
    pooledConnection.connectedTimeMillis/connectStackTrace）。前端 Connection Pools 视图分"空闲/活跃"两区。
  - **空闲/活跃连接区分（HikariCP，读源码确认）✅**：与 Druid 不同——Hikari 所有连接都在同一个容器
    `pool.connectionBag.sharedList`(CopyOnWriteArrayList\<PoolEntry>)，靠 **`PoolEntry.state`** 分类：
    `1=IN_USE`(活跃/借出中)、`0=NOT_IN_USE`(空闲)、`-1=REMOVED`、`-2=RESERVED`(后两者不列)。
    每个 PoolEntry 给 `connection`(真实连接对象)、`lastBorrowed`/`lastAccessed`(nanoTime，借出/访问时刻)。
    输出 `connections.{idleCount,activeCount,otherCount,idle[],active[]}`，对象类型都是 PoolEntry（与 Druid 的 holder/pooledConnection 双类型不同）。
    `hikariConnections()` 用 `CollectionExtractionUtils.extractList` 提取 sharedList，遍历按 state 分桶（上限 1000 防超大池）。
  - **密码脱敏**（已验证）：password/secret/credential/token 等字段 → `******`，绝不回传明文。
  - 端点：`GET /connection-pools?dumpDir=..`；前端：🔌 Connection Pools 菜单（配置/统计表 + Inspect 跳转）。
- [x] **泄漏归因 ✅ 已实现**（query-service `PoolLeakView` + `/pool-leaks`）
  - Druid：activeConnections(Map) 借出连接 + connectedTimeMillis(借出时刻) + **connectStackTrace(借出栈，泄漏点)**。
  - HikariCP：connectionBag.sharedList 的 PoolEntry state=IN_USE + lastBorrowed。
  - 时间判定：dump 无当前时间 → 用借出时间戳最大值近似 dump 时刻算相对借出时长，超阈值(默认5min)=疑似泄漏。
  - 归因：借出栈直接给（Druid）；无栈时提示用 Path to GC Roots 看持有者。
  - 端点：`GET /pool-leaks?dumpDir=..&thresholdMs=300000`；前端：Connection Pools 视图内"泄漏归因"区。
  - 完整闭环：连接池配置/统计(健康) + 泄漏归因(借出超时连接+借出栈+持有者) —— 超越 MAT 原生。

## 7. 建议的演进路线

1. **先做通用图分析**（前两份文档的 M0~M4）——这是所有诊断的地基。
2. **第一个领域插件做连接池**：从 HikariCP + Druid 起步（国内最常见），跑通"扫描→解读→归因→可视化"闭环。
3. **抽象 PoolInspector 框架 + 配置驱动字段映射**，再扩展 Tomcat/DBCP/c3p0。
4. **横向复制到线程池/ThreadLocal/ClassLoader**（很多可直接复用 MAT 内置 query）。
5. **沉淀一个"诊断规则库"**：把每类问题的判定逻辑(阈值、信号)做成可配置规则，形成类似 Leak Suspects 的"自动体检报告"。

---

## 8. 需要你确认的点

1. 第一批要支持的连接池优先级？（建议 **HikariCP + Druid** 起步）
2. 诊断结果是要**实时交互查询**，还是也要生成**一键体检报告**（类似 MAT Leak Suspects）？
3. 字段映射用**硬编码 resolver** 还是**配置文件驱动**（后者更耐版本变化，但前期投入大）？
4. 除连接池外，下一个最想覆盖的问题类型是什么？（线程池 / ThreadLocal / ClassLoader 泄漏 / 缓存）
