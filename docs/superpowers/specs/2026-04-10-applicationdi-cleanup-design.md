# ApplicationDI Cleanup + LlmConfig Unification

## Goal

Reduce ApplicationDI to pure DI plumbing by extracting business logic to proper homes, and eliminate the redundant AIProvider/AIProviderConfig types by unifying on LlmProvider/LlmConfig from llm4zio.

## Part 1: Eliminate AIProvider/AIProviderConfig Duplication

### Problem

`AIProvider` enum and `AIProviderConfig` case class in `config-domain` are structurally identical to `LlmProvider` enum and `LlmConfig` case class in `llm4zio`. The only differences:

- `AIProvider.OpenAi` vs `LlmProvider.OpenAI` (casing)
- `AIProviderConfig` has `fallbackChain: ModelFallbackChain` (app-level concern)
- Two conversion functions in ApplicationDI (`aiProviderToLlmProvider`, `aiConfigToLlmConfig`) bridge the gap

Since `llm4zio` is part of this repo (not published independently), the anti-corruption layer adds ceremony without value.

### Changes

**build.sbt:** Add `llm4zio` to `configDomain.dependsOn(...)`. This lets `config-domain` use `LlmProvider` and `LlmConfig` directly.

**config.entity.ProviderModels:** Remove `AIProvider` enum and `AIProviderConfig` case class. Keep `ModelFallbackChain` and `FallbackEntry` but update them to reference `LlmConfig` instead of `AIProviderConfig`. Add a `ProviderConfig` case class that wraps `LlmConfig` with the app-level `fallbackChain` field:

```scala
case class ProviderConfig(
  config: LlmConfig,
  fallbackChain: ModelFallbackChain = ModelFallbackChain.empty,
)
```

**GatewayConfig:** Change `resolvedProviderConfig` to return `ProviderConfig` (wrapping `LlmConfig` + `fallbackChain`).

**SettingsApplier:** Change `toAIProviderConfig` → `toProviderConfig`, producing `ProviderConfig` directly with `LlmConfig` inside.

**ApplicationDI:** Remove `aiProviderToLlmProvider` and `aiConfigToLlmConfig` conversion functions. All code uses `LlmConfig` natively.

**shared-services RateLimiterConfig:** Rename `fromAIProviderConfig` to `fromLlmConfig` (or remove if the `llm4zio` version suffices).

**All consumers:** Replace `AIProvider` references with `LlmProvider`, `AIProviderConfig` references with `LlmConfig` or `ProviderConfig` as appropriate.

## Part 2: Extract Business Logic from ApplicationDI

### Problem

ApplicationDI is 667 lines. Beyond DI plumbing, it contains stateful services, business logic, and factory methods that belong in their domain packages.

### Extractions

| Current location in ApplicationDI | New file | Content |
|-----------------------------------|----------|---------|
| `ConfigAwareLlmService` (private case class, ~100 lines) | `src/main/scala/app/ConfigAwareLlmService.scala` | Stateful LLM service wrapping `Ref[GatewayConfig]` with failover chain, provider caching, streaming support. Exposes `val live: ZLayer[...]`. |
| `ConfigAwareTelegramClient` (private case class, ~60 lines) | `src/main/scala/gateway/boundary/telegram/ConfigAwareTelegramClient.scala` | Dynamic Telegram client that reads token from `Ref[GatewayConfig]` and caches client instances per token. Exposes `val live: ZLayer[...]`. |
| `channelRegistryLayer` + `registerOptionalExternalChannel` + `parseSessionScopeStrategy` (~75 lines) | `src/main/scala/gateway/control/ChannelRegistryFactory.scala` | Factory that builds `ChannelRegistry` by reading config and conditionally registering WebSocket, Telegram, Discord, and Slack channels. Exposes `val live: ZLayer[...]`. |
| `workspaceRunServiceLayer` (~20 lines) | `src/main/scala/workspace/control/WorkspaceRunServiceFactory.scala` | Factory selecting between `MockAgentRunner` and `CliAgentRunner` based on demo flag. Exposes `val live: ZLayer[...]`. |
| `issueWorkReportProjectionLayer` (~15 lines) | `src/main/scala/board/control/IssueWorkReportProjectionFactory.scala` | Factory that creates `IssueWorkReportProjection`, subscribes to event bus, and runs hydration on startup. Exposes `val live: ZLayer[...]`. |
| `loadSettingsSnapshot` (~10 lines) | Inline into `configRefLayer` or extract to `config.control.SettingsLoader` | Reads JSON settings snapshot from disk. |

### What stays in ApplicationDI

After extraction, ApplicationDI contains only:

- `commonLayers` — layer composition referencing `*.live` from extracted files
- `webServerLayer` — layer composition referencing `*.live` from extracted files
- `configRefLayer` — layer for `Ref[GatewayConfig]` initialization
- `httpClientLayer` — layer for ZIO HTTP Client
- `configAwareLlmServiceLayer` — simplified to reference `ConfigAwareLlmService.live`
- `fatalStartupLayer` / `errorMessage` — DI error handling helpers

Each extracted file follows the standard ZLayer pattern:

```scala
object ConfigAwareLlmService:
  val live: ZLayer[Dep1 & Dep2 & ..., Nothing, LlmService] =
    ZLayer.fromFunction(ConfigAwareLlmServiceLive.apply)
    // or ZLayer { ... } for effectful construction
```

### Naming

Extracted files use descriptive names reflecting what they build, not generic "Factory" where a more specific name fits. The case classes keep their current names (e.g., `ConfigAwareLlmService` stays `ConfigAwareLlmService`).

## Testing

No new tests needed — these are pure extractions preserving existing behavior. `sbt compile && sbt test` verifies correctness. The key risk is import changes triggering `-Werror` unused-import failures, which compilation catches.

## Ordering

1. Part 2 first (extract business logic) — mechanical moves, no type changes
2. Part 1 second (unify types) — changes type signatures across files, easier after ApplicationDI is smaller
