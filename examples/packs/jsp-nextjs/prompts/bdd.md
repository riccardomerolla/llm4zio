Write Gherkin .feature files encoding the specs as executable user journeys — these
become the acceptance tests of the new SPA.

- One feature per screen/flow.
- User vocabulary, not servlet vocabulary: "When she submits a transfer of 3000.00,
  Then she is asked to confirm", not "doPost forwards to confirm.jsp".
- Error messages are asserted VERBATIM — they are contract.
- Concrete values everywhere: real account numbers, amounts, statuses.
- Cover: each screen's happy path, EVERY validation rule and its message, threshold
  boundaries (a rule at 2500.00 gets scenarios at 2500.00 and 2500.01 per its
  inclusive/exclusive semantics), confirmation flows, and the state that must
  survive navigation (what hidden fields carried).
