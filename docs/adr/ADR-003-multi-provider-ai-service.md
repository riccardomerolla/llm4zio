# ADR-003: Multi-Provider AIService Abstraction

**Status:** Accepted  
**Date:** 2026-02-07  
**Decision Makers:** Architecture Team, Riccardo Merolla  
**Context:** Provider-agnostic AI integration for migration pipeline

---

## Context

The migration pipeline originally depended on a Gemini-specific service surface (`GeminiService`).  
This made provider changes expensive and blocked compatibility with OpenAI-compatible endpoints and Anthropic APIs.

We need:

- A provider-agnostic runtime service for all agents.
- Startup-time provider selection from config/CLI/env.
- Backward-compatible default behavior (`gemini-cli`).
- Support for local OpenAI-compatible gateways such as LM Studio.

---

## Decision

Introduce a provider-agnostic `AIService` as the primary dependency for agents and orchestration.

- Add `AIService.fromConfig` factory layer that selects implementation by `AIProviderConfig.provider`.
- Wire provider selection in `Main.scala` with:
  - `AIProviderConfig` from resolved config
  - `Client.default` + `HttpAIClient.live` for HTTP providers
  - `AIService.fromConfig` for runtime selection
- Migrate orchestrator and AI-calling agents from `GeminiService` to `AIService`.
- Standardize agent error mapping to provider-neutral names (`AIFailed`).
- Keep compatibility wrappers (`GeminiService`, `GeminiResponse`, `GeminiError`) for legacy paths/tests.

---

## Consequences

### Positive

- Enables OpenAI, Anthropic, Gemini API, Gemini CLI, and OpenAI-compatible local runtimes.
- Keeps agent logic provider-neutral.
- Centralizes provider selection at composition boundaries.
- Preserves default behavior when no provider is specified.

### Negative

- Adds HTTP client dependency and startup wiring complexity.
- Introduces additional model/config validation paths.

### Risk Mitigation

- Default provider remains `gemini-cli`.
- Legacy compatibility types remain available.
- Layer-based wiring keeps provider changes isolated to startup composition.

---

## References

- [ADR-002: Gemini CLI Adoption](./ADR-002-gemini-cli-adoption.md)
- [LM Studio OpenAI-compatible API](https://lmstudio.ai/docs/developer/openai-compat)
