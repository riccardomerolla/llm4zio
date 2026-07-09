Derive the implementation task list for the BFF+SPA monorepo (Spring Boot backend
at the root, Next.js under frontend/) from the spec pack. Constraints:

- The FIRST task must be exactly: encode the BDD scenarios as failing JUnit 5
  acceptance tests against the seeded .feature files, driving the BFF API (no
  production code).
- Early tasks: BFF request/response DTOs per the spec's API contract, then one task
  per flow implementing the BFF endpoint with authoritative validation (verbatim
  messages, spec rule order) and data access.
- Later tasks: SPA screens under frontend/ consuming the BFF, mirroring validation
  for UX, with node:test coverage mocking the BFF at the fetch seam.
- Each task names the spec rules (R1, ...) and scenarios it covers.
