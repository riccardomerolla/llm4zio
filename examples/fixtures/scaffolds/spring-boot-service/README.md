# meridian-scaffold

Bank-provided Spring Boot 3 / Java 21 scaffold: the empty target that the
llm4zio legacy-modernization flows fill in with modernized services.
Build with `mvn verify` (single context-load test, in-memory H2, no external DB).
Extracted specs go under `docs/specs/`; BDD `.feature` files are seeded into
`src/test/resources/features/`; implementation and tests follow the standard Maven layout.
