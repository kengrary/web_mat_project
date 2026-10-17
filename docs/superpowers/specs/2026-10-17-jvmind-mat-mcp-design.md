# JVMind MAT MCP Server — Design

Date: 2026-10-17
Status: approved

## Goal

Expose the existing JVMind heapdump analysis capabilities (Eclipse MAT query
service) as a local stdio MCP server, distributed on PyPI and indexed in the
official MCP Registry as `io.github.kengrary/jvmind-mat`.

The first release is local-only: the user runs the MCP server on the machine
that holds the `.hprof` file and has Eclipse MAT 1.17 + JDK 21 installed.

## Non-goals (v1)

- Remote/hosted MCP endpoint, OAuth, multi-user auth, quotas.
- Docker/OCI packaging.
- Uploading heapdumps; the tool takes an existing local path.
- Windows support beyond best effort.
- Replacing the Python gateway or its `report_id` model.

## Architecture

```
MCP client (Claude Desktop / Cursor / VS Code)
        │ stdio JSON-RPC
        ▼
jvmind-mat-mcp (Python, FastMCP)
  ├─ RuntimeManager   detect MAT/JDK, install OSGi bundle, start/stop :8090
  ├─ ParseRunner      reuses parser-cli v1 ParseHeapDump logic (background)
  ├─ LocalStore       ~/.local/state/jvmind-mcp/state.json (+ file lock)
  └─ MatClient        HTTP → 127.0.0.1:<free port>
```

The Java side is reused unchanged: the package ships the 150 KB
`com.jvmind.mat.query-<version>.jar` OSGi bundle and installs it into the
user's MAT `plugins/` + `configuration/.../bundles.info` on first use.

## Lifecycle

1. `open_heapdump(path)` validates the hprof header, registers a `hd_<uuid>`
   handle, and returns immediately with `status=PARSING`.
2. A background thread ensures MAT is installed/running, then runs
   `org.eclipse.mat.api.parse` and updates progress in the state file.
3. Analysis tools refuse to run until `status=DONE` and return
   `解析中(x%, phase)` instead.
4. `get_heapdump_status` exposes progress; `list_heapdumps` lists handles;
   `close_heapdump` releases the handle and optionally stops MAT.
5. The MAT child process is owned by the MCP server and terminated on exit.
   A stale service from a previous run is reused if `/health` answers.

## Tools

Lifecycle tools:

- `open_heapdump(path)`
- `get_heapdump_status(report_id)`
- `list_heapdumps()`
- `close_heapdump(report_id, stop_service=False)`

Analysis tools (1:1 with the existing Python agent tools):

`mat_overview`, `mat_histogram`, `mat_dominator`, `mat_threads`,
`mat_threadlocals`, `mat_object`, `mat_path2gc`, `mat_oql`,
`mat_leak_suspects`, `mat_diagnose_oom`, `mat_connection_pools`,
`mat_top_consumers`, `mat_sql_in_threads`.

Descriptions are bilingual and reused from
`react_agent/tools.py`; implementation is adapted from
`react_agent/mat_tools.py` (human-readable units, Top-N truncation, async
polling, normalized errors).

## Runtime details

- MAT Home discovery: `MAT_HOME`, then common install paths, then
  `MemoryAnalyzer` on `PATH`.
- Accepted MAT versions: `>=1.17,<1.18`; Java: 21+.
- MAT is launched directly through the Equinox launcher jar so `-Xmx` can be
  controlled; bind address is always `127.0.0.1`.
- Default MAT heap: `max(4g, 70% of physical RAM)`, overridable with
  `JVMIND_MAT_XMX`.
- Service port: external `MAT_QUERY_SERVICE_URL` wins; otherwise 8090 if free,
  otherwise an ephemeral port. Only the MCP process may manage the port it
  chose.
- Parsing writes index files next to the hprof, as MAT requires. Failed parses
  clean partial `*.index` files (same behavior as parser-cli v1).
- `-clean` is passed only when the bundle registration changed.

## State and privacy

- State lives under the platform state dir (`~/.local/state/jvmind-mcp` on
  Linux), protected by a file lock.
- Logs go to the same directory; only short tails are surfaced to the LLM.
- No network egress; heap data never leaves the machine.
- OQL can read arbitrary strings from the heap; the tool description warns
  about privacy.

## Errors

Actionable messages for: MAT missing, wrong MAT version, JDK too old, unwritable
MAT install, missing/unreadable hprof, stale/partial index, port conflict,
service startup failure, and parse OOM (MAT exit code 79). No raw stack traces
are returned to the model.

## Packaging and publishing

- Distribution: `jvmind-mat-mcp` on PyPI, console script
  `jvmind-mat-mcp`; wheel contains the vendored query-service jar.
- Registry: `io.github.kengrary/jvmind-mat`, `server.json` schema
  `2025-12-11`, PyPI package + stdio transport.
- Ownership verification: `<!-- mcp-name: io.github.kengrary/jvmind-mat -->`
  in the PyPI README.
- Release tag `mcp-v<version>` triggers: build → PyPI trusted publishing →
  `mcp-publisher login github-oidc` → `mcp-publisher publish`.

## Testing

- Unit: MAT detection, bundle registration idempotency, state locking,
  progress mapping, tool schemas.
- Protocol: official MCP Python client over stdio (`initialize`, `tools/list`,
  lifecycle calls against a mocked runtime).
- Integration (manual/nightly): real MAT + small hprof, full flow.
