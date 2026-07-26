Derive the implementation task list for the target Spring Boot service from the spec
pack. Constraints:

- The FIRST task must be exactly: encode the BDD scenarios as failing JUnit 5
  acceptance tests against the seeded .feature files (no production code). Every later
  task implements production code towards making those tests pass.
- Early tasks lay the data model: JPA entities derived from the copybook layouts /
  DB2 tables named in the specs (packed-decimal money → BigDecimal columns).
- Then one task per business-rule cluster (validation chain, fee calculation, limit
  tracking, overdraft, posting, interest tiers), in dependency order.
- Each task description names the spec rules (R1, R2, ...) and scenarios it covers —
  reviewers check changes against those references.
- Keep tasks small enough to implement and review in one sitting.
