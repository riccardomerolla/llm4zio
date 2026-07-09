Derive the implementation task list for the target Spring Boot integration service
from the spec pack. Constraints:

- The FIRST task must be exactly: encode the BDD scenarios as failing JUnit 5
  acceptance tests against the seeded .feature files, driving the service's
  processing entry point with in-memory inputs/outputs (no production code).
- Early tasks: the message model (typed payment/request/reject types mirroring the
  spec's mappings — money as BigDecimal) and the ports for destinations (interfaces
  the tests can capture; real MQ/HTTP adapters are out of scope for the slice).
- Then one task per rule cluster in spec order: validation chain with reject codes,
  the routing table, destination mappings, thresholds/flags.
- Each task names the spec rules (R1, ...) and scenarios it covers.
