# llm4zio-core

The LLM plumbing layer for llm4zio: `Connector`/`LlmService`, API and CLI provider implementations (OpenAI, Anthropic,
Gemini, LmStudio, Ollama, Claude CLI, Codex, Copilot, Gemini CLI, OpenCode), streaming via ZIO Streams,
tool-calling, structured output, and lightweight observability hooks.

## Module map

| Package | Contents |
|---|---|
| `llm4zio.core` | `LlmService`, `Connector{,Api,Cli}`, `Models` (`Message`, `LlmChunk`, `LlmConfig`, `LlmProvider`), `Streaming`, `Errors` (`LlmError`), `ConnectorRegistry`, `ConnectorFactories`, `Conversation` |
| `llm4zio.providers` | Provider impls: `OpenAiApi`, `AnthropicApi`, `GeminiApi`, `LmStudioApi`, `OllamaApi` (API); `ClaudeCli`, `Codex`, `Copilot`, `GeminiCli`, `OpenCode` (CLI); `Mock`, `HttpClient` |
| `llm4zio.tools` | `Tool`, `AnyTool`, `JsonSchema`, tool-calling executor |
| `llm4zio.observability` | Lightweight tracing/metrics hooks |

## Further reading

- **Root README** — library overview, build commands, conventions.
- **`llm4zio-flow`** — the agentic flow layer (Plan/Task, stage/fail, Chat, GitTool, GhTool, reviewAndFixLoop).
- **`llm4zio-runner`** — script entry point (`flow(args)(body)`), terminal progress renderer, example flows.
