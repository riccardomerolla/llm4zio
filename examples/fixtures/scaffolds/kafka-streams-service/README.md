# meridian-streams-scaffold

Bank-provided Kafka Streams / Java 21 scaffold: the empty target that the
llm4zio legacy-modernization flows fill in with event-streaming replacements
for ACE message flows. Build with `mvn verify` (TopologyTestDriver only — no
broker anywhere in the test path).

The topology is built by `Application.buildTopology()`, a pure factory that
tests and the equivalence replay harness (`scripts/replay.sh` →
`com.meridian.replay.ReplayHarness`) drive with TopologyTestDriver. Extracted
specs go under `docs/specs/`; BDD `.feature` files are seeded into
`src/test/resources/features/`.
