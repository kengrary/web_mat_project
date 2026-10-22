# PulseMCP

PulseMCP is a directory of MCP servers. Its site is behind Cloudflare bot
protection, so it cannot be checked or submitted from the CLI in this
environment.

## Status

The server is already live in the official MCP Registry
(`io.github.kengrary/jvmind-mat`). PulseMCP is expected to ingest Registry
entries automatically; check in a browser:

- <https://www.pulsemcp.com/servers> (search `jvmind`)

Allow a few days for ingestion after a new Registry release.

## If it is not listed

Open <https://www.pulsemcp.com/submit> in a browser and submit:

| Field | Value |
|---|---|
| Name | `JVMind MAT Heap Dump Analyzer` |
| Short description | `Analyze Java heap dumps locally with Eclipse MAT and live JVM capture: histograms, dominators, threads, OQL, leak suspects, and OOM diagnosis.` |
| Repository | `https://github.com/kengrary/web_mat_project` (or the public mirror) |
| Package / install | `uvx jvmind-mat-mcp` |
| Category | Developer Tools |
| Tags | `mcp`, `heapdump`, `live-jvm`, `jvm`, `java`, `eclipse-mat`, `memory-leak`, `oom` |
| Registry name | `io.github.kengrary/jvmind-mat` |

Long description: copy `description_long` from
[`metadata.json`](./metadata.json).

## Notes

- PulseMCP entries often link to the source repository; a public repo is better
  for discovery even if not strictly required for Registry ingestion.
- Do not resubmit repeatedly while a Registry-sourced entry is pending.
