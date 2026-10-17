# JVMind MAT MCP Server — Implementation Plan

Design: `2026-10-17-jvmind-mat-mcp-design.md`

Status: implemented (v0.1.0).

## Phase 0 — Documentation and package skeleton

- [x] `mcp-server/pyproject.toml` (Python >=3.10, deps: mcp, httpx, filelock,
      platformdirs; console script `jvmind-mat-mcp`).
- [x] `src/jvmind_mat_mcp/` package, `__main__.py`, `cli.py --help`.
- [x] `ruff` and `pytest` dev configuration.
- Verify: `pip install -e 'mcp-server[dev]'`, `jvmind-mat-mcp --help`.

## Phase 1 — MAT/JDK detection and doctor

- [x] `config.py`: env vars, defaults, state paths, version pins.
- [x] `mat_runtime.py`: locate MAT, parse version, locate launcher jar,
      validate Java 21, check write permissions.
- [x] `jvmind-mat-mcp doctor` prints a human-readable report.
- Verify: unit tests with fake MAT trees; doctor on `/home/pan/mat`.

## Phase 2 — Bundle installation and service lifecycle

- [x] `scripts/vendor-bundle.sh` builds/copies the query-service jar.
- [x] Add `.gitignore` exception for the vendored jar.
- [x] `ensure_bundle()`: sha256-compare, copy, idempotent bundles.info update,
      backup on first modification, cleanup of old versions.
- [x] `start_service()`: free port, direct launcher command, health polling,
      PID tracking, log capture, atexit shutdown, stale process reuse.
- Verify: unit tests for bundles.info; start/stop against real MAT.

## Phase 3 — Parsing and local state

- [x] `store.py`: JSON state + lock, report ids, mtime/size validation.
- [x] `parse.py`: port `TextProgressMapper` and `run_parse_v1`, including
      partial-index cleanup and MAT exit code 79 handling.
- [x] Background parse thread with throttled progress updates.
- [x] Lifecycle tools: `open_heapdump`, `get_heapdump_status`,
      `list_heapdumps`, `close_heapdump`.
- Verify: unit tests for mapper/store; real parse of a small hprof.

## Phase 4 — Analysis tools

- [x] `mat_client.py`: endpoint wrappers with per-endpoint timeouts.
- [x] `tools.py`: adapt the existing 13 analysis tools; explicit typed signatures.
- [x] Register tools with bilingual descriptions and output trimming.
- [x] Async poll for `leak-suspects` / `diagnose-oom`.
- Verify: tool schema tests; real calls for overview/histogram/object.

## Phase 5 — Tests and hardening

- [x] Unit tests for detection, bundles, store, mapper, contracts.
- [x] stdio protocol smoke test with the official MCP client.
- [x] Integration test gated by `MAT_HOME` + `JVMIND_MCP_IT_HPROF`.
- [x] Concurrency/restart/error-path tests.
- Verify: `pytest`, `ruff check`, `ruff format --check`.

## Phase 6 — Packaging and publishing

- [x] README with install, MAT/JDK setup, client config, privacy,
      troubleshooting, and `mcp-name` marker.
- [x] `server.json` (PyPI, stdio, version synced with tag).
- [x] `mcp-ci.yml` and `publish-mcp.yml` (PyPI trusted publishing + registry).
- [x] Manual prerequisite: configure PyPI trusted publisher.
- Verify: `twine check dist/*`, `uvx jvmind-mat-mcp --help`,
      `mcp-publisher validate`, registry search.
