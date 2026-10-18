# Marketplace submissions

Canonical metadata for every directory: [`metadata.json`](./metadata.json).

| Directory | Mechanism | Public repo required | Artifact / status |
|---|---|---|---|
| [Smithery](./smithery.md) | MCPB bundle upload | For verification only | `mcp-server/dist/jvmind-mat-mcp-0.1.0.mcpb` — built & validated |
| [mcp.so](./mcp.so.md) | Web form (free review or $39 paid) | **Yes** | Copy ready; submission blocked by private repo |
| [Glama](./glama.md) | Web form + Dockerfile | **Yes** (form says “Must be public”) | Copy + `mcp-server/Dockerfile` ready |
| [PulseMCP](./pulsemcp.md) | Official Registry ingest / web form | No (registry is enough) | Likely automatic; check in browser |

Already published:

- Official MCP Registry: `io.github.kengrary/jvmind-mat` v0.1.0
- PyPI: `jvmind-mat-mcp` 0.1.0

## Blocking decision: repository visibility

`kengrary/web_mat_project` is currently **private**. mcp.so and Glama require a
public GitHub repository, and Smithery's verification checklist links the
listing to the repository.

Choose one:

1. **Make the current repo public.** Simplest; exposes the Java query service,
   parser, docs, and MCP server.
2. **Publish a public mirror containing only `mcp-server/`** (recommended if the
   Java source should stay private). Create `kengrary/jvmind-mat-mcp`, push a
   subtree export, then update `server.json`, the PyPI README, and this
   directory to point at the new repo. The PyPI package name and Registry name
   can stay the same.
3. **Skip mcp.so and Glama.** Publish Smithery's MCPB bundle and rely on
   PulseMCP's Registry ingest.

Subtree export for option 2:

```bash
git subtree split --prefix=mcp-server -b mcp-public
# create the empty public repo first, then:
git push git@github.com:kengrary/jvmind-mat-mcp.git mcp-public:main
```

The export needs its own root `README.md`, `pyproject.toml`, `server.json`,
`Dockerfile`, and `NOTICE` (all already live under `mcp-server/`).

## Quality gaps to close before broad listing

- **License:** the repository and PyPI package have no `LICENSE` file. Glama
  grades license quality; mcp.so may ask. Pick a license for the MCP wrapper
  (for example MIT or Apache-2.0) and add it to `mcp-server/`.
- **Icon:** optional but recommended for mcp.so/Smithery cards.
- **Public Dockerfile build:** Docker Hub is unreachable from this workstation,
  so `mcp-server/Dockerfile` was validated by simulating its COPY/install steps
  in a clean venv, not by `docker build`. CI should build it.
- **Smithery verification:** needs a logged-in Smithery account and a public
  repo.

## Verification performed

- MCPB manifest schema validation passes (`mcpb validate`).
- Bundle packs to 275 KB and unpacks cleanly.
- Bundle runs through the `uv` runtime and completes MCP `initialize` with 17
  tools.
- Dockerfile install steps reproduced in a clean Python venv.
