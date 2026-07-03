# A 方案（嵌入式 Equinox）实测验证 + 关键缺陷修正

> 在沙箱（JDK11 + 真实 MAT 1.12.0 发行版）**真跑通了** A 方案：
> 嵌入式 Equinox 启动 → MAT bundle ACTIVE → openSnapshot 成功。
> 过程中抓到一个**会让 query-service 直接启动失败的设计缺陷**，并验证了正确解法。

## 实测结果

| 步骤 | 结果 |
|---|---|
| 反射调 EclipseStarter.startup | ✅ `Equinox running = true` |
| MAT bundle 激活 | ✅ hprof bundle state 8(STARTING)→32(ACTIVE) |
| openSnapshot 真实 hprof | ✅ `SUCCESS numObjects=12815` |
| dispose + shutdown | ✅ 干净退出，EXIT 0 |

环境：JDK 11，MAT 1.12.0，测试 dump `sun_jdk6_18_x32.hprof`。

## ★ 抓到的关键缺陷：ClassNotFoundException

第一版验证（AVerify）启动 Equinox 成功、bundle 也 ACTIVE，但：
```
[AVerify] Equinox running = true
[AVerify] hprof bundle state 8 → 32 (ACTIVE)
Exception: java.lang.ClassNotFoundException: org.eclipse.mat.snapshot.SnapshotFactory
    at AVerify.main(...Class.forName("org.eclipse.mat.snapshot.SnapshotFactory")...)
```

**原因（嵌入式 OSGi 的经典陷阱）**：
- OSGi 里每个 bundle 有**自己的 classloader**，类被 bundle classloader 隔离。
- 容器**外部**的应用代码（我们的 main / QueryRunner）用普通 AppClassLoader，
  **看不到 bundle 内的类** → `Class.forName("org.eclipse.mat...")` 必然 CNF。

> 这直接影响 query-service：`EquinoxBootstrap` / `QueryRunner` / 各 View **不能直接 `import` 或
> `Class.forName` MAT 类**（A 方案下运行期它们在不同 classloader）。这是凭空写代码绝对会漏、
> 一跑就炸的坑——实测提前抓到了。

## ✅ 正确解法（已验证）

通过 **bundle 的 classloader** 加载 MAT 类：
```java
Object matApiBundle = /* 从 systemContext.getBundles() 找 org.eclipse.mat.api */;
Method loadClass = matApiBundle.getClass().getMethod("loadClass", String.class);
Class<?> factory = (Class<?>) loadClass.invoke(matApiBundle, "org.eclipse.mat.snapshot.SnapshotFactory");
// 之后全程用反射调 factory（openSnapshot/dispose）
```
实测这样改后：`[A2] SUCCESS numObjects=12815`。

## 对 query-service 设计的修正（重要）

A 方案下有两条可行路线，**必须二选一**，否则 CNF：

### 路线 1：全反射桥接（当前 query-service 的最小改动路径）
- 在 EquinoxBootstrap 里暴露一个 `Bundle.loadClass` 桥，所有 View/QueryRunner 通过它拿 MAT 的
  `SnapshotFactory`/`ISnapshot`/`SnapshotQuery`/`QueryRegistry` 等 Class，全程反射调用。
- 缺点：代码全是反射，啰嗦、易错、没编译期类型检查。
- 适合：想保持"独立进程 + 普通 jar"形态，不改打包。

### 路线 2：把 query-service 自己做成一个 OSGi bundle（更干净，推荐）★
- 把 query-service 打成 bundle，声明 `Require-Bundle: org.eclipse.mat.api` 等，丢进 MAT 的
  `dropins/` 或 plugins/，由 MAT launcher（或我们起的 Equinox）加载。
- 这样 query-service 的代码和 MAT 在**同一 OSGi 类空间**，可以正常 `import SnapshotFactory`，
  无需任何反射——我们已写的 SnapshotManager/View/QueryRunner **几乎不用改**。
- 启动：注册一个 `IApplication`（像 ParseSnapshotApp 那样）在 application start 里 new QueryHttpServer。
- 缺点：要会 OSGi 打包（MANIFEST.MF + bundle 化）。
- **这其实更接近文档里说的"内嵌 Equinox"的本意**，且避免全反射。

> 结论：之前 Main/EquinoxBootstrap 写的"进程内反射起 Equinox + 直接 import MAT 类"是**自相矛盾**的——
> 起了容器，却又想用 AppClassLoader 直接调 bundle 类。实测证明这条死路。
> **修正：要么路线1（全反射），要么路线2（自身 bundle 化）。推荐路线2。**

## bundles.info 的发现（A 方案配置正解）

MAT 发行版**不是**手写 `osgi.bundles` 列全部 bundle，而是用 **simpleconfigurator**：
```
osgi.bundles = reference:file:org.eclipse.equinox.simpleconfigurator_*.jar@1:start
org.eclipse.equinox.simpleconfigurator.configUrl = .../configuration/.../bundles.info
```
`bundles.info` 列出每个 bundle 的路径/start level/autostart。
**这比 EquinoxBootstrap 里手写 bundle 清单稳得多**——直接复用 MAT 的 bundles.info，
让 simpleconfigurator 自动加载全部依赖闭包，不用自己猜"最小 bundle 集"。

注意：bundles.info 里 MAT bundle 是 `start level 4, autostart=false`（lazy activation）。
所以拿到 bundle 后可能需要显式 `bundle.start()` 触发 activator（实测 hprof 是 STARTING→需 start→ACTIVE）。

## 据此要改的代码（落地清单）
1. **EquinoxBootstrap**：
   - osgi.bundles 改用 simpleconfigurator + configUrl=MAT 的 bundles.info（删手写 bundle 列表）；
   - 暴露 `Class<?> loadMatClass(String fqn)`（通过 mat.api bundle 的 loadClass）。
2. **选路线2（推荐）**：给 query-service 加 MANIFEST.MF 做成 bundle，注册 IApplication，
   则 View/QueryRunner 的 `import org.eclipse.mat.*` 可保留，无需反射。
3. **选路线1**：QueryRunner + 各 View 改为通过 EquinoxBootstrap.loadMatClass 反射调 MAT API。

## 沙箱已验证 / 你本地需确认
- ✅ 已验证（沙箱 JDK11 + MAT1.12）：Equinox 嵌入启动、bundle ACTIVE、openSnapshot、bundle classloader 解法。
- ⏳ 你本地：选定路线1/2 后改 EquinoxBootstrap，用你的 JDK(17/21)+对应 MAT 版本 `mvn package` 跑通 query-service。

## 复现（沙箱用的最小验证程序思路）
```java
// 1) setInitialProperties: install.area=MAT根, simpleconfigurator + bundles.info, osgi.clean
// 2) EclipseStarter.startup(new String[0], null)
// 3) 从 getSystemBundleContext().getBundles() 找 org.eclipse.mat.api，必要时 bundle.start()
// 4) Class<?> f = (Class<?>) bundle.loadClass("org.eclipse.mat.snapshot.SnapshotFactory")  ← 关键
// 5) 反射 f.openSnapshot(hprof, listenerProxy) → getSnapshotInfo().getNumberOfObjects()
```
