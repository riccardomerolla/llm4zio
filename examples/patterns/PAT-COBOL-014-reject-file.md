---
match: REJECT
---
A reject file with code+reason is a first-class output: map to a reject record
entity (code, reason, original payload) written through a port, with codes verbatim.
The reject set is closed — port it as an enum and cover every code with a test.
