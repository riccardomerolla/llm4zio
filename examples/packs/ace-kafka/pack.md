# Pack: ace-kafka

source: ace
scaffold: ../../fixtures/scaffolds/kafka-streams-service
sources: .*\.(msgflow|esql)
programs: .*\.(msgflow|esql)
specs-dir: docs/specs
features-dir: src/test/resources/features
replay: scripts/replay.sh

## Gates

- build: mvn -q -B test-compile
- test: mvn -q -B test
- verify: mvn -q -B verify

## Judge

- completeness (0..2): Every node in the message flow, every routing rule, validation, reject code, interface, message mapping, and threshold/flag in the msgflow/ESQL source is captured in the specs and BDD scenarios — re-expressed as event contracts (topics, keys, event payloads); the traceability matrix accounts for every flow node and ESQL module. Score 2 only if nothing material is missing.
- faithfulness (0..2): Every statement is grounded in the source: reject codes, routing predicates, currency lists, thresholds, flag values, and field mappings match the ESQL exactly, and nothing is invented — renaming a queue to a topic is mapping, changing its meaning is invention. Score 2 only if fully source-grounded.
- testability (0..2): Scenarios are concrete event-in/events-out examples — specific payloads, amounts, currencies, correlation keys, and the exact output topic or reject code expected; no vague language. Score 2 only if every scenario is directly encodable as a TopologyTestDriver test.

## Equivalence

- ordering: per-key
- ignore: TIMESTAMP, TS, PROCESSED_AT

## Coverage: flow-node

files: .*\.msgflow
unit: <translation xmi:type="utility:ConstantString" string="([^"]+)"/>

## Coverage: esql-module

files: .*\.esql
unit: ^CREATE (?:COMPUTE|FILTER|DATABASE) MODULE (\S+)
