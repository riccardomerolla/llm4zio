---
files: .*\.java
---
Check the event contracts against the specs: topics and event keys match the
spec's interface table exactly; per-key ordering is preserved (no repartition
that loses the correlation key before an order-sensitive step); a rejected
payment emits exactly one reject event and nothing else; amounts are BigDecimal
end-to-end (no double-backed Serde); destination events carry only the fields
the spec's mapping names — no passthrough of unmapped fields.
