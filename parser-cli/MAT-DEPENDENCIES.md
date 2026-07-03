# MAT 依赖清单与获取方式

> 面向 parser-cli（及后续 Java MAT Query 服务）落地时"如何拿到 MAT 依赖"。
> 内容基于对 MAT 源码各 bundle `MANIFEST.MF` 与官方 target definition 的实际核对。

---

## 0. 最重要的两个结论（先看这个）

1. **MAT 解析核心（api / report / parser / hprof）零第三方库依赖**——
   核对过：没有 ASM、Lucene、Guava 等，也没有内嵌 jar（无 `Bundle-ClassPath` 引入外部库）。
   它只依赖**自己这 4 个 bundle** + **少量 Eclipse 平台 runtime bundle**。
2. **headless 解析不需要 SWT/UI**——hprof bundle 对 `org.eclipse.swt`/`jface`/`ui` 的 import 全是
   `resolution:=optional`。所以可以彻底排除一大堆 GUI 依赖。

> 因此"MAT 依赖"= 4 个 MAT bundle + 一小撮 Equinox/core runtime bundle，**不是**整个 Eclipse RCP。

---

## 1. 依赖层级（核对 MANIFEST 的 Require-Bundle）

```
org.eclipse.mat.hprof   ── requires ──▶ org.eclipse.mat.parser
                                        org.eclipse.mat.report
                                        org.eclipse.core.runtime
                                        org.eclipse.core.commands
org.eclipse.mat.parser  ── requires ──▶ org.eclipse.mat.report
                                        org.eclipse.mat.api (reexport)
                                        org.eclipse.core.runtime
org.eclipse.mat.api     ── requires ──▶ org.eclipse.mat.report (reexport)
                                        org.eclipse.core.runtime
                                        org.eclipse.core.resources
org.eclipse.mat.report  ── requires ──▶ org.eclipse.core.runtime
                                        org.eclipse.core.resources
```

### 1.1 必需的 MAT bundle（4 个）
| Bundle | 作用 | 当前版本 | 最低 Java |
|---|---|---|---|
| `org.eclipse.mat.api` | ISnapshot、query 框架、内置 inspections | 1.17.x | **JavaSE-21** |
| `org.eclipse.mat.report` | query 注解/注册体系、collect 压缩类、报告引擎 | 1.17.x | JavaSE-17 |
| `org.eclipse.mat.parser` | 解析管线、index 读写、支配树 | 1.17.x | JavaSE-1.8 |
| `org.eclipse.mat.hprof` | hprof 两遍解析器 | 1.17.x | JavaSE-1.8 |

> 取最高要求：**最新 MAT 需要 JDK 21**（api bundle）。若想用 JDK 17，需用稍旧的 MAT 版本（如 1.14.x，那时 api 还是 JavaSE-17）。

### 1.2 排除的 MAT bundle（GUI/无关，不要）
`org.eclipse.mat.ui` / `.ui.rcp` / `.ui.help` / `.chart` / `.chart.ui` / `.jdt` / `.dtfj` / `.ibmdumps` / `.jruby.resolver` / `.tests`

### 1.3 需要的 Eclipse 平台 runtime bundle（headless 最小集）
MAT 用 Eclipse 的 extension registry 发现解析器，所以需要 core runtime 一族。**headless 最小集**大致是：
```
org.eclipse.osgi                       # OSGi/Equinox 框架本体
org.eclipse.osgi.services
org.eclipse.equinox.common             # core.runtime 的基础
org.eclipse.equinox.registry           # ★ extension registry（解析器发现的关键）
org.eclipse.equinox.preferences
org.eclipse.equinox.app                # IApplication（方式A 跑 application 时）
org.eclipse.core.runtime               # Require-Bundle 直接声明
org.eclipse.core.contenttype           # content-type 检测（识别 hprof）
org.eclipse.core.jobs
org.eclipse.core.resources             # api/report 声明依赖
org.eclipse.core.commands              # hprof 声明依赖
org.eclipse.core.expressions           # resources 传递依赖
org.osgi.service.prefs
```
> 这些都来自 Eclipse Platform，发行版/p2 里都有。实际"最小集"以 OSGi 解析时的报错为准——
> 缺哪个补哪个；用方式 A（起 Equinox）时框架会自动解析依赖闭包。

---

## 2. 三种获取方式（按推荐度排序）

### 方式①：从 MAT 发行版 zip 直接取 jar（最快，推荐起步）★
MAT 发行版（standalone RCP）里 `plugins/` 目录就是一堆现成的 OSGi bundle jar，直接捞出来用。

下载来源（核对过官方页面）：
- 最新版下载页：`https://eclipse.dev/mat/download/`
- 历史版本：`https://eclipse.dev/mat/download/previous/`
- 当前主线 1.17.x；若要 JDK17 用 **1.14.0**（`MemoryAnalyzer-1.14.0.202303151743-linux.gtk.x86_64.zip`）。
- 文件名形如：`MemoryAnalyzer-<ver>.<date>-linux.gtk.x86_64.zip`（约 70~90MB）。

```bash
# 1) 下载 MAT standalone（Linux 版，约 70~90MB）
#    官方下载页: https://eclipse.dev/mat/download/  (或 downloads)
#    形如: MemoryAnalyzer-1.15.0.YYYYMMDD-linux.gtk.x86_64.zip
wget "<MAT 发行版 url>" -O mat.zip
unzip mat.zip -d mat-dist

# 2) 需要的 jar 都在 mat-dist/mat/plugins/
ls mat-dist/mat/plugins/ | grep -E "org.eclipse.mat.(api|report|parser|hprof)_"
ls mat-dist/mat/plugins/ | grep -E "org.eclipse.(osgi|equinox|core)"

# 3) 装进本地 Maven 仓（坐标随便定，与 parser-cli/pom.xml 对齐即可）
mvn install:install-file \
  -Dfile=mat-dist/mat/plugins/org.eclipse.mat.api_1.17.0.xxxx.jar \
  -DgroupId=org.eclipse.mat -DartifactId=org.eclipse.mat.api -Dversion=1.15.0 -Dpackaging=jar
# 对 report/parser/hprof 重复
```
- 优点：bundle 已编译、版本自洽、含 plugin.xml（extension 声明完整）。
- 适合：方式 A（嵌 Equinox）—— 直接把 `plugins/` 当 `osgi.install.area`，框架自动解析全部依赖。

### 方式②：用 Tycho 从源码构建（要可控/要改源码时）
MAT 用 **Maven Tycho** 构建（不是普通 Maven），从 p2 仓库拉依赖。
```bash
git clone https://github.com/eclipse-mat/mat.git
cd mat
# parent/pom.xml + *.target 定义了 p2 仓库与目标平台
mvn -f parent/pom.xml clean install   # 需联网拉 p2，首次较慢
```
- 优点：能改 MAT 源码、产出与源码一致的 bundle。
- 缺点：Tycho 构建重、需联网 p2、对环境敏感。
- 适合：需要给连接池等领域诊断**改/扩 MAT 源码**时。

### 方式③：p2/maven 镜像（CI 自动化）
- p2 update site：`https://download.eclipse.org/mat/<version>/update-site/`
- 用 `p2-maven-plugin` 或 Tycho 的 target platform 把 p2 bundle 转成 Maven 依赖，供 CI 复用。
- 适合：团队 CI 固定版本、可重复构建。

---

## 3. 两种运行模式对依赖的不同要求

| | 方式 A：嵌 Equinox（推荐） | 方式 B：直连 SnapshotFactory |
|---|---|---|
| 需要的 jar | 4 MAT bundle + Equinox/core runtime 全套 | 4 MAT bundle + core runtime（registry 仍需初始化）|
| extension registry | OSGi 框架自动建立 ✅ | 需手工初始化（脱离 OSGi 时 registry 为空 → 解析器发现失败）|
| 部署形态 | 一个 `plugins/` 目录 + launcher | classpath 平铺所有 jar |
| 稳定性 | 高（官方支持的运行方式）| 较低（需绕过 OSGi 机制，有坑）|
| 建议 | **生产用这个** | 仅用于快速验证/特殊场景 |

> 结论：**用方式 A**。把 MAT 发行版的整个 `plugins/` 目录作为 OSGi install area，
> 起 Equinox 后框架自动解析依赖闭包——你几乎不用手工挑"最小依赖集"，省心且不易漏。

---

## 4. parser-cli/pom.xml 里的占位坐标怎么填

`pom.xml` 里用了占位坐标 `org.eclipse.mat:org.eclipse.mat.api:1.15.0` 等。三种填法：

```xml
<!-- 填法1：install-file 进本地仓后，坐标对上即可（见方式①第3步）-->
<dependency>
  <groupId>org.eclipse.mat</groupId>
  <artifactId>org.eclipse.mat.api</artifactId>
  <version>1.15.0</version>
</dependency>

<!-- 填法2：system scope 直接指向发行版里的 jar（仅本地构建，不可发布）-->
<dependency>
  <groupId>org.eclipse.mat</groupId>
  <artifactId>org.eclipse.mat.api</artifactId>
  <version>1.15.0</version>
  <scope>system</scope>
  <systemPath>${project.basedir}/lib/org.eclipse.mat.api.jar</systemPath>
</dependency>
```

> 注意：方式 A 下，MAT bundle 其实是被 **Equinox 在运行时从 install area 加载**的，
> 编译期 pom 依赖只为编译我们调用 MAT API 的代码（`SnapshotFactory`/`ISnapshot` 等）。
> 即"**编译期 classpath 有 MAT jar 能编过，运行期由 Equinox 真正加载并提供 registry**"。

---

## 4.5 方式 A 的"零接线"捷径：直接用发行版 launcher（最省心）

如果不想自己写 EclipseStarter 接线，发行版自带的命令行 launcher 已经是一个**配好的 Equinox**，
能直接 headless 跑 MAT 的解析 application：
```bash
# 解压后 mat/ 目录里有 MemoryAnalyzer 可执行 + plugins/ + ParseHeapDump.sh
/opt/mat/ParseHeapDump.sh /nfs/heapdumps/hd_xxx/app.hprof
#   等价于:
/opt/mat/MemoryAnalyzer -consoleLog -nosplash \
    -application org.eclipse.mat.api.parse \
    /nfs/heapdumps/hd_xxx/app.hprof \
    -vmargs -Xmx32g
# 产物: 与 hprof 同目录的全套 *.index
```
- 优点：**零依赖接线**，发行版自带的 Equinox 已含全部 runtime bundle，extension registry 现成。
- 代价：进度走 MAT 的文本 `ConsoleProgressListener`，不是我们的 JSONL（需在 Python 侧解析文本进度，或接受较粗的进度）。
- 取舍：
  - **想要结构化 JSONL 进度** → parser-cli 方式 A（把发行版 `plugins/` 当 install area，起 Equinox 后调我们的 `HeapDumpParser`）。
  - **想最快跑通、进度要求不高** → 直接 fork 发行版 `ParseHeapDump.sh`，parser-cli 退化为"包装 + 文本进度解析"。

> 实务建议：**第一版先用 `ParseHeapDump.sh` 跑通端到端**（Python 解析它的文本进度行，文档10 里 MAT 的
> `Subtask: ... DominatorTree ...` 等输出可粗略映射阶段），验证链路通后再换 parser-cli 方式 A 拿精细 JSONL 进度。

---

## 5. 许可证

- MAT：**EPL-2.0**。复用/链接/再分发其 bundle 没问题；分发时随附 EPL 声明，若修改源码有源码义务。
- Eclipse 平台 runtime bundle：同为 EPL-2.0。

---

## 6. 落地检查清单

- [ ] 选定 MAT 版本（决定最低 JDK：1.17.x→JDK21，1.14.x→JDK17）。
- [ ] 下载发行版 zip，确认 `plugins/` 里有 api/report/parser/hprof 4 个 jar。
- [ ] 决定运行模式（建议 A）。A：保留整个 `plugins/` 作 install area；B：挑依赖闭包平铺。
- [ ] MAT 4 个 jar 装进本地仓 / system scope，对齐 `parser-cli/pom.xml` 坐标。
- [ ] JDK 版本与 MAT 最低要求匹配（解析进程单独设 `-Xmx` 大堆）。
- [ ] `mvn -f parser-cli/pom.xml clean package` 编过 → 用一个小 hprof 跑 `--mode direct` 验证产出 index。
- [ ] 接线 `EquinoxLauncher`（方式 A）→ 用大 hprof 验证 + JSONL 进度。
