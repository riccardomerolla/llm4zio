# 10. Conversation is replayed history, not a backend session token

- Status: accepted

## Context
Maintaining a coder conversation across many tasks requires continuity. Some backends offer resumable session tokens, but relying on them would couple the flow to provider-specific session features and break determinism in tests. A backend-neutral way to model an ongoing conversation was needed.

## Decision
Model Chat (flow/Chat.scala) as an accumulating List[Message] held in a Ref and threaded through executeStreamWithHistory on every turn — there is no backend session token; continuity is the replayed history. The richer ConversationThread/Checkpoint/PromptRegistry vocabulary in core/Conversation.scala is intentionally not used by the flow layer.

## Consequences
Conversation works identically across every provider and is fully reproducible with the Mock provider. The cost is resending growing history each turn (token cost grows with conversation length). It also leaves a parallel, unused conversation vocabulary in core — a remnant model contributors should not mistake for the wired path.
