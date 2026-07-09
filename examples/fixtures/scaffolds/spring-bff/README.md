# meridian-bff

Bank-provided BFF + SPA monorepo scaffold: the empty target the llm4zio
legacy-modernization flows fill in with the modernized web channel.
Backend: Spring Boot 3 / Java 21 (web + webflux + data-jpa, in-memory H2,
single context-load test). Frontend: `frontend/` client-only Next.js
(`node --test` smoke test, no install needed). Gate: `scripts/test.sh`
(= `mvn -q -B test` then `cd frontend && npm test`). Extracted specs go under
`docs/specs/`; BDD `.feature` files under `src/test/resources/features/`.
