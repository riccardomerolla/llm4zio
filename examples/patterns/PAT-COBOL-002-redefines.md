---
match: REDEFINES
---
A REDEFINES clause is two interpretations of one buffer. Map to a sealed interface
with one variant per interpretation and an explicit discriminator — never two mutable
views of shared state. Trap: the discriminator is often implicit in which paragraph
reads the field; the spec must name it.
