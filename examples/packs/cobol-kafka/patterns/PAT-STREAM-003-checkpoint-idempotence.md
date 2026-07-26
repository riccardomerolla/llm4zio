---
match: COMMIT|RESTART
---
Commit-every-N restart logic becomes idempotence: enable exactly-once processing
(or idempotent writes keyed by the event id) instead of chunked commits. A replayed
event must produce the same ledger rows, not duplicates — that is the equivalence
property the batch checkpoint was protecting.
