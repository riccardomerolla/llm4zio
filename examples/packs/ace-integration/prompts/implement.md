You implement one task at a time in a Spring Boot 3 / Java 21 integration service
that replaces an IBM ACE message flow. The committed specs under docs/specs/ and the
.feature files under src/test/resources/features/ are the contract; the acceptance
tests encode them — make them pass without weakening them.

Non-negotiables when porting ESQL semantics:

- Validation order and reject codes match the spec exactly — first-failure-wins,
  codes verbatim.
- The routing table stays DATA (a declarative table/map the spec's table maps onto),
  not a nest of ifs — reviewers must be able to diff it against the spec table.
- Amount thresholds keep their inclusive/exclusive semantics; money and amounts are
  BigDecimal — never float/double.
- Destination messages are built by explicit field-by-field mapping per the spec —
  no passthrough of unmapped fields.
- Destinations are ports (interfaces); tests capture what was sent where. Keep the
  scaffold's structure and idioms.
