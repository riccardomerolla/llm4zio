---
match: STRING .*DELIMITED
---
STRING…DELIMITED builds fixed-format text: map to an explicit formatter with the
exact widths/delimiters, tested against golden strings. Trap: DELIMITED BY SIZE vs
BY SPACE differ on trailing blanks — match the source.
