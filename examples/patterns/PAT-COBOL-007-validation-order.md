---
match: GO TO .*-EXIT|EVALUATE TRUE
---
First-failure-wins validation ladders are contract: port as an ordered chain where
the first failing rule short-circuits with its exact reason code. Property to keep:
at most ONE reject reason per input, and its code is the first violated rule in
source order.
