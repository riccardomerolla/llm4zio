---
match: DAILY.*ACCUM|ACCUM.*DAILY
---
Accumulators maintained across programs (daily transfer totals reset by another job)
are shared domain state: model as an explicit entity with named reset semantics, and
document WHICH process resets it — the invariant lives between programs.
