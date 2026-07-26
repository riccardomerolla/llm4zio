---
match: REJECT
---
The reject file becomes a reject topic: same codes, same reasons, one reject event
per failed input event, keyed like the input. Downstream consumers replace the
morning reject-report job; a rejected event NEVER also produces output events.
