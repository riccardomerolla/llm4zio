# 13. Progress is an event stream published to a swappable sink (noop/Collecting/Hub)

- Status: Accepted

## Context
A flow needs observable progress — stages starting/completing/failing, tool use, assistant messages, token usage — but the library should not hard-wire a terminal UI, and tests need to assert on what happened without a console. Cost tracking also needs a feed of token-usage facts.

## Decision
Define FlowEvent as an enum of progress facts (StageStarted/Completed/Failed, Aborted, Info, ToolUse, AssistantMessage, TokensUsed) and FlowEvents as a sink trait with three implementations: noop, Collecting (accumulates into a Ref for tests), and Hub (a bounded broadcast with back-pressure). stage(name)(effect) and fail(message) publish events around an effect, with the sink resolved implicitly via FlowContext's given FlowEvents. The runner subscribes its terminal renderer to the Hub; CostTracker consumes TokensUsed.

## Alternatives considered
Hard-wire println/terminal output into the orchestration — rejected because it couples the domain to a UI and makes headless/test runs noisy and unassertable. Use zio-logging exclusively for progress — rejected because structured, typed events let the runner render a live tree and let CostTracker consume TokensUsed as data, which free-text logs cannot. A callback/listener interface passed around explicitly — rejected in favour of an implicit sink on FlowContext so stage/fail stay terse inside the context-function body.

## Consequences
Presentation is decoupled from orchestration — the same flow runs headless, under test, or with a live terminal by swapping the sink. Tests assert on collected events. The Hub's bounded back-pressure means a slow consumer throttles the producer. Connectors are tapped (EventTappingService) to emit events, so observability rides on the event stream rather than the core observability decorators (see ADR 14).
