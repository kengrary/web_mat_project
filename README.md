# JVMind × MAT — Heap Dump Analysis

**English** | [中文](./README.zh.md)

> Java heap dump analysis built on the Eclipse Memory Analyzer (MAT) engine:
> upload → parse → MAT-like web queries → domain diagnostics (connection-pool
> leaks and more) → natural-language agent diagnosis.
>
> **Status**: the Java parser and query service are tested. MCP Server `v0.1.1`
> is published on PyPI and the official MCP Registry. Python platform
> integration is still in progress. See [PROJECT-STATUS.md](./PROJECT-STATUS.md).

---

## Quick start

1. **Project overview** → this file + [`mat-study/README.md`](./mat-study/README.md)
   (the design blueprint; Chinese).
2. **Current progress** → [PROJECT-STATUS.md](./PROJECT-STATUS.md) (completed work,
   remaining tasks, lessons learned).
3. **Python team onboarding** → [TODO-PYTHON-TEAM.md](./TODO-PYTHON-TEAM.md)
   (task list, API contract, acceptance criteria).
4. **Run the query service** → [query-service/DEPLOY.md](./query-service/DEPLOY.md)
   (deploy + curl verification).
5. **Run the parser** → [parser-cli/v1-parsehd/README.md](./parser-cli/v1-parsehd/README.md)
   (no build required; uses the MAT distribution).
6. **Analyze heap dumps from Claude/Cursor/VS Code** →
   [mcp-server/README.md](./mcp-server/README.md) (`uvx jvmind-mat-mcp`, local stdio).

---

## MCP Server (published)

A local stdio MCP server that exposes MAT heap analysis to Claude Desktop,
Cursor, and VS Code. Heap dumps never leave the machine; the MAT helper binds to
`127.0.0.1` only.

```bash
uvx jvmind-mat-mcp doctor      # checks JDK 21 / MAT 1.17 / permissions
```

| Channel | Location |
|---|---|
| PyPI | <https://pypi.org/project/jvmind-mat-mcp/> |
| Official MCP Registry | `io.github.kengrary/jvmind-mat` |
| MCPRush | <https://mcprush.com/kengrary/jvmind-mat-heap-dump-analyzer-mcp> |
| Documentation | [mcp-server/README.md](./mcp-server/README.md) |

Capabilities: `open_heapdump` → `mat_overview` / `mat_histogram` /
`mat_dominator` / `mat_threads` / `mat_object` / `mat_path2gc` / `mat_oql` /
`mat_leak_suspects` / `mat_diagnose_oom` and more — 17 tools in total.

Requirements: Python 3.10+, JDK 21+, Eclipse MAT **1.17.x** (a small query
bundle is installed into MAT on first use).

## Tools

All analysis tools take the `report_id` returned by `open_heapdump`.

- `open_heapdump` — open a local `.hprof`, start background indexing, return a `report_id`
- `get_heapdump_status` — poll parse status, progress, and phase
- `list_heapdumps` — list heap dumps known to this MCP server
- `close_heapdump` — release a heap dump handle and optionally stop the MAT helper
- `mat_overview` — heap overview: used/committed/live heap, object/class/classloader counts, JDK version
- `mat_histogram` — class histogram sorted by shallow, retained, or object count
- `mat_dominator` — dominator tree of the largest retained objects, with parent drill-down
- `mat_threads` — list threads or drill into stack frames and frame locals
- `mat_threadlocals` — inspect ThreadLocal values grouped by value class or thread
- `mat_object` — inspect a single object's fields, statics, references, and GC root
- `mat_path2gc` — shortest path from an object to a GC root
- `mat_oql` — run an OQL query and return rows plus a reusable result set id
- `mat_leak_suspects` — run LeakHunter and return suspects with retained sizes and descriptions
- `mat_diagnose_oom` — composite OOM diagnosis: leak suspects + SQL in threads + dominator analysis
- `mat_connection_pools` — diagnose database connection pools and leaked connections
- `mat_top_consumers` — top memory consumers by retained heap
- `mat_sql_in_threads` — find SQL statements in thread stacks

---

## Architecture

```
  User uploads a heap dump
        │
   [Python/FastAPI]  ← in progress (TODO-PYTHON-TEAM.md)
   upload / queue / progress / auth / agent
        ├── fork ─────────────▶ [parser-cli / V1]  hprof → *.index   ✅ tested
        │                              (writes to NFS)
        └── reverse proxy ────▶ [query-service]    queries over indexes  ✅ tested
                                  (OSGi bundle, :8090)
                                  histogram / dominators / threads / OQL
                                  + Leak Suspects / path2gc etc.
         Shared: NFS (dump files) + PostgreSQL (tasks/reports)

  Local MCP path (v0.1.1 published):
  Claude Desktop / Cursor / VS Code
        │ stdio
        ▼
   [mcp-server] ──HTTP 127.0.0.1──▶ [query-service :8090]
        └── runs MAT ParseHeapDump when index files are missing
            (reuses the parser-cli V1 logic)
```

---

## Repository layout

### Top level

| File | Contents |
|---|---|
| [README.md](./README.md) | English overview (this file) |
| [README.zh.md](./README.zh.md) | Chinese overview |
| [PROJECT-STATUS.md](./PROJECT-STATUS.md) | Milestones, module status, TODOs, lessons learned |
| [TODO-PYTHON-TEAM.md](./TODO-PYTHON-TEAM.md) | Python integration task list, API contract, acceptance |

### `mat-study/` — design documents (Chinese)

| # | Document | What it covers |
|---|---|---|
| — | [README.md](./mat-study/README.md) | Design blueprint (four-layer architecture, decisions, milestones) |
| 01 | [MAT source study and architecture](./mat-study/01-MAT源码研究与架构设计.md) | How MAT works and how to reuse it; index file format |
| 02 | [Core algorithms and implementation](./mat-study/02-核心算法深挖与落地方案.md) | Dominator tree, paths, retained size; web backend design |
| 03 | [Beyond OOM: connection-pool leaks](./mat-study/03-超越OOM-连接池泄漏等问题排查思路.md) | What else a heap dump can answer; pools, thread pools, classloaders |
| 04 | [Tooling the MAT API for agents](./mat-study/04-MAT接口工具化-自然语言诊断Agent思路.md) | How an LLM drives MAT; tool granularity and output trimming |
| 05 | [End-to-end integration design](./mat-study/05-全链路系统集成设计.md) | Upload, queue, progress, three-layer soft-reference cache |
| 06 | [Technology choices and four-view API](./mat-study/06-技术选型确认与四大视图API设计.md) | Finalized choices and the four-view API contract |
| 07 | [Integrating with JVMind](./mat-study/07-与现有JVMind系统对接落地方案.md) | How to connect to the existing platform (required for the Python team) |
| 08 | [MAT feature inventory and gaps](./mat-study/08-MAT功能盘点与实现缺口.md) | 57 queries reviewed; gap priorities |
| 09 | [Frontend implementation guidance](./mat-study/09-前端实现建议-复刻MAT体验.md) | Making the web UI feel like MAT |
| 10 | [MAT Inspector capabilities](./mat-study/10-MAT-Inspector能力盘点与实现建议.md) | Inspector features and what is already implemented |
| 12 | [Python integration plan](./mat-study/12-Python串通执行计划.md) | Phased plan for the Python side |
| 13 | [Thread-level OOM diagnosis](./mat-study/13-OOM线程级诊断思路.md) | Finding the OOM-triggering thread and its SQL |
| 14 | [Heap dump frontend design](./mat-study/14-heapdump前端落地设计-对齐真实端点.md) | Endpoint mapping, async state machine, integration rules |

### `parser-cli/` — parsing layer (hprof → index)

| File | Contents |
|---|---|
| [README.md](./parser-cli/README.md) | Parser overview (V1/V2 routes) |
| [MAT-DEPENDENCIES.md](./parser-cli/MAT-DEPENDENCIES.md) | How to obtain MAT dependencies, version/JDK matrix, bundle list |
| [v1-parsehd/README.md](./parser-cli/v1-parsehd/README.md) | **V1** (zero build; MAT distribution + text progress) — current |
| [v1-parsehd/VERIFICATION.md](./parser-cli/v1-parsehd/VERIFICATION.md) | V1 verification report (real hprof, three bugs fixed) |
| `v1-parsehd/mat_parse_runner.py` | Progress parser (Python; reusable by the worker) |
| `python-worker-snippet.py` | Worker snippet for calling the parser |
| `src/.../*.java` | V2 skeleton (self-built jar + JSONL progress; not active) |

### `query-service/` — query layer (OSGi bundle, :8090) ✅

| File | Contents |
|---|---|
| [README.md](./query-service/README.md) | Service overview (endpoints, SnapshotManager, route 2) |
| [DEPLOY.md](./query-service/DEPLOY.md) | **Deploy + end-to-end verification** (bundles.info + `-clean`, curl examples) |
| [EQUINOX-A-VERIFICATION.md](./query-service/EQUINOX-A-VERIFICATION.md) | Embedded Equinox test and CNF fix (why route 2) |
| [BUILD-TROUBLESHOOTING.md](./query-service/BUILD-TROUBLESHOOTING.md) | Build troubleshooting (p2 repositories, JDK versions) |
| `src/.../*.java` (21 files) | Service implementation: core/http/model/task/view/equinox |

### `mcp-server/` — local stdio MCP (✅ v0.1.1 published)

| File | Contents |
|---|---|
| [README.md](./mcp-server/README.md) | Install, client config, 17 tools, privacy, troubleshooting |
| `src/jvmind_mat_mcp/server.py` | MCP SDK tool registration |
| `src/jvmind_mat_mcp/engine.py` | Heap dump registry, background parsing, MAT lifecycle |
| `src/jvmind_mat_mcp/vendor/` | Query-service OSGi bundle shipped with the package |
| [Dockerfile](./mcp-server/Dockerfile) | Glama review image (MAT/JDK not included; mount at runtime) |
| `tests/` | Unit tests, stdio protocol tests, optional real-MAT integration tests |

### `marketplace/` — directory submission pack (🟡 prepared, not submitted)

| File | Contents |
|---|---|
| [README.md](./marketplace/README.md) | Platform status, remaining steps, quality gaps |
| [metadata.json](./marketplace/metadata.json) | Shared name, description, tags, install command |
| `smithery/manifest.json` | MCPB manifest (Smithery skipped, kept for later) |
| [mcp.so.md](./marketplace/mcp.so.md) / [glama.md](./marketplace/glama.md) / [pulsemcp.md](./marketplace/pulsemcp.md) | Form copy and steps per platform |

---

## Module status

| Module | Form | Status | Entry point |
|---|---|---|---|
| **parser-cli** | V1 = MAT command; V2 = standalone jar | ✅ V1 tested | `v1-parsehd/mat_parse_runner.py` |
| **query-service** | OSGi bundle (HTTP :8090) | ✅ tested | `QueryServiceApp.java` / `QueryHttpServer.java` |
| **mcp-server** | Python stdio MCP (PyPI + official Registry) | ✅ v0.1.1 published | `src/jvmind_mat_mcp/server.py` |
| **marketplace** | Directory copy (Smithery skipped) | 🟡 prepared, pending submission | `marketplace/README.md` |
| **Python integration** | FastAPI + DB + agent | ⏳ in progress | `TODO-PYTHON-TEAM.md` |

---

## Key facts (avoid re-learning these)

- MAT dependencies are not on Maven Central → use Tycho (`eclipse-plugin`) with a
  p2 repository or a local Directory target; do not add `<dependencies>`.
- Version ↔ JDK: **1.17 → JDK 21**, 1.14 → JDK 17, 1.12 → JDK 11 (keep all
  three places in sync).
- Deployment: copy the jar into `plugins/`, add a line to `bundles.info`
  (auto-start=true), and restart with **`-clean`** (not `dropins/`).
- The query service has no authentication and is intranet-only. The Python proxy
  injects `dumpDir` (= the dump directory).
- Reopening a dump requires both the hprof and index files on a seekable
  filesystem (NFS works).
- MCP package `jvmind-mat-mcp`; Registry name `io.github.kengrary/jvmind-mat`.
  After changing the Java query layer, rerun
  `mcp-server/scripts/vendor-bundle.sh`, otherwise the wheel ships a stale bundle.
- On first use the MCP server writes `com.jvmind.mat.query` into MAT's
  `plugins/` and `bundles.info`; the MAT install must be writable.
- Smithery is skipped for now: its web UI only exposes remote HTTPS URLs and
  the CLI server-creation endpoint was removed. MCPB artifacts are kept for a
  later retry. The Docker image is for directory review; real analysis still
  needs MAT + JDK 21 on the host.

---

## Tech stack

- **Parsing/query**: Java 21 + Eclipse MAT 1.17 (OSGi/Equinox)
- **MCP server**: Python 3.10+ / MCP Python SDK 2.x / `uv` / stdio
- **Platform/agent**: Python + FastAPI (the existing JVMind platform)
- **Storage**: NFS (dump files) + PostgreSQL (tasks/reports)
- **Distribution**: PyPI + official MCP Registry; mcp.so, Glama, and PulseMCP
  materials are prepared; Smithery is skipped
- **License**: MIT for this project (see [LICENSE](./LICENSE)); Eclipse MAT is
  EPL-2.0 and is not redistributed
