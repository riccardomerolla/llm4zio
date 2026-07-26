---
match: PERFORM [0-9]|^//[A-Z0-9]+ +EXEC
---
The batch main loop and JCL steps map to topology stages: validate → enrich → route
→ post, one named processor per paragraph cluster. Keep stage boundaries where the
batch had paragraph boundaries so traceability survives the paradigm shift.
