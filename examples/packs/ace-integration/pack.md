# Pack: ace-integration

source: ace
scaffold: ../../fixtures/scaffolds/spring-boot-service
sources: .*\.(msgflow|esql)
specs-dir: docs/specs
features-dir: src/test/resources/features

## Gates

- build: mvn -q -B test-compile
- test: mvn -q -B test
- verify: mvn -q -B verify

## Judge

- completeness (0..2): Every node in the message flow, every routing rule, validation, reject code, queue interface, message mapping, and threshold/flag in the msgflow/ESQL source is captured in the specs and BDD scenarios; the traceability matrix accounts for every flow node and ESQL module. Score 2 only if nothing material is missing.
- faithfulness (0..2): Every statement is grounded in the source: reject codes, routing predicates, currency lists, thresholds, flag values, queue names, and field mappings match the ESQL exactly, and nothing is invented. Score 2 only if fully source-grounded.
- testability (0..2): Scenarios are concrete message-in/outcome-out examples — specific payloads, amounts, currencies, and the exact destination or reject code expected; no vague language. Score 2 only if every scenario is directly encodable as a test.

## Coverage: flow-node

files: .*\.msgflow
unit: <translation xmi:type="utility:ConstantString" string="([^"]+)"/>

## Coverage: esql-module

files: .*\.esql
unit: ^CREATE (?:COMPUTE|FILTER|DATABASE) MODULE (\S+)
