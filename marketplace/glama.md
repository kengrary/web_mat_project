# Glama

Glama indexes public GitHub repositories and also has an **Add Server** flow.

## Form values

Open <https://glama.ai/mcp/servers> → **Add Server**:

| Field | Value |
|---|---|
| Name | `JVMind MAT Heap Dump Analyzer` |
| Description | `Local MCP server for Java heap dump analysis with Eclipse MAT and live JVM capture: histograms, dominators, threads, OQL, leak suspects, and OOM diagnosis.` |
| GitHub Repository URL | `https://github.com/kengrary/web_mat_project` |

The field is explicitly labeled **“Must be public.”** The repository is now
public, so this requirement is satisfied.

The modal submits to `/api/mcp/servers/submit` and requires a Glama account.
After approval, Glama emails instructions for supplying a Dockerfile used for
automated safety and quality checks.

## Dockerfile

Use the repo-root [`Dockerfile`](../Dockerfile) (build context: repository root):

```bash
docker build -t jvmind-mat-mcp:0.2.0 .
```

The image includes Python 3.12 and JDK 21, runs as a non-root user, and contains
the MCP server plus its Python dependencies. It does **not** include Eclipse MAT
(EPL-2.0, not redistributed). For heap analysis, mount MAT writable and set
`MAT_HOME`:

```bash
docker run --rm -i \
  -v /opt/mat:/opt/mat \
  -e MAT_HOME=/opt/mat \
  jvmind-mat-mcp:0.2.0
```

The MCP `initialize`, `tools/list`, and `list_java_processes` calls work without
MAT; analysis tools return an actionable “MAT not found” error until it is
available. If the MAT mount is not writable by UID 10001, run the container with
your host UID or pre-install the query bundle.

`mcp-server/Dockerfile` remains a smaller Python-only image for package-level
checks; Glama should use the root `Dockerfile`.

## Notes

- Glama may auto-index the repo after it becomes public; submitting explicitly
  is still useful for claiming the listing.
- A public repo plus a working Dockerfile materially improves the quality
  grade. The repository now has an MIT `LICENSE`; the published PyPI 0.2.0
  metadata includes it.
- Docker Hub was unreachable from the workstation used to prepare this, but the
  GitHub Actions CI workflow builds the image successfully.
