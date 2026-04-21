# bankmod — MCP Integration

Point an MCP client at the bankmod server to read resources, run tools, and
trigger prompts.

## Claude Desktop

Add to your Claude Desktop config (macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "bankmod": {
      "command": "curl",
      "args": ["-N", "http://localhost:8090/mcp"]
    }
  }
}
```

Restart Claude Desktop. You should see `bankmod` in the tools/resources list.

## Surface summary

**Resources**

| URI | Purpose |
|---|---|
| `graph://full` | Entire graph as JSON |
| `graph://service/{id}` | Single service JSON |
| `graph://edge/{from}/{to}/{port}` | Single edge JSON |
| `graph://invariant/{name}` | Catalog entry for a named invariant |
| `graph://slice/{id}/{depth}` | N-hop neighborhood (depth 1..5) |

**Tools**

| Name | Purpose |
|---|---|
| `queryDependencies` | BFS neighborhood from a service out to a depth |
| `renderDiagram` | Render graph (full or slice) as mermaid / d2 / structurizr / json |
| `validateEvolution` | Decode + validate a proposed patch — does NOT commit |
| `proposeService` | Decode + validate + atomically swap into store on success |
| `explainInvariantViolation` | Human-readable explanation and fix suggestions for an invariant error |
| `listInvariants` | Full catalog of enforced invariants |

**Prompts**

| Name | Use case |
|---|---|
| `addService` | Propose adding a new service to the graph |
| `migrateEndpoint` | Migrate a service's endpoint from one port to another |
| `introduceEvent` | Introduce or promote an event-driven edge |

## Notifications

When `proposeService` commits or `ModelWatcher` reloads, the server pushes
`notifications/resources/updated` on any subscribed URIs — clients that
implement SSE see the update within one second.

## Running

```bash
# Default — seed from SampleGraph.sample, no live reload.
sbt bankmodApp/run

# With live-reload on a JSON fixture:
export BANKMOD_GRAPH_FILE=$(pwd)/examples/bankmod/sample-graph.json
sbt bankmodApp/run
```

The server logs its endpoint on boot:

```
bankmod v0.0.1 — MCP endpoint: http://localhost:8090/mcp
```
