# Roadmap v2 — Consolidated Milestones + MCP Federation

**Date:** 2026-03-04
**Status:** Approved
**Supersedes:** [2026-02-23-roadmap.md](2026-02-23-roadmap.md)

## Context

The foundation work is complete:
- EclipseStore persistence with event sourcing (15 issues closed)
- Git worktree CLI agent runs with Docker sandbox (16 issues closed)
- M1: Core Platform — channels, models, sessions, routing (4 issues closed)
- M1: Tool Calling — all 3 providers + UX (6 issues closed)
- Agent Orchestration Platform — interactive sessions, git visibility, issue workflow, agent management (20 issues closed)
- ADT Event-Sourced Persistence with BCE restructure (7 phases closed)

30 open issues remain across 6 old milestones. This roadmap consolidates them into 5 focused milestones and adds a new MCP/Federation milestone.

## Architecture Decision: Multi-Gateway Coordination

**Approach: Hybrid — MCP for Tool Interface + Internal REST for Coordination**

- **MCP Server** on each gateway: exposes tools for external LLM clients (Claude Code, Cursor, etc.) to interact with the gateway — assign issues, run agents, query status.
- **Internal REST API** for gateway-to-gateway coordination: peer discovery, leader election, task delegation.
- **Leader gateway** receives tasks and dispatches to the best-fit peer based on agent capabilities and current load.
- **Peers** report status back via webhooks.

Why not pure MCP for everything? MCP is designed for LLM↔tool integration, not service-to-service orchestration. It lacks service discovery, consensus, and load balancing. A lightweight REST mesh is simpler and more appropriate for peer coordination.

---

## Milestone 1: Streaming & Structured Output

**Goal:** Finish the LLM provider quality layer.

| Priority | Issue | Title |
|----------|-------|-------|
| P0 | #318 | True SSE streaming for OpenAI provider |
| P0 | #319 | True SSE streaming for Anthropic provider |
| P1 | #321 | Wire executeStructured into ChatController |
| P1 | #322 | [UX] Structured output mode toggle in chat composer |

---

## Milestone 2: Observability & Operations

**Goal:** Production-readiness — metrics, fallback, service management.

| Priority | Issue | Title |
|----------|-------|-------|
| P0 | #323 | Instrument all LLM calls with LlmMetrics |
| P0 | #324 | Implement provider fallback chain |
| P0 | #273 | Gateway service management (daemon, health, RPC) |
| P1 | #325 | [UX] Token usage panel per conversation |
| P1 | #326 | [UX] LLM metrics widget in dashboard |
| P1 | #327 | [UX] Provider fallback configuration |
| P1 | #281 | Usage tracking (tokens, costs, quotas) |
| P2 | #282 | Config versioning (history, diff, rollback) |
| P2 | #280 | Gateway onboarding wizard |

---

## Milestone 3: Agent Intelligence & Memory

**Goal:** Context management, RAG, guardrails — the smart agent layer.

| Priority | Issue | Title |
|----------|-------|-------|
| P0 | #328 | Wire AgentDispatcher to ChatController |
| P0 | #329 | Apply context window management to conversations |
| P0 | #334 | Wire EmbeddingService + VectorStore to retrieval |
| P1 | #330 | Persist ConversationThread checkpoints |
| P1 | #335 | Implement guardrails framework |
| P1 | #332 | [UX] Context window indicator |
| P1 | #331 | [UX] Agent browser in sidebar |
| P2 | #333 | [UX] Conversation checkpoint/branching UI |
| P2 | #336 | [UX] Knowledge base management page |
| P2 | #337 | [UX] RAG settings per conversation |
| P2 | #338 | [UX] Guardrails configuration |

---

## Milestone 4: MCP Server & Gateway Federation

**Goal:** External MCP tool interface + multi-gateway coordination.

### MCP Server — Library Layer (llm4zio)

ZIO-native MCP implementation in `llm4zio/src/main/scala/llm4zio/mcp/`:

- **JSON-RPC 2.0 protocol** (`mcp/jsonrpc/`): request/response/error types, ZIO JSON codecs, `JsonRpcHandler` trait
- **MCP message types** (`mcp/protocol/`): initialize, tools/list, tools/call, notifications, `McpContent` ADT, bridge from existing `Tool` → MCP schema
- **McpServer** (`mcp/server/`): registers tools from `ToolRegistry`, handles MCP lifecycle, emits tool change notifications
- **Stdio transport** (`mcp/transport/`): stdin/stdout newline-delimited JSON framing for local clients (Claude Code, Cursor)
- **SSE transport** (`mcp/transport/`): HTTP POST for requests + SSE stream for responses, session management, API key auth

### MCP Server — Gateway Layer

Wire `McpServer` into the gateway, registering gateway-specific tools:
- `assign_issue`, `run_agent`, `get_run_status`, `list_agents`, `list_workspaces`, `search_conversations`, `get_metrics`
- Mount SSE routes on existing HTTP server, add `--mcp-stdio` CLI flag

### Gateway Federation (internal coordination)

- **Gateway Registry**: each gateway registers with a known seed peer via REST. Peers propagate the registry (gossip-style).
- **Leader Election**: simple bully algorithm — highest priority gateway becomes leader. Leader sends heartbeats; if missed, next in line takes over.
- **Task Delegation**: leader receives issues and dispatches to the best-fit peer based on:
  - Agent capabilities match
  - Current load (active runs / max concurrent)
  - Workspace locality (prefer peer that has the repo cloned)
- **Status Reporting**: peers push run status updates to leader via webhook. Leader aggregates for the federation dashboard.

| Priority | Issue | Title | Layer |
|----------|-------|-------|-------|
| P0 | #390 | [llm4zio] JSON-RPC 2.0 protocol — codec, request/response/error types | library |
| P0 | #391 | [llm4zio] MCP message types — initialize, tools/list, tools/call, notifications | library |
| P0 | #392 | [llm4zio] McpServer trait — register tools, handle MCP lifecycle | library |
| P1 | #393 | [llm4zio] Stdio transport — stdin/stdout JSON-RPC framing | library |
| P1 | #394 | [llm4zio] SSE transport — HTTP MCP endpoint for remote clients | library |
| P0 | #385 | MCP Server — wire gateway tools and start MCP endpoint | gateway |
| P0 | #386 | Gateway Registry — peer discovery and registration | gateway |
| P1 | #387 | Leader Election — lightweight leader/follower protocol | gateway |
| P1 | #388 | Task Delegation — dispatch issues to best-fit peer | gateway |
| P2 | #389 | Federation Dashboard — view peers, agents, load, delegated tasks | gateway |
| P2 | #278 | Security and approvals (agent action gates) | gateway |
| P2 | #275 | Pairing system (user approval for channel access) | gateway |

---

## Milestone 5: Extensibility & Power Users

**Goal:** Skills, scheduling, TUI.

| Priority | Issue | Title |
|----------|-------|-------|
| P1 | #271 | Cron jobs (scheduled messages and system events) |
| P1 | #270 | Skills system (pluggable capabilities marketplace) |
| P2 | #279 | System events and heartbeat |
| P2 | #277 | Terminal UI (TUI) |

---

## Cleanup

Close stale milestones (all issues already done or superseded):
- Phase 1: Core Infrastructure
- Phase 2: Gemini CLI Integration
- Phase 3: Agent Implementations
- Phase 4: Orchestration & State
- Phase 5: Validation & Testing
- Phase 6: Documentation & Polish
- OpenClaw Feature Parity

Close superseded milestones (issues reassigned above):
- M1: Core Platform (all closed)
- M1: Tool Calling (all closed)
- M2: Streaming Quality + Structured Output → merged into new M1
- M2: Operations & Security → merged into new M2 + M4
- M3: Observability + Provider Fallback → merged into new M2
- M3: Advanced Features → becomes new M5
- M4: Agent System + Conversation Memory → merged into new M3
- M5: RAG + Guardrails → merged into new M3
- M-Workspaces (all closed)
- EclipseStore Persistence Migration (all closed)

---

## Dependency Graph

```
M1: Streaming & Structured Output
  #318 OpenAI SSE ──┐
  #319 Anthropic SSE ┤
  #321 Structured ───┤
  #322 UX toggle ────┘

M2: Observability & Operations  ←── M1 (metrics need streaming)
  #323 LlmMetrics ──────┐
  #324 Fallback chain ──┤
  #273 Service mgmt ────┤
  #325-327 UX ──────────┤
  #281 Usage tracking ──┤
  #282 Config versioning┤
  #280 Onboarding ──────┘

M3: Agent Intelligence & Memory  ←── M1 (RAG uses streaming)
  #328 AgentDispatcher ─┐
  #329 Context mgmt ────┤
  #334 RAG retrieval ───┤
  #330 Checkpoints ─────┤
  #335 Guardrails ──────┤
  #331-338 UX ──────────┘

M4: MCP & Federation  ←── M2 (needs service mgmt), M3 (needs agent system)
  MCP Server ───────────┐
  Gateway Registry ─────┤
  Leader Election ──────┤
  Task Delegation ──────┤
  Federation Dashboard ─┤
  #278 Security ────────┤
  #275 Pairing ─────────┘

M5: Extensibility  (independent, can start anytime)
  #271 Cron ────────────┐
  #270 Skills ──────────┤
  #279 System events ───┤
  #277 TUI ─────────────┘
```
