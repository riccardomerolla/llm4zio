You implement one task at a time in a Kafka Streams / Java 21 service that replaces
a COBOL batch program with an event-streaming topology — this is the batch-window
kill: continuous processing with the batch's exact business rules. The committed
specs under docs/specs/ and the .feature files under src/test/resources/features/
are the contract; the acceptance tests encode them — make them pass without
weakening them.

The batch→streaming mapping (the paradigm shift, applied mechanically):

- Each input file record becomes one keyed event; the correlation key is the entity
  the batch processed per record (the source account for transfers). Per-key
  ordering replaces the batch's sort step — never repartition away from that key
  mid-topology.
- The batch main loop's paragraph clusters become named topology stages (validate →
  route → post); posted side effects (ledger rows, audit rows) become output events
  per the spec's event-contract table; the reject file becomes a reject topic with
  the same codes.
- Per-account accumulators (daily limits) become keyed state stores updated
  transactionally with the emit; commit-interval restart logic becomes idempotent,
  exactly-once processing.
- The pattern cards tagged in the task's specs (PAT-STREAM-*, PAT-COBOL-*) are the
  translation playbook — follow them, and say in the commit which cards applied.

Non-negotiables carried over from the COBOL:

- Money is BigDecimal scale 2; COMPUTE ROUNDED is RoundingMode.HALF_UP; never
  float/double, never a double-backed Serde.
- Preserve the specs' validation ORDER exactly (first-failure-wins) and reason codes
  verbatim; a rejected event emits ONE reject event and no other output.
- Boundary conditions match the spec: "< 1000.00" means 999.99 qualifies and 1000.00
  does not.
- The topology is built by a pure `Topology buildTopology()` factory driven by
  TopologyTestDriver in tests and in the replay harness — no broker in the test
  path. Keep the scaffold's structure and idioms.

Replay harness (equivalence verification — part of the contract, same rules as tests):

- The scaffold ships scripts/replay.sh; it runs com.meridian.replay.ReplayHarness,
  which YOU implement: read one equivalence vector as JSON on stdin, print a JSON
  array of observations on stdout — nothing else on stdout, ever.
- The vector's "inputs" is a flat string map keyed by the specs' COBOL field names
  ("XFER-AMOUNT", "FROM-ACCT", …) plus "state:"-prefixed pre-existing state
  ("state:ACCOUNT:<id>": account row, "state:RUN-DATE"). Arrange the state (seeded
  state stores), feed exactly one input event through TopologyTestDriver, and emit
  every output event in per-key order as:
    {"type":"message","topic":"<output topic>","key":"<event key>","fields":{...}}
- Amounts as plain scale-2 decimal strings, status and reason codes verbatim.
