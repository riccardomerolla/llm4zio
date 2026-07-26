---
match: SQLCODE
---
The SQLCODE ladder (0 / +100 / other) maps to Optional-plus-typed-error: +100 is
Optional.empty (not found), 0 is a value, anything else is an exception. Trap:
WHENEVER clauses and per-statement EVALUATEs can differ per call site — port each
site as it is, not one global policy.
