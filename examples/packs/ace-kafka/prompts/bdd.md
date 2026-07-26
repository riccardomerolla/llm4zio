Write Gherkin .feature files encoding the specs as executable message-in/outcome-out
scenarios — these become the acceptance tests of the new integration service.

- One feature per flow (or per coherent rule cluster within one).
- Scenario shape: Given a payment event with concrete field values and key, When
  the topology processes it, Then an event is emitted on <output topic> with
  <mapped fields / flags> — or a reject event with code <code>.
- Business vocabulary with the codes kept in the Then step (e.g. `Then the payment
  is rejected with code V01 (unsupported currency)`).
- Concrete values everywhere: real amounts, currencies, account numbers, IBANs.
- Cover: every routing branch, EVERY reject code, every threshold boundary (a flag
  at 10000.00 gets scenarios at 9999.99 and 10000.00 per its semantics), and the
  field mappings of each destination message.
