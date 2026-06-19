# 5. Depend on one LlmService capability trait, never a concrete provider

- Status: Accepted

## Context
The library must talk to many backends — HTTP APIs (OpenAI, Anthropic, Gemini, LM Studio, Ollama) and CLI coding agents (claude, codex, gemini, pi, opencode, copilot) — plus a deterministic mock for tests. Callers (planner, chat, reviewers) should not know or care which backend is behind them, and swapping providers should not ripple through the flow code.

## Decision
Define a single capability trait LlmService (core/LlmService.scala) exposing executeStream, executeStreamWithHistory, executeWithTools, executeStructured/WithUsage, and isAvailable. All callers depend only on this trait. LlmService.fromConfig is a ZLayer that builds the right provider by matching on LlmProvider, and ConnectorRegistry/ConnectorFactories resolve a config to a live instance. The flow layer references the two seats (reasoning, coder) purely as LlmService.

## Alternatives considered
Expose concrete provider classes and let callers pick — rejected because backend-specific code would spread through the flow layer and make provider swaps a refactor. One trait per backend family (an ApiLlm and a CliLlm caller-visible distinction) — rejected in favour of a single trait so callers are blind to API-vs-CLI; the split is pushed below into Connector sub-traits instead. A typeclass/tagless-final encoding parameterised over the effect — rejected as unnecessary ceremony given ZIO is the fixed single effect system (ADR 1).

## Consequences
Providers are interchangeable and tests use a deterministic MockProvider for the same interface. New providers are added without touching flow logic. The trait becomes a stable published contract that must evolve carefully, and any backend-specific feature must either fit the trait or be surfaced via capabilities (see ADR 6).
