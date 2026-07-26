---
match: FEE
---
Threshold fee ladders (flat under X, percent capped over) are a policy value object:
one pure function amount→fee, boundary semantics exactly as the source (< vs <=),
tested at each boundary and cap. Trap: waivers (same-customer) are part of the
policy, not the caller.
