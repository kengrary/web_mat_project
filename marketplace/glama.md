# Glama

Glama indexes public GitHub repositories and also has an **Add Server** flow.

## Form values

Open <https://glama.ai/mcp/servers> → **Add Server**:

| Field | Value |
|---|---|
| Name | `JVMind MAT Heap Dump Analyzer` |
| Description | `Local MCP server for Java heap dump analysis with Eclipse MAT: histograms, dominators, threads, OQL, leak suspects, and OOM diagnosis.` |
| GitHub Repository URL | `https://github.com/kengrary/web_mat_project` |

The field is explicitly labeled **“Must be public.”** The repository is now
public, so this requirement is satisfied.

The modal submits to `/api/mcp/servers/submit` and requires a Glama account.
After approval, Glama emails instructions for supplying a Dockerfile used for
automated safety and quality checks.

## Dockerfile

Use `mcp-server/Dockerfile` (build context: `mcp-server/`):

```bash
docker build -t jvmind-mat-mcp:0.1.0 mcp-server
```

The image contains the MCP server and its Python dependencies. It does **not**
include Eclipse MAT or JDK 21; those are runtime dependencies for heap analysis.
For a self-contained analysis image, mount MAT into the container and set
`MAT_HOME`:

```bash
docker run --rm -i \
  -v /opt/mat:/opt/mat:ro \
  -e MAT_HOME=/opt/mat \
  jvmind-mat-mcp:0.1.0
```

The MCP `initialize` and `tools/list` calls work without MAT; analysis tools
return an actionable “MAT not found” error until it is available.

## Notes

- Glama may auto-index the repo after it becomes public; submitting explicitly
  is still useful for claiming the listing.
- A public repo plus a working Dockerfile materially improves the quality
  grade. The repository now has an MIT `LICENSE`; the published PyPI 0.1.0
  metadata predates it and will show MIT after the next release.
- Docker Hub was unreachable from the workstation used to prepare this, but the
  GitHub Actions CI workflow builds the image successfully.
