# Smithery

Smithery distributes local stdio servers as **MCPB bundles**. There is no need
for a public MCP endpoint.

## Artifacts

- Manifest: [`smithery/manifest.json`](./smithery/manifest.json)
- Build script: `mcp-server/scripts/build-mcpb.sh`
- Bundle: `mcp-server/dist/jvmind-mat-mcp-0.1.0.mcpb`
- Runtime: `uv` (the host manages Python and dependencies)

The manifest exposes two optional user settings: `mat_home` (directory) and
`mat_xmx` (string, default `8g`).

## Publish

```bash
cd /home/pan/web_mat_project
mcp-server/scripts/build-mcpb.sh

npx --yes smithery auth login
npx --yes smithery mcp publish \
  mcp-server/dist/jvmind-mat-mcp-0.1.0.mcpb \
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
