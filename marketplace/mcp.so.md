# mcp.so

Submission page: <https://mcp.so/submit> → **MCP Server** tab.

The free path is a manual review. A $39 one-time paid option publishes
immediately with a verified badge and featured placement; not required.

## Prerequisites

The form requires a **public GitHub repository URL**; `kengrary/web_mat_project`
is now public. You still need an mcp.so account to submit.

## Form values

| Field | Value |
|---|---|
| Repository URL | `https://github.com/kengrary/web_mat_project` |
| Name | `JVMind MAT Heap Dump Analyzer` |
| Short description | `Analyze Java heap dumps locally with Eclipse MAT: histograms, dominators, threads, OQL, leak suspects, and OOM diagnosis.` |
| Category | Developer Tools |
| Tags | `mcp`, `heapdump`, `jvm`, `java`, `eclipse-mat`, `memory-leak`, `oom`, `debugging` |
| Install | `uvx jvmind-mat-mcp` |
| Homepage | `https://pypi.org/project/jvmind-mat-mcp/` |

Long description:

```markdown
JVMind MAT runs Eclipse MAT on your machine and exposes heap analysis as MCP
tools. Open a `.hprof` file, wait for background indexing, then inspect the heap
overview, class histogram, dominator tree, thread stacks, ThreadLocals,
individual objects, GC paths, OQL results, LeakHunter suspects, OOM diagnosis,
connection pools, top consumers, and SQL in threads.

Everything stays local: heap dumps never leave the machine, and the MAT helper
binds to `127.0.0.1` only.

**Requirements:** JDK 21 and Eclipse MAT 1.17.x. The server installs a small
query bundle into MAT on first use.

**Install:** `uvx jvmind-mat-mcp`

The repository README lists all 17 tools under the `## Tools` heading.
```

Client configuration to show on the listing:

```json
{
  "mcpServers": {
    "jvmind-mat": {
      "command": "uvx",
      "args": ["jvmind-mat-mcp"],
      "env": {
        "MAT_HOME": "/opt/mat",
        "JVMIND_MAT_XMX": "8g"
      }
    }
  }
}
```

## Notes

- Sign-in is required to submit.
- mcp.so auto-extracts tools from a `## Tools` heading in the repository
  README. The root `README.md` / `README.en.md` now have that section, so the
  listing should show all 17 tools after submission. Wait until the README
  change is pushed to the default branch.
- A logo/icon is optional but improves the card. No icon is committed yet.
- The listing should mention that analysis is local and requires MAT; this
  avoids users expecting a hosted service.
