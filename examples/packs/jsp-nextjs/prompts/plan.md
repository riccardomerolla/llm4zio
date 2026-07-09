Derive the implementation task list for the client-only Next.js SPA from the spec
pack. Constraints:

- The FIRST task must be exactly: encode the BDD scenarios as failing acceptance
  tests under tests/ using node:test, driving the screen components/logic with the
  expected API mocked at the fetch boundary (no production code).
- Early tasks: the typed API client module implementing the spec's "Expected API
  contract" (all calls go through it — it is the seam the tests mock), and shared
  state that replaces session/hidden-field flows.
- Then one task per screen/flow: page component, validation with VERBATIM message
  texts, navigation, confirmation steps.
- Each task names the spec rules (R1, ...) and scenarios it covers.
- No server-side code: the scaffold exports statically (S3); everything dynamic
  goes through the API client.
