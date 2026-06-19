# 8. Role split: reasoning over an API connector, code-editing over a CLI agent

- Status: accepted

## Context
Planning, review, and structured-output judgements are reasoning tasks well served by API models, whereas editing a working tree is best done by a CLI coding agent that runs its own tool loop against the filesystem. Forcing one backend to do both would either waste a capable API model on file edits or ask a headless CLI to emit structured plans it is poor at.

## Decision
FlowContext carries two LlmService seats — reasoning and coder (FlowContext.scala) — typically an API connector for reasoning and a CLI coding agent for the coder. The runner's presets and DefaultFlowContext wire both seats, and a single all-CLI backend (e.g. all-claude) can fill both when desired. Reviewers are a further list of LlmService seats.

## Consequences
Each task uses the best-fit backend and costs are controllable (cheap models for editing, stronger models for reasoning). The two-seat shape is baked into the context aggregate and the canonical flow. It also means a deployment must provision and authenticate two kinds of backend unless it deliberately collapses them.
