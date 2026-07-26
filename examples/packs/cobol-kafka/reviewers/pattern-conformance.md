---
files: .*\.(java|kt|scala)
---
The specs cite pattern cards (PAT-… ids) — the translation playbook for known
legacy idioms. For each cited card, check the implementation follows its
translation (BigDecimal/HALF_UP where comp3-money is cited, sealed variants for
redefines, keyed state stores where keyed-state-store is cited, …). Patterns are
ADVISORY heuristics: report divergence as minor findings naming the card id, and
accept divergence the specs or target architecture explicitly justify — the
specs always win over the cards.
