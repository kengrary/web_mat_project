# Marketplace submissions

Canonical metadata for every directory: [`metadata.json`](./metadata.json).

| Directory | Mechanism | Public repo | Artifact / status |
|---|---|---|---|
| [Smithery](./smithery.md) | MCPB bundle upload | ✓ public | `mcp-server/dist/jvmind-mat-mcp-0.1.1.mcpb` — built & validated; Smithery login pending |
| [mcp.so](./mcp.so.md) | Web form (free review or $39 paid) | ✓ public | Copy ready; account + form submission pending |
| [Glama](./glama.md) | Web form + Dockerfile | ✓ public | Copy + Dockerfile ready; account + form submission pending |
| [PulseMCP](./pulsemcp.md) | Official Registry ingest / web form | ✓ public | Likely automatic; check in browser |
| [MCPRush](https://mcprush.com/kengrary/jvmind-mat-heap-dump-analyzer-mcp) | Auto-indexed | ✓ public | Listed: Grade A, trust 94/100, 17 tools; v0.1.1 rescan pending |

Already published:

- Official MCP Registry: `io.github.kengrary/jvmind-mat` v0.1.1
- PyPI: `jvmind-mat-mcp` 0.1.1
- MCPRush: <https://mcprush.com/kengrary/jvmind-mat-heap-dump-analyzer-mcp>
  (auto-indexed; Grade A, trust 94/100)

## Repository visibility

`kengrary/web_mat_project` is **public**. The mcp.so, Glama, and Smithery
requirements that depended on a public repository are satisfied.

Remaining manual steps:

1. **Smithery** — `npx --yes smithery auth login`, then publish the MCPB bundle.
2. **mcp.so** — sign in, submit the MCP Server form with the values in
   [`mcp.so.md`](./mcp.so.md).
3. **Glama** — sign in, Add Server, then provide `mcp-server/Dockerfile` when
   their review email arrives.
4. **PulseMCP** — check in a browser; the server should be picked up from the
   official Registry.

## MCPRush

MCPRush picked the server up automatically; no submission was required. The
listing currently shows:

- Grade A, trust score 94/100, 17 tools; v0.1.1 rescan pending
- “Source is public, though no licence is declared” — the scan predates the
  MIT `LICENSE`; a rescan or the next PyPI release should clear this
- Tool descriptions and parameter schemas were not read because the scan was
  static (it did not connect to a running server)

A publisher account / live scan can improve the tool surface metadata.

## Quality gaps

- **License:** MIT is now added at the repository root and in `mcp-server/`,
  and the MCPB manifest declares MIT. PyPI 0.1.1 includes the MIT metadata; MCPRush will pick it up on rescan.
- **Icon:** optional but recommended for mcp.so/Smithery cards.
- **Smithery verification:** needs a logged-in Smithery account.
- **MCPRush tool metadata:** a runtime scan would capture descriptions and
  input schemas that the static scan skipped.

## Verification performed

- MCPB manifest schema validation passes (`mcpb validate`).
- Bundle packs to 275 KB and unpacks cleanly.
- Bundle runs through the `uv` runtime (including `uv.lock`) and completes MCP
  `initialize` with 17 tools.
- CI builds the MCPB and the Docker image successfully.
