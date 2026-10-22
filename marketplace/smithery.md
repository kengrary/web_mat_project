# Smithery (skipped)

**Status: skipped for now.** The Smithery dashboard only offers remote HTTPS URL publishing; there is no local MCPB entry point. The CLI server-creation endpoint returns `403 Updating servers has been removed`. Keep these artifacts for a future retry if Smithery restores local MCPB publishing.

Smithery distributes local stdio servers as **MCPB bundles**. There is no need
for a public MCP endpoint.

## Artifacts

- Standard MCPB manifest: [`smithery/manifest.json`](./smithery/manifest.json)
- Smithery extended manifest: [`smithery/manifest.smithery.json`](./smithery/manifest.smithery.json)
  (adds `inputSchema` to every tool)
- Build script: `mcp-server/scripts/build-mcpb.sh`
- Standard bundle: `mcp-server/dist/jvmind-mat-mcp-0.2.0.mcpb`
- Smithery bundle: `mcp-server/dist/jvmind-mat-smithery-0.2.0.mcpb`
- Runtime: `uv` (the host runs the Python package; `uv.lock` pins dependencies)

The manifests expose two optional user settings: `mat_home` (directory) and
`mat_xmx` (string, default `8g`).

Two Smithery-specific workarounds are intentional:

- `server.type` is `python` (not `uv`): Smithery's bundle parser only accepts
  `python`, `node`, `binary`, or a `bun` command, while `mcp_config.command`
  still runs the bundle through `uv`.
- The Smithery archive uses the extended manifest with `inputSchema`; the
  standard MCPB schema does not allow that key, so the Smithery archive is
  zipped separately and is not passed through `mcpb validate`.

## Create the server first

Smithery has removed server creation from the CLI/API. `PUT /servers/<name>`
returns:

```text
403 {"error":"Updating servers has been removed. Contact support@smithery.ai."}
```

and the old `smithery.run` host no longer resolves. Create the server in the
dashboard:

1. Open <https://smithery.ai/dashboard> → **Publish** → **New MCP server**.
2. Namespace `kengrary`, name `jvmind-mat`.
3. Display name: `JVMind MAT Heap Dump Analyzer`.
4. Description: `Analyze Java heap dumps locally with Eclipse MAT.`
5. If the dashboard offers an MCPB upload, upload
   `mcp-server/dist/jvmind-mat-smithery-0.2.0.mcpb`; otherwise create the
   server metadata and continue below.

## Publish a release

Once the server exists:

```bash
cd /home/pan/web_mat_project
mcp-server/scripts/build-mcpb.sh

npx --yes smithery auth login
npx --yes smithery mcp publish \
  mcp-server/dist/jvmind-mat-smithery-0.2.0.mcpb \
  -n kengrary/jvmind-mat
```

`smithery auth login` opens a browser and requires a Smithery account. In a
non-TTY environment it prints an auth URL; complete it in a browser, then
resume with:

```bash
npx --yes smithery mcp publish --resume
```

Verify:

```bash
npx --yes smithery mcp search jvmind-mat
```

## Notes

- Smithery's “Get verified” checklist links the listing to the source
  repository. The repo is public, so verification can proceed after publishing.
- The MCPB bundle is self-contained source-wise, but `uv` downloads the Python
  dependencies on first run. MAT and JDK 21 must already be installed on the
  user's machine.
- Rebuild and republish the bundle for every new package version; the manifest
  `version` must match `pyproject.toml`.
