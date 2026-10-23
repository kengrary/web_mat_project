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

## Glama's generated Dockerfile

Glama does not accept a custom Dockerfile. It generates one from the repository
and the submission fields, then wraps the stdio server with `mcp-proxy`.

Fill the fields with:

| Field | Value |
|---|---|
| Python version | `3.12` (the template defaults to 3.15 preview; override it if possible) |
| Build command | `UV_TOOL_BIN_DIR=/usr/local/bin uv tool install --python 3.12 ./mcp-server` |
| Start command | `jvmind-mat-mcp` |
| System packages (optional) | `openjdk-21-jdk-headless` |
| Environment | `JVMIND_MCP_STATE_DIR=/tmp/jvmind-mcp-state`, `JVMIND_MCP_DUMP_DIR=/tmp/jvmind-mcp-dumps` |

The generated file should end with:

```dockerfile
CMD ["mcp-proxy","--","jvmind-mat-mcp"]
```

`uv tool install` puts the console script in `/usr/local/bin`, so `mcp-proxy`
can resolve it. If the form has no build command field, use this start command
instead (requires network at container start):

```bash
uvx --python 3.12 --from /app/mcp-server jvmind-mat-mcp
```

The generated image does not include Eclipse MAT. MCP `initialize` and
`tools/list` work; `mat_*` calls return an actionable “MAT not found” error until
MAT is mounted. The live JVM tools need the optional JDK package; without it
they return a JDK-tool-not-found error.

## Environment variables JSON schema

Paste this into Glama's “Environment variables JSON schema” field. All variables
are optional so the server can still start without MAT; real heap analysis needs
`MAT_HOME` and JDK 21.

```json
{
  "type": "object",
  "properties": {
    "MAT_HOME": {
      "type": "string",
      "title": "Eclipse MAT home",
      "description": "Directory containing plugins/ and configuration/ for Eclipse MAT 1.17.x. Leave empty to auto-detect.",
      "default": ""
    },
    "JVMIND_JAVA": {
      "type": "string",
      "title": "JDK 21 java binary",
      "description": "Path to the java executable (JDK 21+). Defaults to JAVA_HOME/bin/java or java on PATH.",
      "default": "java"
    },
    "JVMIND_MAT_XMX": {
      "type": "string",
      "title": "MAT heap size",
      "description": "JVM heap for MAT parsing and queries, for example 8g. Defaults to 70% of RAM, minimum 4g.",
      "default": "8g"
    },
    "JVMIND_MCP_STATE_DIR": {
      "type": "string",
      "title": "State directory",
      "description": "Directory for state, logs, and parsed report registry.",
      "default": "/tmp/jvmind-mcp-state"
    },
    "JVMIND_MCP_DUMP_DIR": {
      "type": "string",
      "title": "Heap dump directory",
      "description": "Default output directory for live heap dumps.",
      "default": "/tmp/jvmind-mcp-dumps"
    },
    "JVMIND_DUMP_TIMEOUT": {
      "type": "string",
      "title": "Dump timeout (seconds)",
      "description": "Timeout for a live heap dump, 1-3600 seconds.",
      "default": "300"
    },
    "JVMIND_DUMP_MIN_FREE_GB": {
      "type": "string",
      "title": "Minimum free disk space (GB)",
      "description": "Refuse to start a dump when free space is below this value.",
      "default": "2"
    }
  },
  "required": []
}
```

## Self-hosting Dockerfile

The repo-root [`Dockerfile`](../Dockerfile) is for local/self-hosting (JDK 21
included, non-root). Glama does not use it.

```bash
docker build -t jvmind-mat-mcp:0.2.0 .
docker run --rm -i \
  -v /opt/mat:/opt/mat \
  -e MAT_HOME=/opt/mat \
  jvmind-mat-mcp:0.2.0
```

`mcp-server/Dockerfile` remains a smaller Python-only image for package-level
checks.

## Notes

- Glama may auto-index the repo after it becomes public; submitting explicitly
  is still useful for claiming the listing.
- A public repo plus a working Dockerfile materially improves the quality
  grade. The repository now has an MIT `LICENSE`; the published PyPI 0.2.0
  metadata includes it.
- Docker Hub was unreachable from the workstation used to prepare this, but the
  GitHub Actions CI workflow builds the image successfully.
