# Marketplace submissions

Canonical metadata for every directory: [`metadata.json`](./metadata.json).

| Directory | Mechanism | Public repo | Artifact / status |
|---|---|---|---|
| [Smithery](./smithery.md) | MCPB bundle upload | ✓ public | `mcp-server/dist/jvmind-mat-mcp-0.1.0.mcpb` — built & validated; Smithery login pending |
| [mcp.so](./mcp.so.md) | Web form (free review or $39 paid) | ✓ public | Copy ready; account + form submission pending |
| [Glama](./glama.md) | Web form + Dockerfile | ✓ public | Copy + Dockerfile ready; account + form submission pending |
| [PulseMCP](./pulsemcp.md) | Official Registry ingest / web form | ✓ public | Likely automatic; check in browser |

Already published:

- Official MCP Registry: `io.github.kengrary/jvmind-mat` v0.1.0
- PyPI: `jvmind-mat-mcp` 0.1.0

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

## Quality gaps

- **License:** the repository and PyPI package still have no `LICENSE` file.
  Glama grades license quality; mcp.so may ask. Pick a license for the MCP
  wrapper (for example MIT or Apache-2.0) and add it to `mcp-server/`.
- **Icon:** optional but recommended for mcp.so/Smithery cards.
- **Smithery verification:** needs a logged-in Smithery account.

## Verification performed

- MCPB manifest schema validation passes (`mcpb validate`).
- Bundle packs to 275 KB and unpacks cleanly.
- Bundle runs through the `uv` runtime (including `uv.lock`) and completes MCP
  `initialize` with 17 tools.
- CI builds the MCPB and the Docker image successfully.
