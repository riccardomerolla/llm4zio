---
match: ACCEPT .* FROM DATE
---
ACCEPT FROM DATE is ambient time: inject a Clock; the run date is a parameter of the
use case, never LocalDate.now() inline. This is what makes replay/equivalence
deterministic.
