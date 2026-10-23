# JVMind MAT MCP Server

<!-- mcp-name: io.github.kengrary/jvmind-mat -->

A local [Model Context Protocol](https://modelcontextprotocol.io) server for
analyzing Java heap dumps with [Eclipse MAT](https://eclipse.dev/mat/). It can
discover local Java processes, capture a live heap dump, and expose heap
overview, histograms, dominator trees, threads, OQL, leak suspects, and OOM
diagnosis to MCP clients such as Claude Desktop, Cursor, and VS Code.

Everything runs on your machine. Heap dumps never leave the host; the helper
service binds to `127.0.0.1` only.

## Published

- PyPI: <https://pypi.org/project/jvmind-mat-mcp/> (v0.2.1)
- Official MCP Registry: `io.github.kengrary/jvmind-mat`
- MCPRush: <https://mcprush.com/kengrary/jvmind-mat-heap-dump-analyzer-mcp>
- Install: `uvx jvmind-mat-mcp doctor`

Directory listings (mcp.so, Glama, PulseMCP) are prepared but not live yet.
Smithery is skipped because its current web UI has no local MCPB flow; see
[`../marketplace/README.md`](../marketplace/README.md).

## Requirements

- Python 3.10+
- JDK 21+
- Eclipse MAT **1.17.x** (the server installs its small query bundle into MAT on
  first use; MAT itself is not bundled)

Download MAT from <https://eclipse.dev/mat/downloads.php> and unpack it, for
example to `/opt/mat`.

## Install

```bash
uvx jvmind-mat-mcp doctor
```

or with pip:

```bash
pip install jvmind-mat-mcp
jvmind-mat-mcp doctor
```

`doctor` validates MAT, JDK, permissions, and the state directory.

If MAT is not in a common location, set `MAT_HOME`:

```bash
export MAT_HOME=/opt/mat
```

## Client configuration

Claude Desktop (`claude_desktop_config.json`) and Cursor (`.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "jvmind-mat": {
      "command": "uvx",
      "args": ["jvmind-mat-mcp"],
      "env": {
        "MAT_HOME": "/opt/mat",
        "JVMIND_MAT_XMX": "8g"
      }
    }
  }
}
```

VS Code (`.vscode/mcp.json`):

```json
{
  "servers": {
    "jvmind-mat": {
      "type": "stdio",
      "command": "uvx",
      "args": ["jvmind-mat-mcp"],
      "env": {
        "MAT_HOME": "/opt/mat",
        "JVMIND_MAT_XMX": "8g"
      }
    }
  }
}
```

Restart the client after changing the configuration.

## Usage

### Live JVM

1. `list_java_processes()` — discover local, attachable JVMs. The managed MAT
   helper is excluded and secrets in command lines are masked.
2. `dump_java_heap(pid)` — first call without `confirm=true` returns a preview
   (target, output directory, estimated/free disk, timeout). Call again with
   `confirm=true` to capture.
3. `get_heapdump_status(report_id)` — the dump runs in the background with
   `status=DUMPING`; MAT parsing starts automatically and the status becomes
   `PARSING`, then `DONE`.
4. Analyze with the `mat_*` tools.

A live dump uses `jmap -dump:live`, so it triggers a Full GC and may pause the
target JVM. The default output directory is the server's current working
directory; override it with `output_dir` or `JVMIND_MCP_DUMP_DIR`.

### Existing heap dump

1. `open_heapdump(path)` — absolute path to a `.hprof` file. Indexing starts in
   the background and the call returns immediately with a `report_id` and
   `status=PARSING`. If MAT index files already exist next to the dump, the
   report is ready immediately.
2. `get_heapdump_status(report_id)` — poll until `status=DONE`. Large dumps can
   take several minutes.
3. Analyze:
   - `mat_overview` — always start here
   - `mat_histogram`, `mat_dominator`, `mat_top_consumers`
   - `mat_threads`, `mat_threadlocals`, `mat_sql_in_threads`
   - `mat_object`, `mat_path2gc`, `mat_oql`
   - `mat_leak_suspects`, `mat_diagnose_oom`, `mat_connection_pools`
4. `close_heapdump(report_id)` — release the handle. Pass `stop_service=true` to
   also stop the MAT helper process.

Tools accept an object id in decimal or `0x`-prefixed hex. Values shown as
`retained` may be approximate (MAT's dominator-tree lower bound).

## Tools

All analysis tools take the `report_id` returned by `open_heapdump`.

- `open_heapdump` — open a local `.hprof`, start background indexing, return a `report_id`
- `get_heapdump_status` — poll parse status, progress, and phase
- `list_heapdumps` — list heap dumps known to this MCP server
- `close_heapdump` — release a heap dump handle and optionally stop the MAT helper
- `list_java_processes` — discover local attachable JVMs (pid, main class, command; MAT helper excluded)
- `dump_java_heap` — preview and capture a live heap dump, then start MAT parsing (confirm=true)
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

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `MAT_HOME` | auto-detect | Eclipse MAT install directory |
| `JVMIND_JAVA` | `JAVA_HOME/bin/java` or `java` | JDK 21 binary |
| `JVMIND_JCMD` | sibling of `JVMIND_JAVA` | `jcmd` used for process discovery and heap estimates |
| `JVMIND_JPS` | sibling of `JVMIND_JAVA` | `jps` fallback for process discovery |
| `JVMIND_JMAP` | sibling of `JVMIND_JAVA` | `jmap` used for live heap dumps |
| `JVMIND_MAT_XMX` | 70% of RAM, min 4g | Heap for MAT parsing and queries |
| `JVMIND_MCP_DUMP_DIR` | current working directory | Default output directory for live dumps |
| `JVMIND_DUMP_TIMEOUT` | `300` | Live dump timeout (seconds) |
| `JVMIND_DUMP_MIN_FREE_GB` | `2` | Minimum free disk space before a dump |
| `MAT_QUERY_SERVICE_URL` | managed locally | Use an already-running query service |
| `MAT_QUERY_PORT` | `8090` | Preferred local port (falls back to a free port) |
| `MAT_QUERY_CACHE` | `true` | Cache heavy query results on disk |
| `JVMIND_MCP_STATE_DIR` | platform state dir | State, logs, parsed report registry |
| `JVMIND_MCP_HTTP_TIMEOUT` | `60` | Per-request timeout (seconds) |
| `JVMIND_MCP_POLL_INTERVAL` | `2` | Async task poll interval (seconds) |
| `JVMIND_MCP_POLL_MAX_SECS` | `900` | Async task timeout (seconds) |

State and logs live in `~/.local/state/jvmind-mcp` on Linux (see
`jvmind-mat-mcp doctor` for the exact path).

## Privacy and security

- The MCP server and MAT helper communicate over loopback only.
- No telemetry, no uploads, no cloud calls.
- OQL can read arbitrary strings from the heap. Treat query output as sensitive.
- `list_java_processes` returns command lines; values that look like passwords,
  tokens, or credentials are masked, but review output before sharing it.
- Heap dumps and MAT index files contain sensitive heap data. Use a private
  output directory (the default is the server's current working directory).
  The `.hprof` is chmod 0600; MAT writes index files with its own permissions,
  so directory permissions matter.
- The MAT query service has no authentication; it is bound to `127.0.0.1` and
  must not be exposed to a network.

## Troubleshooting

**MAT not found** — set `MAT_HOME` to the directory containing `plugins/` and
`configuration/`. On macOS this is often
`/Applications/mat.app/Contents/Eclipse`.

**Wrong MAT version** — this release requires MAT 1.17.x. Upgrade MAT or install
a matching release of this package.

**Permission denied writing to MAT** — the server must copy one small jar into
`$MAT_HOME/plugins` and update
`configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`. Install MAT
in a user-writable directory or grant write access.

**Parsing runs out of memory** — increase `JVMIND_MAT_XMX` (for example `16g`)
or analyze a smaller dump.

**Port already in use** — the server reuses a JVMind query service if it finds
one, otherwise picks a free port. Set `MAT_QUERY_PORT` to a preferred port.

**No Java processes found** — confirm the same user owns the MCP server and the
target JVM, that `jcmd`/`jps` come from the JDK 21 install, and that the target
is in the same PID namespace (containers only see their own processes).

**Attach failed / socket file error** — the target may run with
`-XX:+DisableAttachMechanism` or under another user. Start it without that flag
and run the MCP server as the same user. Java 8 targets are supported on a
best-effort basis.

**Not enough disk space** — set `JVMIND_MCP_DUMP_DIR` to a larger filesystem or
raise `JVMIND_DUMP_MIN_FREE_GB`. MAT also writes index files next to the dump.

**Dump stays DUMPING** — large heaps can take a long time. Increase
`JVMIND_DUMP_TIMEOUT`; the target JVM may be paused for a Full GC.

**Inspect logs** — see `~/.local/state/jvmind-mcp/logs/` (`parse-<id>.log`,
`dump-<id>.log`, and `mat-service.log`) or run `jvmind-mat-mcp doctor`.

## Development

```bash
cd mcp-server
python -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/pytest -q
.venv/bin/ruff check src tests
.venv/bin/ruff format --check src tests
```

The wheel ships `src/jvmind_mat_mcp/vendor/com.jvmind.mat.query-<version>.jar`.
After changing the Java query service, rebuild and vendor it:

```bash
mcp-server/scripts/vendor-bundle.sh
```

Integration test:

```bash
MAT_HOME=/opt/mat \
JVMIND_MCP_IT_HPROF=/path/to/app.hprof \
.venv/bin/pytest -q -m integration
```

Docker (directory/CI checks; MAT and JDK 21 are not included):

```bash
docker build -t jvmind-mat-mcp:0.2.1 .
docker run --rm -i \
  -v /opt/mat:/opt/mat:ro \
  -e MAT_HOME=/opt/mat \
  jvmind-mat-mcp:0.2.1
```

## Publishing

`v0.2.1` is published to PyPI and the official MCP Registry. Release tags use
the `mcp-v<version>` form. The GitHub workflow builds the wheel, publishes to
PyPI with Trusted Publishing, then publishes `server.json` to the official MCP
Registry with GitHub OIDC.

Manual prerequisites:

1. Configure a PyPI Trusted Publisher for the `pypi` environment.
2. Confirm the package name `jvmind-mat-mcp` and registry name
   `io.github.kengrary/jvmind-mat`.
3. Bump the version in `pyproject.toml`, `server.json`, and
   `src/jvmind_mat_mcp/__init__.py` together.

Local checks:

```bash
python -m build
python -m twine check dist/*.whl dist/*.tar.gz
mcp-publisher validate
```

Marketplace packs (mcp.so/Glama/PulseMCP copy) live in
[`../marketplace/README.md`](../marketplace/README.md). The Smithery MCPB is kept
for a future retry (its local publishing flow is currently unavailable); it runs
the Python package via `uv` and is pinned by `uv.lock`. Build both bundles with:

```bash
mcp-server/scripts/build-mcpb.sh
```

## License

The MCP server is licensed under the MIT License; see [LICENSE](./LICENSE).
Eclipse MAT is licensed under the Eclipse Public License 2.0 and is not
redistributed by this package.
