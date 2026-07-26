Write Gherkin .feature files encoding the specs' business rules as executable
scenarios — these become the acceptance tests of the new Spring Boot service.

- One feature file per COBOL program (or per coherent rule cluster within one).
- Business vocabulary, not mainframe vocabulary: "the transfer is rejected because the
  source account is frozen", not "reject code 11 is written to REJFILE" — but keep the
  reason code in the Then step so traceability holds (e.g. `Then the transfer is
  rejected with reason code 11 (source account frozen)`).
- Every scenario uses concrete values: real amounts, statuses, and expected balances
  after posting — never "a large amount" or "the correct fee".
- Cover: the happy path, EVERY reject reason, every fee tier and its boundary values
  (e.g. exactly 1000.00), limit boundaries (exactly at the daily limit), overdraft
  floor boundaries, and rounding-sensitive amounts.
- Boundary discipline: when a rule says "< 1000.00", write scenarios at 999.99 and
  1000.00.
