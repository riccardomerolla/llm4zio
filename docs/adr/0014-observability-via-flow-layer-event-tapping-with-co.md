# 14. Observability via flow-layer event tapping, with core decorators left opt-in and unwired

- Status: Accepted

## Context
The core module ships decorator wrappers (MeteredLlmService, Langfuse, Tracing, LlmLogger, Metrics, Logging) that wrap any LlmService to record counts/tokens/latency. The runner, however, already has an event stream and cost tracker and needs progress/cost surfaced through that channel rather than a second, parallel metrics path.

## Decision
By default the runner does not wire the core observability decorators. Instead DefaultFlowContext taps each connector through TransientRetry → EventTappingService → (optional) UsageLimitAware, so usage and progress flow as FlowEvents into the Hub and into CostTracker/PriceList for cost accounting. The core decorators remain available for embedders who want them but are not part of the default wiring.

## Alternatives considered
Wire the core Metered/Langfuse/Tracing decorators by default — rejected because it creates a second, parallel telemetry path competing with the FlowEvent stream and forces a dependency/config (e.g. Langfuse) on every consumer. Drop the core decorators entirely — rejected because embedders may legitimately want OpenTelemetry/Langfuse, so they are kept as opt-in tools. Emit telemetry only via zio-metrics with no event stream — rejected because the runner's live terminal tree and cost summary are driven by the typed FlowEvent feed.

## Consequences
There is one default observability path (the event stream) instead of two competing ones, keeping the runner simple. Token cost is tracked from TokensUsed events. The core decorators are present but dormant — contributors should know they exist as opt-in tools and not assume they are active, and embedders who want OpenTelemetry/Langfuse must wire them deliberately.
