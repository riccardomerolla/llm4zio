---
match: RERUN|RESUBMIT
---
Batch rerun-from-file becomes topic replay: reprocessing is resetting offsets over
an immutable input topic, which is only safe because of idempotent processing
(PAT-STREAM-003). Document the replay procedure in the runbook the way rerun JCL
was documented.
