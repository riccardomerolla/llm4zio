---
match: RECORD CONTAINS|RECORDING MODE
---
Fixed-width record layouts (LRECL) are wire formats: one explicit parser/serializer
per layout at the boundary, tested against byte-exact golden records; inland types
are domain records. Never index into strings mid-service.
