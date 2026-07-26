---
match: DECLARE .* CURSOR|FETCH 
---
DB2 cursor loops map to pageable repository queries or streams; the loop body is a
per-row use case. Trap: cursors see uncommitted own-writes under the same unit of
work — if the source relies on that, the spec must say so explicitly.
