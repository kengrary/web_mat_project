# query-service 构建排错

## 错误：No repository found at file:/home/pan/mat/p2

```
[ERROR] Failed to load p2 repository with ID 'mat-local' from location file:/home/pan/mat/p2:
        No repository found at file:/home/pan/mat/p2
```

### 根因（已在沙箱用真实 MAT 发行版核实）
**MAT 发行版根目录不是标准 p2 仓库**：
- 标准 p2 仓库需要根目录有 `artifacts.xml`(或 `.jar`) + `content.xml`(或 `.jar`) 索引。
- MAT 发行版根目录**没有**这两个文件。
- 那个 `p2/` 子目录是 **p2 运行时 profile 缓存**（`p2/org.eclipse.equinox.p2.core/...`），**不是仓库**。

所以 `file:/home/pan/mat` 和 `file:/home/pan/mat/p2` 当 `<layout>p2</layout>` 仓库都会失败。

### 解法（二选一）

#### 方案B·离线·推荐（你已有本地 MAT）★
用 Tycho 的 **`.target` 文件 + Directory location**，直接扫描 MAT 发行版的 `plugins/`
（里面 185 个标准 OSGi bundle jar，含 MAT 4 个 + 全部 Equinox/core runtime），不需要 p2 索引。

1. 编辑 `query-service.target`，把 path 改成你的 MAT 路径：
   ```xml
   <location path="/home/pan/mat" type="Directory"/>
   ```
2. pom.xml 已配置引用该 .target（`target-platform-configuration` 里的 `<target><file>...`）。
3. 构建：
   ```bash
   mvn -f query-service/pom.xml clean package
   ```

> 已核实：MAT 发行版 `plugins/` 里的 jar 都带 `Bundle-SymbolicName`（标准 OSGi bundle），
> Directory location 能正确识别为 target 平台单元。通常这一个 location 就覆盖了 MANIFEST.MF 里
> Require-Bundle 的全部依赖（MAT + Equinox 都在同一个 plugins/）。

#### 方案A·联网（最省事，不依赖本地 MAT）
MAT 官方 update-site 是标准 p2 仓库（实测 artifacts.jar/content.jar 返回 200）：
1. pom.xml 里删掉 `target-platform-configuration` 的 `<target>` 块；
2. 解开 `<repositories>` 注释，按 JDK 选版本：
   ```xml
   <repository>
     <id>mat-p2</id><layout>p2</layout>
     <url>https://download.eclipse.org/mat/1.14.0/update-site/</url>
   </repository>
   ```
   版本↔JDK：`1.15.x→JDK21`，`1.14.0→JDK17`，`1.12.0→JDK11`（1.12 已归档，用 `https://archive.eclipse.org/mat/1.12.0/update-site/`）。

### 版本匹配提醒
`MANIFEST.MF` 里 `Bundle-RequiredExecutionEnvironment: JavaSE-17` 与 pom 的 `JavaSE-17` /
`maven.compiler.release=17` 要与你本地 JDK 和所选 MAT 版本一致：
- 本地 MAT 是 **1.12.0** → 它要求 JDK11。则把 MANIFEST/pom 的 17 改成 **11**，并用 JDK11 构建。
- 想用 JDK17 → 换 MAT **1.14.0** 发行版。

> 你 `/home/pan/mat` 看起来是某个版本，先确认：`cat /home/pan/mat/MemoryAnalyzer.ini | grep -i java` 或看
> `plugins/org.eclipse.mat.api_*.jar` 的版本号，据此对齐 JDK 与 BREE。

## 其它可能的坑

### Import-Package: com.sun.net.httpserver 不可见
本服务用 JDK 内置 `com.sun.net.httpserver`。某些 OSGi/JPMS 配置下系统 bundle 未导出该包，
启动报 `package ... not visible`。应对：
- 启动加 `-vmargs --add-exports jdk.httpserver/com.sun.net.httpserver=ALL-UNNAMED`（视情况）；
- 或在 MAT 的 `config.ini` 给系统 bundle 加 `org.osgi.framework.system.packages.extra=com.sun.net.httpserver`；
- 实在不行：HTTP 层换 Netty（不依赖 JDK 内置 server）。这是已知备选（README 待办里也记了）。

### Tycho 版本
pom 用 `tycho.version=4.0.8`（需 Maven 3.9+、JDK17+ 运行 Maven 本身）。
若你的 Maven/JDK 较老，调低 Tycho 版本或升级构建环境。
