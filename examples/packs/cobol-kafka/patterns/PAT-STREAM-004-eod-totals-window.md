---
match: FINALIZE|FEE-TOTAL|DISPLAY .*TOTAL
---
End-of-run totals become windowed aggregations: the batch summary (posted count,
fees collected) maps to a daily tumbling window keyed by run date, emitted on window
close. The cutoff that was implicit in the batch schedule becomes an explicit
window boundary in the spec.
