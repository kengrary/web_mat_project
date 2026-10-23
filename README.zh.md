# JVMind × MAT — Heapdump 智能分析模块

[![JVMind MAT Heap Dump Analyzer MCP server](https://mcprush.com/badge/jvmind-mat-heap-dump-analyzer-mcp/card.svg)](https://mcprush.com/to/jvmind-mat-heap-dump-analyzer-mcp?ref=badge)

[English](./README.md) | **中文**

> 基于 Eclipse Memory Analyzer (MAT) 内核，为现有 JVMind 平台增加 **Java 堆转储（heapdump）分析能力**：
> 上传 → 解析 → 类 MAT 的 Web 查询 → 领域诊断（连接池泄漏等）→ 自然语言 Agent 诊断。
>
> **当前状态**：Java 侧（解析 + 查询服务）已实测跑通；MCP Server `v0.2.0` 已发布到 PyPI 与官方 MCP Registry；Python 平台串通待开发。详见 [PROJECT-STATUS.md](./PROJECT-STATUS.md)。

---

## 🚀 新人 5 分钟入门

1. **想了解项目全貌** → 读本文件 +「[mat-study/README.md](./mat-study/README.md)」（项目蓝图）。
2. **想知道现在做到哪了** → [PROJECT-STATUS.md](./PROJECT-STATUS.md)（已完成 / 待办 / 踩坑经验）。
3. **是 Python 团队、要接手开发** → [TODO-PYTHON-TEAM.md](./TODO-PYTHON-TEAM.md)（任务清单 + 接口契约 + 验收）。
4. **想把查询服务跑起来** → [query-service/DEPLOY.md](./query-service/DEPLOY.md)（部署 + curl 验证）。
5. **想跑通解析** → [parser-cli/v1-parsehd/README.md](./parser-cli/v1-parsehd/README.md)（零编译，发行版即可）。
6. **想在 Claude/Cursor/VS Code 里分析堆转储** → [mcp-server/README.md](./mcp-server/README.md)（`uvx jvmind-mat-mcp`，本地 stdio）。

---

## ⚡ MCP Server（已发布）

本地 stdio MCP Server，把 MAT 的堆分析能力直接接到 Claude Desktop / Cursor / VS Code。支持主动发现本机 Java 进程并抓取 live 堆转储，也可以直接打开已有 `.hprof` 文件。堆转储不出本机，MAT 辅助服务只绑 `127.0.0.1`。

```bash
uvx jvmind-mat-mcp doctor      # 检查 JDK 21 / MAT 1.17 / 权限
```

| 渠道 | 地址 |
|---|---|
| PyPI | <https://pypi.org/project/jvmind-mat-mcp/> |
| 官方 MCP Registry | `io.github.kengrary/jvmind-mat` |
| MCPRush | <https://mcprush.com/kengrary/jvmind-mat-heap-dump-analyzer-mcp> |
| 使用文档 | [mcp-server/README.md](./mcp-server/README.md) |

能力：`list_java_processes` / `dump_java_heap`（在线抓取）、`open_heapdump`（已有文件）→ `mat_overview` / `mat_histogram` / `mat_dominator` / `mat_threads` / `mat_object` / `mat_path2gc` / `mat_oql` / `mat_leak_suspects` / `mat_diagnose_oom` 等 19 个工具。

要求：Python 3.10+、JDK 21+、Eclipse MAT **1.17.x**（首次使用会自动安装一个小型查询 bundle）。

## Tools

所有分析工具都接收 `open_heapdump` 返回的 `report_id`。

- `open_heapdump` — 打开本地 `.hprof`，后台解析并返回 `report_id`
- `get_heapdump_status` — 查询解析状态、进度和阶段
- `list_heapdumps` — 列出本 MCP 服务已知的堆转储
- `close_heapdump` — 释放堆转储句柄，可选停止 MAT 辅助进程
- `list_java_processes` — 发现本机可 attach 的 Java 进程（pid、主类、命令行；排除 MAT 辅助进程）
- `dump_java_heap` — 预览并抓取 live 堆转储，然后自动开始 MAT 解析（confirm=true 执行）
- `mat_overview` — 堆总览：已用/已提交/存活堆、对象/类/类加载器数量、JDK 版本
- `mat_histogram` — 类直方图，按 shallow / retained / count 排序
- `mat_dominator` — 支配树，列出 retained 最大的对象并支持按 parent 下钻
- `mat_threads` — 线程列表，或下钻线程栈帧和 frame locals
- `mat_threadlocals` — 按 valueClass 或 thread 分组查看 ThreadLocal
- `mat_object` — 检查单个对象的字段、静态字段、入/出引用和 GC Root
- `mat_path2gc` — 对象到 GC Root 的最短路径
- `mat_oql` — 执行 OQL 查询，返回行和可复用的 resultSetId
- `mat_leak_suspects` — 运行 LeakHunter，返回泄漏嫌疑、retained 大小和描述
- `mat_diagnose_oom` — 综合 OOM 诊断：泄漏嫌疑 + 线程 SQL + 支配树分析
- `mat_connection_pools` — 诊断数据库连接池和超时未释放的连接
- `mat_top_consumers` — 按 retained heap 排序的内存占用 Top
- `mat_sql_in_threads` — 在线程栈中查找 SQL，定位 OOM 时的慢查询

---

## 架构一图

```
  用户上传 heapdump
        │
   [Python/FastAPI]  ← 待开发(TODO-PYTHON-TEAM.md)
   上传/队列/进度/鉴权/Agent
        ├── fork ─────────────▶ [parser-cli / V1]  hprof → *.index   ✅已实测
        │                              （写 NFS）
        └── 内网反代 ──────────▶ [query-service]    读 index 提供查询  ✅已跑通
                                  (OSGi bundle, :8090)
                                  histogram/支配树/线程树/OQL
                                  + Leak Suspects/path2gc 等
         共享：NFS(dump 文件) + PostgreSQL(任务/报告)

  本机 MCP 路径（v0.2.0 已发布）：
  Claude Desktop / Cursor / VS Code
        │ stdio
        ▼
   [mcp-server] ──HTTP 127.0.0.1──▶ [query-service :8090]
        └── 缺 index 时自动调用 MAT ParseHeapDump（复用 parser-cli V1 逻辑）
```

---

## 目录结构与文档导航

### 📁 顶层
| 文件 | 内容 |
|---|---|
| [README.zh.md](./README.zh.md) | 本文件，中文总索引 |
| [README.md](./README.md) | English overview（默认） |
| [PROJECT-STATUS.md](./PROJECT-STATUS.md) | 总进度：里程碑、模块状态、待办、**踩坑经验**（必读） |
| [TODO-PYTHON-TEAM.md](./TODO-PYTHON-TEAM.md) | 给 Python 团队的串通任务清单（表/上传/队列/反代/agent + 验收 + 参考物料） |

### 📁 mat-study/ — 设计文档（从研究到落地）
| # | 文档 | 解决什么 |
|---|---|---|
| — | [README.md](./mat-study/README.md) | **项目蓝图入口**（四层架构、决策表、里程碑） |
| 01 | [MAT源码研究与架构设计](./mat-study/01-MAT源码研究与架构设计.md) | MAT 怎么实现、怎么复用；index 文件格式 |
| 02 | [核心算法深挖与落地方案](./mat-study/02-核心算法深挖与落地方案.md) | 支配树/路径/retained 算法；Web 后端怎么搭 |
| 03 | [超越OOM-连接池泄漏排查](./mat-study/03-超越OOM-连接池泄漏等问题排查思路.md) | 堆镜像还能查什么；连接池/线程池/ClassLoader 诊断 |
| 04 | [接口工具化-诊断Agent思路](./mat-study/04-MAT接口工具化-自然语言诊断Agent思路.md) | LLM 怎么驱动 MAT；工具粒度/出参瘦身 |
| 05 | [全链路系统集成设计](./mat-study/05-全链路系统集成设计.md) | 上传/队列/进度/三层软引用防 OOM |
| 06 | [技术选型确认与四大视图API](./mat-study/06-技术选型确认与四大视图API设计.md) | 选型固化 + 四大视图 API 契约 |
| 07 | [与现有JVMind系统对接](./mat-study/07-与现有JVMind系统对接落地方案.md) | 如何接入现有平台（**Python 团队必读**） |
| 08 | [MAT功能盘点与实现缺口](./mat-study/08-MAT功能盘点与实现缺口.md) | 57 个 query 盘点 + 缺口优先级 |
| 09 | [前端实现建议-复刻MAT体验](./mat-study/09-前端实现建议-复刻MAT体验.md) | Web 前端怎么接近 MAT 体验（通用建议）|
| 10 | [MAT-Inspector能力盘点](./mat-study/10-MAT-Inspector能力盘点与实现建议.md) | Inspector 全部能力 + 已实现项 |
| 12 | [Python串通执行计划](./mat-study/12-Python串通执行计划.md) | Python 接入 Java 侧分阶段计划 |
| 13 | [OOM线程级诊断思路](./mat-study/13-OOM线程级诊断思路.md) | OOM 触发线程识别 + SQL 提取（超越 MAT）|
| 14 | [heapdump前端落地设计-对齐真实端点](./mat-study/14-heapdump前端落地设计-对齐真实端点.md) | 前端**可执行**落地（端点映射+异步状态机+集成红线，**前端团队必读**）|

### 📁 parser-cli/ — 解析层（hprof → index）
| 文件 | 内容 |
|---|---|
| [README.md](./parser-cli/README.md) | 解析器总览（V1/V2 两版路线） |
| [MAT-DEPENDENCIES.md](./parser-cli/MAT-DEPENDENCIES.md) | **MAT 依赖怎么拿**（版本↔JDK、三种获取方式、bundle 清单） |
| [v1-parsehd/README.md](./parser-cli/v1-parsehd/README.md) | **V1 方案**（零编译，发行版 ParseHeapDump + 文本进度）★当前用 |
| [v1-parsehd/VERIFICATION.md](./parser-cli/v1-parsehd/VERIFICATION.md) | V1 实测报告（真实 hprof 跑通 + 修了 3 个 bug） |
| `v1-parsehd/mat_parse_runner.py` | 进度解析器（Python，已实测，供 worker 复用） |
| `python-worker-snippet.py` | worker 调解析器的可用片段 |
| `src/.../*.java` | V2 骨架（自写 jar + JSONL 进度，待切换） |

### 📁 query-service/ — 查询层（OSGi bundle，:8090）✅ 已跑通
| 文件 | 内容 |
|---|---|
| [README.md](./query-service/README.md) | 服务总览（端点、SnapshotManager 三层软引用、路线2） |
| [DEPLOY.md](./query-service/DEPLOY.md) | **部署 + 端到端验证**（bundles.info 注册 + -clean；curl 示例）★实操看这个 |
| [EQUINOX-A-VERIFICATION.md](./query-service/EQUINOX-A-VERIFICATION.md) | Equinox 嵌入实测 + CNF 缺陷修正（为什么选路线2） |
| [BUILD-TROUBLESHOOTING.md](./query-service/BUILD-TROUBLESHOOTING.md) | 构建排错（p2 仓库、JDK 版本匹配） |
| `src/.../*.java` (21 个) | 服务实现：core/http/model/task/view/equinox |

### 📁 mcp-server/ — 本地 stdio MCP（✅ v0.2.0 已发布）
| 文件 | 内容 |
|---|---|
| [README.md](./mcp-server/README.md) | 安装、客户端配置、19 个工具、隐私与排错 |
| `src/jvmind_mat_mcp/server.py` | MCP SDK 工具注册入口 |
| `src/jvmind_mat_mcp/engine.py` | 堆转储注册、后台解析、MAT 服务生命周期 |
| `src/jvmind_mat_mcp/vendor/` | 随包分发的 query-service OSGi bundle |
| [Dockerfile](./mcp-server/Dockerfile) | Glama 审核用镜像（不含 MAT/JDK，运行时挂载） |
| `tests/` | 单测 + stdio 协议测试 + 可选真实 MAT 集成测试 |

### 📁 marketplace/ — 市场提交材料（🟡 准备完成，尚未提交）
| 文件 | 内容 |
|---|---|
| [README.md](./marketplace/README.md) | 各平台状态、剩余步骤、质量缺口 |
| [metadata.json](./marketplace/metadata.json) | 统一名称/描述/标签/安装命令 |
| `smithery/manifest.json` | MCPB manifest（Smithery 暂跳过，保留备用） |
| [mcp.so.md](./marketplace/mcp.so.md) / [glama.md](./marketplace/glama.md) / [pulsemcp.md](./marketplace/pulsemcp.md) | 各平台表单文案与步骤 |

---

## 模块速查

| 模块 | 形态 | 状态 | 关键入口文件 |
|---|---|---|---|
| **parser-cli** | V1=发行版命令；V2=独立 jar | ✅ V1 实测可用 | `v1-parsehd/mat_parse_runner.py` |
| **query-service** | OSGi bundle (HTTP :8090) | ✅ 已跑通+实测 | `QueryServiceApp.java` / `QueryHttpServer.java` |
| **mcp-server** | Python stdio MCP（PyPI + 官方 Registry） | ✅ v0.2.0 已发布 | `src/jvmind_mat_mcp/server.py` |
| **marketplace** | 目录提交材料（Smithery 暂跳过） | 🟡 准备完成，待提交 | `marketplace/README.md` |
| **Python 串通** | FastAPI + DB + agent | ⏳ 待开发 | 见 `TODO-PYTHON-TEAM.md` |

---

## 关键事实速记（避免重复踩坑，详见 PROJECT-STATUS.md）
- MAT 依赖不在 Maven Central → 用 Tycho（`eclipse-plugin`）+ p2/Directory，不写 `<dependencies>`。
- 版本↔JDK：**1.17→JDK21**、1.14→JDK17、1.12→JDK11（三处须一致）。
- 部署：jar 放 `plugins/` + `bundles.info` 加一行（auto-start=true）+ **`-clean`** 重启（不是 dropins）。
- 查询服务无鉴权，仅内网；由 Python 反代时带 `dumpDir`（= dump 目录）。
- reopen 需要 hprof + index 都在可随机 seek 的文件系统（NFS 满足）。
- MCP 包名 `jvmind-mat-mcp`，Registry 名 `io.github.kengrary/jvmind-mat`；改完 Java 查询层后必须重跑 `mcp-server/scripts/vendor-bundle.sh`，否则 wheel 里的 bundle 会过期。
- MCP Server 首次使用会把 `com.jvmind.mat.query` 写入 MAT 的 `plugins/` + `bundles.info`；MAT 安装目录必须可写。
- 在线抓取使用 `jmap -dump:live`：只 dump 存活对象，会触发一次 Full GC 并可能暂停目标 JVM；默认输出到服务进程当前目录，可用 `JVMIND_MCP_DUMP_DIR` 或工具的 `output_dir` 覆盖。
- Smithery 暂跳过（网页端只有远程 URL 入口，CLI 创建接口已移除）；MCPB 材料保留备用。Docker 镜像只用于目录审核，真实分析仍需本机 MAT + JDK 21。

---

## 技术栈
- **解析/查询**：Java 21 + Eclipse MAT 1.17（OSGi/Equinox）
- **MCP Server**：Python 3.10+ / MCP Python SDK 2.x / `uv` / stdio
- **主控/上传/Agent**：Python + FastAPI（现有 JVMind 平台）
- **存储**：NFS（dump 文件）+ PostgreSQL（任务/报告，双进程共享）
- **分发**：PyPI + 官方 MCP Registry；mcp.so/Glama/PulseMCP 材料已准备，Smithery 暂跳过
- **许可证**：本项目代码为 MIT（见 [LICENSE](./LICENSE)）；MAT 为 EPL-2.0（复用其 bundle，注意分发声明）
