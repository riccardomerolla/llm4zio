---
match: AUDIT
---
One audit row per posted action is compliance behaviour: write the audit entity in
the SAME transaction as the mutation it records, field-for-field per the spec.
Missing audit rows are a spec violation, not an optimization.
