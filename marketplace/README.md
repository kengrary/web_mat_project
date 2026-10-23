# Marketplace submissions

Canonical metadata for every directory: [`metadata.json`](./metadata.json).

| Directory | Mechanism | Public repo | Artifact / status |
|---|---|---|---|
| [Smithery](./smithery.md) | Local MCPB upload | ✓ public | **Skipped**: the dashboard only offers remote HTTPS URLs; the CLI create endpoint was removed |
| [mcp.so](./mcp.so.md) | Web form (free review or $39 paid) | ✓ public | Copy ready; account + form submission pending |
| [Glama](./glama.md) | Web form + generated Dockerfile | ✓ public | Copy + generated-Dockerfile fields ready; account + form submission pending |
| [PulseMCP](./pulsemcp.md) | Official Registry ingest / web form | ✓ public | Likely automatic; check in browser |
| [MCPRush](https://mcprush.com/kengrary/jvmind-mat-heap-dump-analyzer-mcp) | Auto-indexed | ✓ public | Listed: Grade A, trust 94/100; currently v0.1.1 (17 tools), v0.2.0 rescan pending |

Already published:

- Official MCP Registry: `io.github.kengrary/jvmind-mat` v0.2.0
- PyPI: `jvmind-mat-mcp` 0.2.0
- MCPRush: <https://mcprush.com/kengrary/jvmind-mat-heap-dump-analyzer-mcp>
  (auto-indexed; Grade A, trust 94/100)

## Repository visibility

`kengrary/web_mat_project` is **public**. The mcp.so and Glama requirements
that depended on a public repository are satisfied.

Remaining manual steps:

1. **mcp.so** — sign in, submit the MCP Server form with the values in
   [`mcp.so.md`](./mcp.so.md).
2. **Glama** — sign in, Add Server, then provide `mcp-server/Dockerfile` when
   their review email arrives.
3. **PulseMCP** — check in a browser; the server should be picked up from the
   official Registry.

**Smithery is skipped for now.** Its dashboard only exposes the remote HTTPS
URL flow, and its CLI/API server-creation endpoint returns
`403 Updating servers has been removed`.

## MCPRush

MCPRush picked the server up automatically; no submission was required. The
listing currently shows:

- Grade A, trust score 94/100; currently v0.1.1 with 17 tools; v0.2.0 rescan pending
- “Source is public, though no licence is declared” — the scan predates the
  MIT `LICENSE`; a rescan or the next PyPI release should clear this
- Tool descriptions and parameter schemas were not read because the scan was
  static (it did not connect to a running server)

A publisher account / live scan can improve the tool surface metadata.

## Smithery (skipped)

The local MCPB artifacts are kept in [`smithery.md`](./smithery.md) for a future
retry, but Smithery is not part of the current rollout:

- The web dashboard only publishes remote HTTPS MCP servers.
- The CLI server-creation endpoint returns `403 Updating servers has been removed`.
- There is no local stdio/MCPB entry point in the current UI.

## Quality gaps

- **Icon:** optional but recommended for mcp.so cards.
- **MCPRush tool metadata:** a runtime scan would capture descriptions and
  input schemas that the static scan skipped.

## Verification performed

- Standard MCPB manifest schema validation passes (`mcpb validate`).
- Standard and Smithery bundles unpack cleanly (about 286 KB each).
- The bundle runs through the `uv` runtime (including `uv.lock`) and completes
  MCP `initialize` with 19 tools.
- CI builds the MCPB and the Docker image successfully.
- The Smithery extended manifest has all 19 `inputSchema` objects; it is kept
  for a future retry if Smithery restores local MCPB publishing.
