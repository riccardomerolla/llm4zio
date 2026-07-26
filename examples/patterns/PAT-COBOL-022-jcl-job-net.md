---
match: ^//[A-Z0-9]+ +EXEC
---
A JCL job is an orchestration: each EXEC step maps to a use-case invocation, COND
codes to conditional continuation, DD statements to the step&apos;s ports. The job net
belongs in the orchestrator (scheduler config), never hidden inside one service.
