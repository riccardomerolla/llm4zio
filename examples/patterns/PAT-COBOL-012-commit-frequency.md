---
match: COMMIT
---
COMMIT every N records is restart-safety, not business logic: in the target, one
transaction per unit of work (per transfer) replaces chunked commits, and the spec
notes the difference. Trap: never reproduce partial-batch visibility as behaviour —
it was an artifact of the mainframe transaction monitor.
