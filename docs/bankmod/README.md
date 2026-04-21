# bankmod — Typed Services-Graph Artifact

bankmod is a parallel product assembly in the llm4zio monorepo. It publishes a
typed ADT of a banking services graph, a validator with seven invariants, and
an MCP server that lets an LLM read, render, validate, and evolve the graph.

## Layout

- [`generated/`](./generated/) — auto-generated living documentation for the
  canonical 8-service sample graph. Regenerate with:
  ```
  sbt "bankmodDocs"
  ```
  or directly:
  ```
  sbt "bankmodApp/runMain bankmod.app.DocsGenerator $(pwd)/docs/bankmod/generated"
  ```
- [`demo-script.md`](./demo-script.md) — 30-minute friendly-architect demo
  script for M9.
- [`mcp-integration.md`](./mcp-integration.md) — how to point Claude Desktop
  at the bankmod MCP endpoint.

## Quick start

1. `sbt bankmodApp/assembly` — produce `target/bankmod-app.jar`.
2. `java -jar target/bankmod-app.jar` — boots the MCP server on
   <http://localhost:8090/mcp>, seeded with the 8-service sample.
3. Optional live-reload:
   `BANKMOD_GRAPH_FILE=examples/bankmod/sample-graph.json java -jar target/bankmod-app.jar`
   — any edit to the JSON file is re-decoded, re-validated, and hot-swapped.
   Regenerate this fixture from the canonical Scala source with `sbt bankmodSeedExample`.

## Module map

| Module | Purpose |
|---|---|
| `bankmod-graph-model` | ADT (`Service`, `Edge`, `Graph`, `Protocol`, `Invariant`) with Iron refinements + zio-blocks-schema |
| `bankmod-graph-validate` | `GraphValidator` — 7 invariant checks |
| `bankmod-graph-render` | Mermaid / D2 / Structurizr / JSON-Schema interpreters + `SampleGraph` |
| `bankmod-mcp-tools` | 6 tools, 1 resource + 4 templates, 3 prompts on `zio-http-mcp` |
| `bankmod-app` | `BankmodMain` (MCP server boot), `ModelWatcher`, `DocsGenerator` |
