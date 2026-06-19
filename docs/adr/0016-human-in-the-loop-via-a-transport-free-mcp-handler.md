# 16. Human-in-the-loop via a transport-free MCP handler exposed over HTTP

- Status: accepted

## Context
Some flows need a human to answer a question or approve a tool call mid-run (HITL), and steerable interactive coder sessions need to relay questions back to the operator. CLI agents speak the Model Context Protocol, so exposing ask-user/approval as MCP tools lets the agent itself call back for input, but the core handler should not be coupled to a specific transport so it stays testable.

## Decision
Model HITL with Interaction (ask a human), ApprovalPolicy/ApprovalDecision (allow/deny a tool call), and McpServer — a transport-free MCP JSON-RPC handler exposing ask_user and approve tools (flow/McpServer.scala). Drive runs a held AgentSession turn, relaying events and bridging questions. The runner's McpHttpServer binds the handler over zio-http and registers it with a claude agent, and InteractiveCoder/TerminalInteraction route questions/approvals to the operator (powering examples/implement-live.sc).

## Consequences
The MCP logic is unit-testable without a server because transport is separated from the JSON-RPC handler, and the same handler can be bound over HTTP in the runner. HITL is opt-in and secondary — only the live/interactive examples exercise it — so most flows stay fully autonomous. Capability flags (askUser, approval) gate which connectors can participate.
