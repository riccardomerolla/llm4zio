---
match: ^ {10,}88  
---
Level-88 condition names are the domain vocabulary: map each group to a Java enum
(ACCT-STATUS 88s → AccountStatus.ACTIVE/FROZEN/CLOSED/DORMANT) and keep the raw code
as the enum field. Trap: several 88s can overlap one value range — model predicates,
not just constants, when ranges appear.
