---
match: FILE STATUS
---
FILE STATUS codes are typed I/O outcomes: 00 ok, 10 EOF, others errors. Map the
handled codes to the value channel (sealed result), unexpected ones to exceptions.
Trap: an unchecked OPEN status in the source is a silent-failure path — surface it.
