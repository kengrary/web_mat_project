# AGENTS.md

JVMind × Eclipse MAT heapdump-analysis module. Companion Python/FastAPI workspace lives at `/home/pan/react_agent_project` (public gateway, auth, DB, reverse proxy, agent tools).

## What is what

- `query-service/` — primary code. OSGi bundle (`com.jvmind.mat.query`) that runs inside a local Eclipse MAT 1.17 install and exposes JSON HTTP endpoints on `:8090`. **This is where 99% of edits happen.**
- `parser-cli/` — hprof→MAT-index parser. V1 (production): `v1-parsehd/mat_parse_runner.py` shells out to MAT's `ParseHeapDump.sh` (zero Java build). V2 `src/` skeleton is unbuildable (MAT not on Maven Central); ignore unless actively working on it.
- `mcp-server/` — Python stdio MCP server (`jvmind-mat-mcp`, PyPI + official MCP Registry) wrapping the query-service. Ships `com.jvmind.mat.query-<version>.jar` under `src/jvmind_mat_mcp/vendor/`; after any Java query-service change, rerun `mcp-server/scripts/vendor-bundle.sh`. Tests: `cd mcp-server && .venv/bin/pytest -q` (see README).
- `mat-study/` — 14 Chinese design docs (numbered `01-` … `14-`, skips `11`). Authoritative architecture/API rationale.
- `web-prototype/` — single-file `index.html` vanilla-JS UI mock; no build, no deps.
- `uploads/` — docs only (Java integration guide, old Vue refactor draft). **Not** a runtime upload directory.
- `mat-src/` — 114 MB upstream Eclipse MAT source copy for code-reading reference. Excluded from git; re-download with `git clone https://github.com/eclipse-mat/mat.git` if missing. This MUST be kept at the same major.minor as the installed MAT at `/home/pan/mat` (currently 1.17.x nightly); after upgrading MAT, re-clone/update mat-src so method signatures match what's running — prefer reading mat-src for business logic (e.g. how LeakHunterQuery builds its SectionSpec) and `javap` against `$MAT/plugins/org.eclipse.mat.*_*.jar` for verifying exact public signatures.

Top-level docs: `MEMORY-SNAPSHOT.md` (cheapest context reload) → `README.md` → `IMPLEMENTATION_GUIDE.md` (API contract bible) → `EXECUTION_PLAN.md` (rollout phases) → `PROJECT-STATUS.md`.

## Build / deploy (query-service)

- **JDK 21 mandatory** (MAT 1.17 requires it; `MANIFEST.MF` BREE, `pom.xml` `maven.compiler.release`, and `query-service.target` `executionEnvironment` all pinned to `JavaSE-21`). Maven ≥ 3.9.
- Build: `mvn -f query-service/pom.xml clean package` (no top-level build; packaging is `eclipse-plugin` via Tycho 4.0.8, target platform is a Directory locator pointing at `/home/pan/mat`).
- Output: `query-service/target/com.jvmind.mat.query-0.1.0.jar`.
- **Deploy into the local MAT install (route-2 only — route-1 standalone is dead):**
  1. `cp query-service/target/com.jvmind.mat.query-0.1.0.jar /home/pan/mat/plugins/`
  2. Append one line to `/home/pan/mat/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`:
     `com.jvmind.mat.query,0.1.0,plugins/com.jvmind.mat.query-0.1.0.jar,4,true`
  3. Restart with `-clean`:
     `./MemoryAnalyzer -consoleLog -nosplash -clean -application com.jvmind.mat.query.app -vmargs -Xmx16g -DMAT_QUERY_PORT=8090`
- **Do NOT drop into `dropins/`** — `pom.xml` comment mentioning dropins is stale; MAT uses `bundles.info` (simpleconfigurator).
- Ignore `Main.java` and `equinox/EquinoxBootstrap.java` — abandoned route-1 (standalone jar) dead code that hits OSGi `ClassNotFoundException`.

## Runtime config (all via `-D` system property or same-named env var; sys-prop wins)

| Key | Default | Purpose |
|---|---|---|
| `MAT_QUERY_BIND` | `127.0.0.1` | Bind address (intranet only in prod) |
| `MAT_QUERY_PORT` | `8090` | Listen port |
| `MAT_QUERY_THREADS` | `16` | HTTP worker threads |
| `MAT_QUERY_MAX_OPEN` | `4` | Max concurrently open snapshots (OOM guard) |
| `MAT_QUERY_HEAP_PCT` | `85` | MemoryWatcher proactive-dispose threshold (%) |
| `MAT_QUERY_TASK_THREADS` | `2` | Async heavy-task pool size |
| `MAT_QUERY_TASK_TIMEOUT` | `300` | Heavy-task timeout (seconds) |
| `MAT_QUERY_CACHE` | `true` | Whether to disk-cache heavy query results (leak-suspects/retained-set/merge-paths/diagnose-oom). `false` disables. |
| `MAT_QUERY_CACHE_DIR` | `<dumpDir>/.query-cache` | Override cache directory; default is a hidden subdir of each dumpDir (lifetime tied to the dump). |
| `MAT_QUERY_CORS` | (unset) | CORS origin; **never set in prod** — dev only for `web-prototype` direct-to-:8090 |

Python-side env vars (`DATABASE_URL`, `HEAPDUMP_STORAGE_ROOT`, `MAT_QUERY_SERVICE_URL`, `MAT_HOME`, `MAT_PARSE_XMX`, per-endpoint timeouts) live in the Python workspace's own config; don't document them here.

## Coding conventions / landmines

**`com.jvmind.mat.query.model.Json` builder** (agents get this wrong constantly):

- `Json.obj()` / `Json.arr()` start a builder. Chain `.kv(...)` / `.item(...)`.
- `.endObj()` / `.endArr()` return the fully-built **`String`**, NOT a `Json`. Do NOT assign the result to a `Json` variable and keep chaining.
- To nest an unfinished builder, pass the `Json` object directly to `.item(Json)` or `.kv(String, Json)` — they auto-close the nested builder. **Do not call `.endObj()` yourself first** (that returns String, type mismatch; if you somehow had a closed one it would emit `}}`).
- `ResultRenderer.render(...)` returns a JSON **`String`**. Embed it with `.raw(key, renderedString)`. Using `.kv(key, s)` double-escapes quotes.
- Keep this class minimal; do not add Jackson/Gson.

**`ResultRenderer` must cover the full `IResult` hierarchy** (in this order, before the `kind:raw` fallback):

1. `IResultTable` → table
2. `IResultTree` → tree (one-level lazy; no stable cross-request node ids — deep expansion is a fresh query keyed on `objectId`)
3. `TextResult` → text
4. `CompositeResult` → composite (recurse entries)
5. **`org.eclipse.mat.report.SectionSpec`** → composite-like (iterate `getChildren()`, recurse; emit `name`/`status`)
6. **`org.eclipse.mat.report.QuerySpec`** → unwrap `getResult()` and recurse; surface `getName()` and `getCommand()` (drill-down command string)

Leakhunter (and other MAT report queries) return `SectionSpec` at the top level; missing branches 5–6 produces the useless `{"kind":"raw","value":"org.eclipse.mat.report.SectionSpec@…"}`.

**`/leak-suspects` 返回 shape**（由 `DiagnosticsView.leakSuspects` 专用包装，不是 ResultRenderer 直接吐出的通用形态）：
`{kind:"leak-suspects", suspects:[{name,className,instanceCount,retainedBytes,description,preferredObjectId}], report:{kind:"section", parts:[…]}}`。
前端/Agent 优先读 `suspects[]`（紧凑、可直接渲染卡片/给 LLM 读）；要钻取细节（子表、钻取命令等）再读 `report`。Python 侧 `mat_leak_suspects` agent 工具遍历 `data["suspects"]`，不要遍历 `parts`。

**表格/树单元格的 retained 近似约定**：MAT 用负 `long` 编码"approximate lower bound"（支配树 O(1) 的 `getMinRetainedSize`），`ResultRenderer` 在列名包含 "retained" 时自动 `abs()` 并在该行加 `retainedApprox:true`。前端对 `retainedApprox:true` 的 retained 数字请显示 "≈"。`cells[]` 仍是字符串数组（保持向后兼容）；列元数据额外带 `retained:boolean`。


**HTTP server is JDK built-in `com.sun.net.httpserver.HttpServer`**, not Spring/Netty/Jetty. If OSGi hides the package (CNF on startup), add `org.osgi.framework.system.packages.extra=com.sun.net.httpserver` to `/home/pan/mat/configuration/config.ini`.

**Two OOM endpoints — don't mix up:**

- `GET /oom-diagnosis` — sync, lightweight, used by the overview OOM banner.
- `GET /diagnose-oom` — async heavy composite (OOM culprit thread + SQL risk + Leak Suspects); returns `{taskId}`, poll `GET /tasks/{id}`.

Heavy queries running via `TaskManager` (dedicated bounded pool, cooperative cancel, disk-cache + SoftReference-cached results; disk cache at `<dumpDir>/.query-cache/<kind>-<argsSha256>.json` survives restarts and is auto-invalidated when hprof mtime is newer): `leakhunter` → `/leak-suspects`, `show_retained_set` → `/retained-set`, `merge_shortest_paths` → `/merge-paths`, composite → `/diagnose-oom`. Cache-hit tasks return with `taskId` prefix `c-` and status `DONE` immediately (skip thread pool + snapshot open). Disable with `-DMAT_QUERY_CACHE=false` for development/benchmarking.

**MAT-API quirks:**

- Approximate retained size = `ISnapshot.getMinRetainedSize()` (cheap, O(1) via dominator tree). Exact retained = calculate retained set (full mark-sweep, expensive, async only).
- `getHistogram()` returns entries with `count == 0` for uninstantiated classes/interfaces — that's normal, not a bug.
- **必填 ID 字段（`thread` / `parent` / 任何 objectId 形参）必须用 `Query.parseObjectId`，并自行 400**：它接受十进制整数与 `0x...` 前缀 hex。`Query.intOr` 只用于可选参数（`top`/`offset`/`thresholdMs`），非法值会**静默回退默认**——绝不能用在必填 ID 上，否则 LLM 拼错参数名也只会默默返回错误结果。

  > **变更（0.1.0 → 0.2.0）**：原「≤8 字符裸 hex」分支已移除。所有 ≤8 字符的纯数字输入（如 `"20"`、`"100"`）此前会被静默当 hex 解析（`"20"` → 32），现统一走十进制。MAT 报告里的地址请带 `0x` 前缀。breaking change：把裸 hex 当 thread id 传的工具/脚本会从「silent 错返回空 frames」变成「400 invalid object id」——这正是预期行为。
  >
  > **TODO（已知 inconsistency，留待 0.3.0 统一）**：`/object?id=`、`/path2gc?object=`、`/dominator?parent=` 当前仍用 `Query.intOr`，会把 `id=0x20` 静默吃成 -1 然后返 400 "missing 'id'"，与上面规则相悖。修法是把 `intOr` 换成 `parseObjectId` + 显式 `-1` 检查。

## Architecture invariants

- Service is **intranet-only**: no auth, no TLS, no CORS by default. All auth/user/quota/billing lives in the Python proxy; Java never sees user identity.
- **Frontend never talks to :8090 directly.** Python reverse-proxies `/api/heapdump-reports/{rid}/...` to the service. `dumpDir` is injected server-side from PostgreSQL, never client-supplied.
- **Java never writes business tables.** Schema evolution (heapdump_reports, tasks, sessions, etc.) happens in `/home/pan/react_agent_project` (Python `init_db()` with `create_all` + auto-column-add).
- `SnapshotManager` lifetime: open snapshots live behind a ref-counted `Lease` (use try-with-resources), a three-layer SoftReference cache, a `Semaphore(MAX_OPEN)` concurrency cap, and a `MemoryWatcher` that proactively disposes idle holders above `HEAP_PCT`. Never open an `ISnapshot` outside this mechanism.
- Snapshot reopen requires both the `.hprof` and all `*.index` files on a random-seek-capable FS (NFS works). Parsing state is not persisted in the service process.
- Report id prefix `hd_<uuid4-hex>`; task status `QUEUED → PARSING → DONE | FAILED | CANCELLED | CANCEL_REQUESTED`; timestamps are `yyyy-MM-dd HH:mm:ss` local TZ (not ISO-8601, not UTC).

## Testing

- `query-service/` currently has **no tests** (no `src/test/`). Plain Maven unit tests won't see MAT classes; Tycho Surefire + OSGi test fragment would be required to add real tests.
- Verification loop: `mvn -f query-service/pom.xml clean package` + redeploy per the deploy steps above + `curl` against running service on :8090.
- `mcp-server/`: `cd mcp-server && .venv/bin/pytest -q`, `ruff check src tests`, `ruff format --check src tests`. Unit + stdio protocol tests need no MAT. Real-MAT integration: `MAT_HOME=/home/pan/mat JVMIND_MCP_IT_HPROF=<dump> .venv/bin/pytest -m integration`.
- The wheel must contain `src/jvmind_mat_mcp/vendor/com.jvmind.mat.query-<version>.jar`; after Java changes rerun `mcp-server/scripts/vendor-bundle.sh`, otherwise `test_vendor.py`/CI fails.
- MCP release: bump `pyproject.toml`, `server.json`, and `src/jvmind_mat_mcp/__init__.py` together, tag `mcp-v<version>`. PyPI Trusted Publishing + `mcp-publisher login github-oidc && publish`; `mcp-publisher validate` checks `server.json`.

## Don't waste time on

- Building `mat-src/` (upstream reference only).
- Building `parser-cli/` V2 skeleton (MAT isn't on Central; V1 shell-out is the production path).
- "Fixing" `Main.java` / `EquinoxBootstrap.java` (dead route-1).
- Adding Spring/Spring-Boot/Jakarta/Jackson/Gson dependencies.
- Putting the bundle jar in `dropins/` (won't be picked up).
- Hunting for Maven Central coordinates for MAT (use Tycho + Directory target at `/home/pan/mat`).
