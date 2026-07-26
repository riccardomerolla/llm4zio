You implement one task at a time in a Kafka Streams / Java 21 service that replaces
an IBM ACE message flow with an event-streaming topology. The committed specs under
docs/specs/ and the .feature files under src/test/resources/features/ are the
contract; the acceptance tests encode them — make them pass without weakening them.

Non-negotiables when porting ESQL semantics to a topology:

- Queues become topics per the spec's interface mapping: one input topic for the
  flow's trigger, one output topic per destination (CICS bridge, SEPA gateway,
  rejects). The event KEY is the correlation identity the spec names (the payment's
  debtor account unless the spec says otherwise) — per-key ordering is contract.
- Validation order and reject codes match the spec exactly — first-failure-wins,
  codes verbatim; a rejected payment emits ONE reject event and nothing else.
- The routing table stays DATA (a declarative map the spec's table maps onto), not
  a nest of ifs — reviewers must be able to diff it against the spec table.
- Amount thresholds keep their inclusive/exclusive semantics; money is BigDecimal —
  never float/double, and never a double-backed Serde.
- Destination events are built by explicit field-by-field mapping per the spec — no
  passthrough of unmapped fields.
- The topology is built by a pure `Topology buildTopology()` factory the tests and
  the replay harness drive with TopologyTestDriver — no broker in the test path.
  Keep the scaffold's structure and idioms.

Replay harness (equivalence verification — part of the contract, same rules as tests):

- The scaffold ships scripts/replay.sh; it runs com.meridian.replay.ReplayHarness,
  which YOU implement: read one equivalence vector as JSON on stdin, print a JSON
  array of observations on stdout — nothing else on stdout, ever.
- The vector's "inputs" is a flat string map: the inbound event's fields using the
  specs' names, plus "topic:" for the input topic and "key:" for the event key.
  Feed exactly one input event through TopologyTestDriver and emit every output
  event, in per-key order, as:
    {"type":"message","topic":"<output topic>","key":"<event key>","fields":{...}}
- Amounts as plain scale-2 decimal strings, codes verbatim from the specs.
