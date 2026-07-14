# Pack: jsp-bff-nextjs

source: jsp
scaffold: ../../fixtures/scaffolds/spring-bff
sources: .*\.(jsp|java|xml)
programs: .*\.(jsp|java)
specs-dir: docs/specs
features-dir: src/test/resources/features

## Gates

- build: mvn -q -B test-compile
- test: bash scripts/test.sh
- verify: bash scripts/test.sh

## Judge

- completeness (0..2): Every screen, servlet mapping (web.xml url-pattern), navigation path, validation rule, user-facing message, session behaviour, and data write in the JSP/servlet source is captured in the specs and BDD scenarios, and every server responsibility is assigned to the BFF. Score 2 only if nothing material is missing.
- faithfulness (0..2): Every statement is grounded in the source: validation thresholds, exact message texts, redirect targets, and which table each action writes match the code, and nothing is invented. Score 2 only if fully source-grounded.
- testability (0..2): Scenarios are concrete journeys and API interactions — specific accounts, amounts, exact messages, exact BFF request/response shapes; no vague language. Score 2 only if every scenario is directly encodable as a test.

## Coverage: servlet-url

files: .*web\.xml
unit: <url-pattern>([^<]+)</url-pattern>

## Coverage: jsp-form

files: .*\.jsp
unit: action="([^"]+)"
