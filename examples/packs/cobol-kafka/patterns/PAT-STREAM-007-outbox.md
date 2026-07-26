---
match: INSERT INTO|WRITE 
---
A batch that both writes DB rows and emits records must not dual-write from a
stream: either the topology emits events and a downstream projector owns the DB, or
writes go through a transactional outbox. Pick per the spec&apos;s system-of-record
statement — the mainframe ledger owner decides.
