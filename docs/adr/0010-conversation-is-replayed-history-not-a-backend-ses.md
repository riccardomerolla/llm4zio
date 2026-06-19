# 10. Conversation is replayed history, not a backend session token

- Status: Accepted

## Context
Maintaining a coder conversation across many tasks requires continuity. Some backends offer resumable session tokens, but relying on them would couple the flow to provider-specific session features and break determinism in tests. A backend-neutral way to model an ongoing conversation was needed.

## Decision
Model Chat (flow/Chat.scala) as an accumulating List[Message] held in a Ref and threaded through executeStreamWithHistory on every turn — there is no backend session token; continuity is the replayed history. The richer ConversationThread/Checkpoint/PromptRegistry vocabulary in core/Conversation.scala is intentionally not used by the flow layer.

## Alternatives considered
Use provider resumable-session tokens — rejected because it couples the flow to backend-specific features (only some connectors declare resumableSessions) and is not reproducible under the Mock provider. Use core's ConversationThread/Checkpoint/ConversationStore — left present but not wired, as it is heavier than the flow needs and would duplicate state ownership. Stateless one-shot prompts with no history — rejected because the coder would lose all working memory across tasks.

## Consequences
Conversation works identically across every provider and is fully reproducible with the Mock provider. The cost is resending growing history each turn (token cost grows with conversation length). It also leaves a parallel, unused conversation vocabulary in core — a remnant model contributors should not mistake for the wired path.
