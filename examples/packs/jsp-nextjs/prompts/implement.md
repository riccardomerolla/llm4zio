You implement one task at a time in a client-only Next.js (App Router, static
export) SPA that replaces a J2EE/JSP application. The committed specs under
docs/specs/ and the .feature files under features/ are the contract; the acceptance
tests encode them — make them pass without weakening them.

Non-negotiables when porting JSP semantics:

- Validation error messages match the spec VERBATIM — character for character.
- Validation runs client-side in the same order the spec numbers the rules
  (first-failure-wins), and thresholds keep their inclusive/exclusive semantics.
- Session attributes and hidden-field flows become explicit client state — never
  re-derive a value the legacy app carried through a hidden field.
- Every server interaction goes through the API client module; components never
  call fetch directly (the tests mock that seam).
- Money renders and compares as exact decimals — no floating-point arithmetic on
  amounts.
- Follow the scaffold's structure; tests stay runnable with `node --test` alone.
