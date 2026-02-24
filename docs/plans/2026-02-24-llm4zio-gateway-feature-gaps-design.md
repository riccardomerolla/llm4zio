# LLM Gateway Feature Gaps: Design & Roadmap

**Date:** 2026-02-24
**Status:** Approved
**Approach:** Milestone ladder — vertical slices from library to UX

---

## Context

The `llm4zio` library (local module) is a ZIO-native LLM framework with a rich set of capabilities:
streaming, tool calling, structured output, agent orchestration, RAG, embeddings, context management,
and observability infrastructure. However, most of these capabilities are **architecturally complete
but not wired into the gateway** — either the library methods are stubbed, or the wiring to controllers
and UI is missing.

Comparing against `llm4s` (the reference Scala LLM library) confirms the gap: llm4s treats tool
calling, hybrid RAG, multi-agent orchestration, memory systems, and observability as first-class
production features. This document maps the gaps and defines a milestone ladder to close them.

---

## Gap Analysis

### Current Gateway LLM Surface

The gateway today exposes:
- Basic chat (prompt → response, stored as message)
- Streaming via WebSocket (`chat-chunk` events, `Streaming.cancellable()` wrapper)
- Provider/model selection in `/settings/ai`
- Model capability table (`Chat`, `Streaming`, `ToolCalling`, `StructuredOutput`, `Embeddings` columns)
- `@agent-name` mention syntax (routes to agent metadata only, not real agent execution)

### llm4zio Capability Readiness

| Capability | Library | Wired to Gateway | UX |
|---|---|---|---|
| Basic chat | ✓ Production | ✓ | ✓ |
| Streaming responses | Partial (some providers fake it) | ✓ WebSocket | ✓ Chat |
| Multi-turn conversation | ✓ ConversationThread | Partial | ✓ |
| Tool calling | Stub (returns error) | ✗ | ✗ |
| Structured output | ✓ OpenAI; prompt-based others | ✗ | ✗ |
| Agent execution | ✓ Framework | ✗ | Metadata only |
| Context window management | ✓ Trimming strategies | ✗ | ✗ |
| Conversation checkpoints | ✓ ConversationCheckpoint | ✗ | ✗ |
| Embeddings / RAG | ✓ VectorStore + EmbeddingService | ✗ | ✗ |
| Observability (metrics) | ✓ LlmMetrics infrastructure | ✗ | ✗ |
| Provider fallback | Partial (ConfigAware switching) | ✓ config-level | ✗ UX |
| Guardrails | ✗ Not implemented | ✗ | ✗ |

---

## Design: Milestone Ladder

Each milestone delivers a vertical slice: library implementation → gateway wiring → UX surface.
Milestones are independently shippable and ordered by user-facing impact.

---

### Milestone 1: Tool Calling

**Goal:** Users can equip conversations with tools (file read, web search, code execution).
The LLM can invoke tools mid-conversation; results are shown inline in the chat.

**Library work:**
- Implement `executeWithTools` in OpenAI provider using the `tools` JSON parameter
- Implement `executeWithTools` in Anthropic provider using `tool_use` content blocks
- Implement `executeWithTools` in Gemini provider using function declarations
- Wire `ToolConversationManager.run()` into `ChatController` — replace single `execute()` call
  with the multi-turn tool loop (max 8 iterations); persist tool call + result message pairs

**UX work:**
- Tool call panel in chat view: collapsible blocks showing tool name, arguments, and result
- Tool registry browser in Settings: list built-in tools, enable/disable per conversation or globally

**Issues (6):**
1. `[llm4zio] Implement tool calling in OpenAI provider`
2. `[llm4zio] Implement tool calling in Anthropic provider`
3. `[llm4zio] Implement tool calling in Gemini provider`
4. `[llm4zio] Wire ToolConversationManager into ChatController`
5. `[UX] Tool call panel in chat view`
6. `[UX] Tool registry browser in Settings`

---

### Milestone 2: Streaming Quality + Structured Output

**Goal:** Streaming is genuinely real-time for all providers (no collect-then-emit).
Users can request structured JSON output with a schema.

**Library work:**
- OpenAI provider: parse `data: ...` SSE chunks; remove fake collect-then-stream wrapper
- Anthropic provider: handle `content_block_delta` streaming events
- Gemini provider: use Gemini streaming API endpoint
- Wire `executeStructured` into `ChatController` as a new message mode

**UX work:**
- Structured output mode toggle in chat composer: button to switch free-text ↔ structured JSON;
  schema input field (optional JSON schema); result rendered as formatted JSON block

**Issues (5):**
7. `[llm4zio] True SSE streaming for OpenAI provider`
8. `[llm4zio] True SSE streaming for Anthropic provider`
9. `[llm4zio] True SSE streaming for Gemini provider`
10. `[llm4zio] Wire executeStructured into ChatController (structured output mode)`
11. `[UX] Structured output mode toggle in chat composer`

---

### Milestone 3: Observability + Provider Fallback

**Goal:** Every LLM call is instrumented. Token usage, latency, and error rates are visible.
Operators can configure an ordered fallback chain between providers.

**Library work:**
- Instrument all `execute*` methods with `LlmMetrics` recording (wrap in metrics effect)
- Implement provider fallback chain in `ConfigAwareLlmService`:
  ordered list; auto-switch on `AuthenticationError` / `RateLimitError`

**UX work:**
- Token usage panel per conversation: prompt tokens, completion tokens, estimated cost per message
  and cumulative total
- LLM metrics widget in `/settings/system`: total requests, avg latency, error rate, top provider
- Provider fallback configuration in `/settings/ai`: ordered fallback list; "Test" button per provider

**Issues (5):**
12. `[llm4zio] Instrument all LLM calls with LlmMetrics`
13. `[llm4zio] Implement provider fallback chain`
14. `[UX] Token usage panel per conversation`
15. `[UX] LLM metrics widget in /settings/system dashboard`
16. `[UX] Provider fallback configuration in /settings/ai`

---

### Milestone 4: Agent System + Conversation Memory

**Goal:** The agent framework becomes real: `@agent-name` routes to actual agent execution.
Users can see the context window state and manage conversation memory.

**Library work:**
- Wire `AgentDispatcher` to `ChatController`: when `@agent-name` detected, call `Agent.execute()`
  with `AgentContext`; store result and tool traces
- Apply `ContextManagement` trimming strategies to conversations before each LLM call
- Persist `ConversationCheckpoint` to EclipseStore; restore on conversation resume

**UX work:**
- Agent browser in `/settings` or chat sidebar: list registered agents, capabilities, status;
  click to start a conversation with a specific agent
- Context window indicator in chat view: token usage bar; trimming strategy selector (DropOldest,
  SlidingWindow, PriorityBased, Summarize)
- Conversation checkpoint / branching UI: Save checkpoint button; restore from checkpoint;
  branch conversation from any point

**Issues (6):**
17. `[llm4zio] Wire AgentDispatcher to ChatController`
18. `[llm4zio] Apply context window management to conversations`
19. `[llm4zio] Persist ConversationThread checkpoints to EclipseStore`
20. `[UX] Agent browser in settings/chat sidebar`
21. `[UX] Context window indicator in chat view`
22. `[UX] Conversation checkpoint / branching UI`

---

### Milestone 5: RAG + Guardrails

**Goal:** Conversations can be grounded in a knowledge base. Content guardrails protect against
problematic inputs and outputs.

**Library work:**
- Wire `EmbeddingService` + `VectorStore` to conversation flow: on each user message, retrieve
  top-K relevant chunks; prepend as context before LLM call
- Implement guardrails framework: input/output validation hooks (length, content policy, JSON schema)

**UX work:**
- Knowledge base management page: upload documents; view indexed chunks; delete; search
- RAG settings per conversation: toggle on/off; select knowledge base; adjust top-K
- Guardrails configuration in `/settings/ai`: enable/disable; configure rules per provider

**Issues (5):**
23. `[llm4zio] Wire EmbeddingService + VectorStore to conversation retrieval`
24. `[llm4zio] Implement guardrails framework`
25. `[UX] Knowledge base management page`
26. `[UX] RAG settings per conversation`
27. `[UX] Guardrails configuration in /settings/ai`

---

## Architecture Notes

### Tool Calling Flow (M1)
```
ChatController.POST /chat/{id}/messages
  → parse @tool-enable hints from message metadata
  → ToolConversationManager.run(prompt, enabledTools, llmService)
      → llmService.executeWithTools(prompt, tools)         // provider impl
      → if ToolCallResponse.toolCalls.nonEmpty:
          toolRegistry.executeAll(toolCalls)
          append tool messages to history
          loop (max 8 iterations)
  → persist full message chain (user + tool calls + tool results + assistant)
  → WebSocket broadcast: chat-chunk events per delta + tool-call events
```

### Streaming Architecture (M2)
```
Provider.executeStream(prompt)
  → HTTP client: chunked transfer / SSE connection
  → ZStream[LlmChunk] (real deltas, not collected)
  → Streaming.toSSE() for SSE clients
  → existing WebSocket path unchanged (already uses ZStream)
```

### Observability Wiring (M3)
```
LlmService.execute*(...)
  → LlmMetrics.recordRequest(provider, model)
  → actual provider call
  → LlmMetrics.recordSuccess(tokens, latency) or recordError(error)
  → GatewayService.metrics exposes per-provider LlmMetrics.Snapshot
```

### Context Management (M4)
```
ChatController.buildHistory(conversationId)
  → load ConversationThread from EclipseStore
  → ContextManagement.trim(thread, strategy, maxTokens)
  → pass trimmed Message list to llmService.executeWithHistory
```

---

## Key Files

| File | Relevance |
|---|---|
| `llm4zio/src/main/scala/core/LlmService.scala` | Core trait — all methods to implement |
| `llm4zio/src/main/scala/tools/Tool.scala` | Tool type + ToolSandbox |
| `llm4zio/src/main/scala/core/ContextManagement.scala` | ToolConversationManager, trimming |
| `llm4zio/src/main/scala/core/Conversation.scala` | ConversationThread, checkpoints |
| `llm4zio/src/main/scala/observability/LlmMetrics.scala` | Metrics infrastructure |
| `llm4zio/src/main/scala/rag/` | EmbeddingService, VectorStore |
| `llm4zio/src/main/scala/agents/Agent.scala` | Agent trait, AgentCoordinator |
| `src/main/scala/conversation/boundary/ChatController.scala` | Gateway wiring point |
| `src/main/scala/shared/web/` | All view files |
| `src/main/scala/gateway/control/GatewayService.scala` | Message routing |

---

## Success Criteria

- M1 done: A conversation with tool calling enabled can invoke `readFile` and show the result inline
- M2 done: Streaming tokens appear in real-time from OpenAI, Anthropic, and Gemini; structured JSON
  output mode renders a formatted result block
- M3 done: `/settings/system` shows live LLM metrics; each message shows token counts
- M4 done: `@coder` mention routes to the Coder agent and shows multi-turn tool execution; context
  window bar is visible
- M5 done: A knowledge base can be uploaded and queried; RAG toggle enriches messages with retrieved
  context
