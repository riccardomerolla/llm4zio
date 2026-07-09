Write Gherkin .feature files encoding the specs as executable scenarios — these
drive the BFF's acceptance tests (and the SPA mirrors them).

- One feature per screen/flow; scenarios exercise behaviour through the BFF API
  (Given an account state, When the client posts a transfer of 3000.00, Then the
  response requires confirmation) in user-domain vocabulary.
- Error messages asserted VERBATIM — the BFF returns the exact legacy texts.
- Concrete values everywhere; threshold boundaries get scenarios on both sides.
- Cover: each flow's happy path, EVERY validation rule and its message,
  confirmation flows, and what lands in the data store on success.
