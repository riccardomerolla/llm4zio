---
match: ^ {6,}COPY  *[A-Z]
---
One copybook = one record type shared across programs: generate a single Java record
per copybook and reuse it, mirroring COPY REPLACING prefixes as field-identical
wrapper types. Trap: two programs copying the same book may honor different subsets
of fields — the SPEC, not the layout, says which fields are behaviour.
