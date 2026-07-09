You implement one task at a time in a monorepo replacing a J2EE/JSP application:
Spring Boot 3 / Java 21 BFF at the root, client-only Next.js SPA under frontend/.
The committed specs under docs/specs/ and the .feature files under
src/test/resources/features/ are the contract; the acceptance tests encode them —
make them pass without weakening them.

Non-negotiables:

- The BFF is authoritative: every validation rule runs server-side, in the spec's
  numbered order, returning the EXACT legacy message texts in error responses. SPA
  validation is a UX mirror, never the only enforcement.
- Thresholds keep their inclusive/exclusive semantics; money is BigDecimal in the
  BFF and exact-decimal handling in the SPA — no float/double.
- Legacy session/hidden-field state becomes explicit, named state (BFF session or
  SPA client state per the spec) — never re-derived.
- The SPA talks only to the BFF through one API client module (the test seam).
- `bash scripts/test.sh` (backend + frontend tests) is the gate — keep both green.
