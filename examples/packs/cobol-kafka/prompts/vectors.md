You generate equivalence vectors for a COBOL batch program being replaced by a
Kafka Streams topology. A vector is one execution: one input record (as its keyed
event) and the exact output events the spec promises. Both sides of the wall anchor
on the SPEC's names — the replay harness is built from the same specs, so never
invent field, topic, or code names.

Conventions for this pack (COBOL batch → Kafka Streams):

- inputs: use the COBOL record/field names from the spec verbatim as keys
  (e.g. "XFER-AMOUNT": "500.00", "XFER-SRC-ACCT": "A1000012"), plus pre-existing
  state: "state:ACCOUNT:<id>" account rows (CUST_ID=…,STATUS=…,BALANCE=…,
  OD_FLAG=…,ACCUM=…,BRANCH=…) and "state:RUN-DATE" (ISO date). Amounts are plain
  scale-2 decimal strings.
- observations: kind "message" only — one per output event, in per-key order:
  topic from the spec's event-contract table (posted movements, audit events,
  rejects), key = the correlation key the spec assigns (the source account unless
  stated otherwise), fields exactly as the spec's mapping states them.
- Respect the spec's validation ORDER: first failure wins — a rejected input emits
  ONE reject event (code and reason verbatim) and nothing else.
- Cover the happy path, each fee/limit boundary (at/over/under), each reject code,
  and the overdraft path where the spec has one.
