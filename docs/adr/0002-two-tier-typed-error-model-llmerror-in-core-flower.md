# 2. Two-tier typed error model: LlmError in core, FlowError in flow

- Status: accepted

## Context
With ZIO's typed error channel, the team had to decide what travels in the error channel and how the LLM-access layer's failures relate to the orchestration layer's failures. Throwable in signatures was explicitly disallowed. The two bounded contexts (LLM Access and Agentic Flow) each have distinct failure vocabularies that nonetheless must connect at their boundary.

## Decision
Define two ADTs. Core uses LlmError (core/Errors.scala) for provider/transport/usage-limit/invalid-request failures. Flow uses FlowError (flow/FlowError.scala) with cases Persistence, PlanParse, Aborted, Process, and Llm(message, cause: Option[LlmError]). The conformist boundary is expressed by FlowError.Llm wrapping an Option[LlmError] — the single place the two vocabularies meet — rather than an anti-corruption layer that re-models everything.

## Consequences
Errors are exhaustively matchable and Throwable never leaks into signatures. The layering keeps core independent of flow concerns. The cost is two error vocabularies to maintain and a small amount of bridging at the seam; the flow layer conforms to core's published LlmError instead of insulating itself from it.
