---
match: PERFORM .* THRU|GO TO .*-EXIT
---
PERFORM…THRU with GO TO <para>-EXIT is early-exit control flow: map to a method with
early returns (or a validation chain), one method per performed range. Trap: a GO TO
that escapes its PERFORM range is a latent fall-through bug in the original — flag it
in the spec instead of reproducing it.
