---
match: OD-FLOOR|OVERDRAFT
---
Overdraft floors including fees are guard clauses with exact arithmetic: compute the
post-transaction balance INCLUDING all fees the source includes before comparing to
the floor. Trap: the OD fee itself may count against the floor — read the COMPUTE
order, not the comment.
