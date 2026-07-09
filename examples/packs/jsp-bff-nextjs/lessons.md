- When splitting a server-rendered app into BFF + SPA, assign every legacy server
  behaviour an explicit owner (BFF or SPA) in the spec BEFORE implementing: rules
  that end up enforced only client-side are the most common regression in this
  migration shape.
- Legacy error message texts are contract at the BFF boundary: return them verbatim
  in error responses so the SPA (and any other consumer) can assert them.
