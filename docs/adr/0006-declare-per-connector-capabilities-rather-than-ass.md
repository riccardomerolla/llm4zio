# 6. Declare per-connector capabilities rather than assume a uniform feature set

- Status: Accepted

## Context
Backends genuinely differ: some stream, some support structured output natively, some can resume or run interactive sessions, some can expose an ask-user/approval tool, some report token usage. A blanket assumption that every LlmService can do everything would cause runtime failures deep inside a flow (e.g. gemini declares InteractiveStdin yet cannot expose an ask-user tool headless, so its askUser is false).

## Decision
Refine LlmService into Connector (core/Connector.scala) carrying id, kind (Api/Cli), healthCheck, and a ConnectorCapabilities record (streaming, resumableSessions, interactiveSessions, askUser, approval, structuredOutput, usageReporting). A flow can inspect declared capabilities and refuse an unsupported workflow up front. CliConnector requires only complete/completeStream plus argv builders and derives the richer methods by default — executeStructured is synthesized for every CLI provider by injecting a schema hint and parsing the text back, while executeWithTools defaults to failing with InvalidRequestError unless overridden.

## Alternatives considered
Assume every connector supports every operation and fail at the call site when it doesn't — rejected because the failure surfaces deep inside a long-running flow rather than at the start. Probe capabilities dynamically by calling and catching — rejected as wasteful (spends tokens/processes to discover support) and non-deterministic. Model each capability as a separate sub-interface the connector may or may not implement, with instanceof-style checks — rejected as less discoverable than a single declared record the flow can read up front.

## Consequences
Flows can fail fast and honestly on capability mismatch instead of mid-run. Adding a CLI provider is cheap because only two primitives are mandatory. The cost is that capabilities are hand-declared and must be kept truthful per connector, and CLI structured output is best-effort (schema-hint + parse) rather than guaranteed.
