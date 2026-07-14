# Pack: jsp-nextjs

source: jsp
scaffold: ../../fixtures/scaffolds/nextjs-spa
sources: .*\.(jsp|java|xml)
programs: .*\.(jsp|java)
specs-dir: docs/specs
features-dir: features

## Gates

- build: npm test
- test: npm test

## Judge

- completeness (0..2): Every screen, servlet mapping (web.xml url-pattern), navigation path, validation rule, user-facing message, and data write in the JSP/servlet source is captured in the specs and BDD scenarios. Score 2 only if nothing material is missing.
- faithfulness (0..2): Every statement is grounded in the source: validation thresholds, exact message texts, redirect targets, session behaviour, and which table each action writes match the code, and nothing is invented. Score 2 only if fully source-grounded.
- testability (0..2): Scenarios are concrete user journeys — specific accounts, amounts, and the exact message or destination screen expected; no vague language ("shows an error", "handled gracefully"). Score 2 only if every scenario is directly encodable as a test.

## Coverage: servlet-url

files: .*web\.xml
unit: <url-pattern>([^<]+)</url-pattern>

## Coverage: jsp-form

files: .*\.jsp
unit: action="([^"]+)"
