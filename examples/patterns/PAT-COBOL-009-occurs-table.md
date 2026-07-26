---
match: OCCURS
---
OCCURS tables are fixed-capacity lists: map to List<T> with the capacity as a
validated invariant, not an array with sentinel rows. Trap: DEPENDING ON means the
effective size is data — carry it, do not scan for blanks.
