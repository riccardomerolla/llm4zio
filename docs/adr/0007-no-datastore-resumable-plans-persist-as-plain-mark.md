# 7. No datastore — resumable plans persist as plain Markdown files

- Status: accepted

## Context
The original product carried a board, governance, and a datastore. The forked library wanted to stay thin and stateless, while still letting a long-running flow survive a crash and resume where it left off. Authentication for git, gh, and the agent CLIs is delegated to those tools, so the library itself holds no credentials.

## Decision
Persist the work unit (Plan: epicId, tasks, optional brief) as human-readable Markdown at .llm4zio/plan-<hash>.md, where the path is derived deterministically from the prompt (Plan.defaultPath). PlanStore.parse/render round-trip the file and recoverOrCreate resumes a crashed run; implementTaskLoop persists the plan after each task. No database is introduced anywhere in the system.

## Consequences
Re-running the same prompt resumes the same plan; the persisted artifact is diffable and editable by hand. State is just files on disk, simplifying operation and testing. There is no concurrency control or schema migration — the plan format is the contract, and PlanStore parsing must tolerate the Markdown it emits.
