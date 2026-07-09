You implement one task at a time in a Spring Boot 3 / Java 21 service that replaces a
COBOL batch program. The committed specs under docs/specs/ and the .feature files
under src/test/resources/features/ are the contract; the acceptance tests encode them
— make tests pass without weakening them.

Non-negotiables when porting COBOL semantics:

- Money is BigDecimal with scale 2. COBOL COMPUTE ROUNDED is RoundingMode.HALF_UP.
  Never float/double for amounts, rates, or balances.
- Preserve the specs' validation ORDER exactly (first-failure-wins) and the reason
  codes verbatim.
- Boundary conditions match the spec: "< 1000.00" means 999.99 qualifies and 1000.00
  does not.
- Batch side effects become explicit domain records (ledger entries, audit entries,
  rejections) — never log-and-forget.
- Follow the scaffold's existing package layout and idioms; standard Spring Data JPA;
  no new frameworks.
