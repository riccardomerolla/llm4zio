# Pack: cobol-springboot

source: cobol
scaffold: ../../fixtures/scaffolds/spring-boot-service
sources: .*\.(cbl|CBL|cpy|CPY|jcl|JCL)
programs: .*\.(cbl|CBL|jcl|JCL)
specs-dir: docs/specs
features-dir: src/test/resources/features
replay: scripts/replay.sh

## Gates

- build: mvn -q -B test-compile
- test: mvn -q -B test
- verify: mvn -q -B verify

## Judge

- completeness (0..2): Every business rule, validation, calculation (fees, limits, interest tiers), error path, and side effect (ledger rows, audit rows, reject records) present in the COBOL/JCL source is captured in the specs and BDD scenarios. Score 2 only if nothing material is missing.
- faithfulness (0..2): Every statement in the specs is grounded in the source: amounts, thresholds, status codes, reason codes, rounding, and the ORDER of validations match the code exactly, and nothing is invented or assumed. Score 2 only if fully source-grounded.
- testability (0..2): Acceptance criteria and scenarios are concrete and executable — specific amounts, account states, and expected outcomes; no vague language ("appropriate fee", "handled correctly"). Score 2 only if every scenario is directly encodable as a test.

## Equivalence

- ordering: unordered
- ignore: TIMESTAMP, TS, CREATED_AT

## Coverage: cobol-paragraph

files: .*\.(cbl|CBL)
unit: ^ {7}(\d{4}-[A-Z0-9-]+)\.

## Coverage: jcl-step

files: .*\.(jcl|JCL)
unit: ^//([A-Z0-9]+) +EXEC

## Survey: calls

files: .*\.(cbl|CBL)
unit: CALL '([A-Z0-9]+)'

## Survey: copies

files: .*\.(cbl|CBL)
unit: ^ {6}[ ]*COPY +([A-Z0-9]+)

## Survey: exec-pgm

files: .*\.(jcl|JCL)
unit: EXEC +PGM=([A-Z0-9]+)
