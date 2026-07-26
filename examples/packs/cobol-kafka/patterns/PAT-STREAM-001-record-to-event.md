---
match: READ |ORGANIZATION IS SEQUENTIAL
---
Each input file record becomes one keyed event: the record layout maps to the event
payload, and the correlation key is the entity the batch processes per record (the
source account for transfers). The event schema lives in the spec&apos;s interface
table — never invent fields the record does not carry.
