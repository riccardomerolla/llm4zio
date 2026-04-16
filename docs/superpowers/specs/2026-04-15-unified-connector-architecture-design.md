# Unified Connector Architecture

## Context

AI Provider and CLI tool configuration is scattered across multiple UX surfaces and code paths:

- **AI Provider** (API-based): configured in Settings > AI tab (global `ai.*` keys), with agent-level overrides in `agent.<name>.ai.*`
- **CLI tool**: configured per-Workspace (`Workspace.cliTool`) and per-Agent (`Agent.cliTool`), set during Template wizard (Q6)
- **Advanced Config**: legacy raw YAML/JSON editor at `/settings/advanced` -- duplicates structured Settings with no validation

At the code level, API providers go through `LlmService` → `ConfigAwareLlmService.buildProvider()` → `HttpClient`, while CLI tools go through `CliAgentRunner.buildArgv()` → `ProcessBuilder`. These are conceptually the same thing -- ways to send prompts and get completions -- but they share no abstraction, live in different layers, and are configured differently.

**Goals:**
1. Unify API providers and CLI tools under a single `Connector` type hierarchy in the llm4zio library
2. Make the Settings page the single source of truth for connector configuration, with agent-level overrides
3. Remove Advanced Config entirely
4. Enable adding/evolving connectors without touching gateway code
5. Library-level integration tests for every connector, independent of application logic

---

## Connector Type Hierarchy

Two-branch sealed hierarchy in `llm4zio.core`, keeping the API vs CLI distinction visible at the type level:

```scala
trait Connector:
  def id: ConnectorId
  def kind: ConnectorKind
  def healthCheck: IO[LlmError, HealthStatus]
  def isAvailable: UIO[Boolean]

enum ConnectorKind:
  case Api, Cli

case class HealthStatus(
  availability: Availability,
  authStatus: AuthStatus,
  latency: Option[Duration],
)
```

### ApiConnector

Extends both `Connector` and the existing `LlmService` trait -- zero breaking changes for current API provider code:

```scala
trait ApiConnector extends Connector, LlmService:
  final def kind: ConnectorKind = ConnectorKind.Api
```

Existing providers (`OpenAIProvider`, `AnthropicProvider`, `GeminiApiProvider`, `LmStudioProvider`, `OllamaProvider`) add `extends ApiConnector` and implement `id` + `healthCheck`. All `LlmService` methods stay unchanged.

### CliConnector

New trait for CLI-based tools. Provides both prompt completion AND argv-building:

```scala
trait CliConnector extends Connector:
  final def kind: ConnectorKind = ConnectorKind.Cli
  def interactionSupport: InteractionSupport
  def buildArgv(prompt: String, ctx: CliContext): List[String]
  def buildInteractiveArgv(ctx: CliContext): List[String]
  def complete(prompt: String): IO[LlmError, String]
  def completeStream(prompt: String): Stream[LlmError, LlmChunk]

case class CliContext(
  worktreePath: String,
  repoPath: String,
  envVars: Map[String, String],
  sandbox: Option[CliSandbox],
  turnLimit: Option[Int],
)

enum CliSandbox:
  case Docker(image: String, mount: Boolean, network: Option[String])
  case Podman, SeatbeltMacOS, Runsc, Lxc
```

### Concrete CLI Connectors

- `GeminiCliConnector` -- absorbs current `GeminiCliProvider` + `CliAgentRunner` gemini argv logic
- `ClaudeCliConnector` -- absorbs `CliAgentRunner` claude argv logic
- `OpenCodeConnector` -- absorbs opencode argv logic
- `CodexConnector` -- absorbs codex argv logic
- `CopilotConnector` -- absorbs copilot argv logic

---

## Configuration ADT

Unified config model in `llm4zio.core`, replacing both `ProviderConfig` (config-domain) and hardcoded CLI tool strings:

```scala
sealed trait ConnectorConfig:
  def connectorId: ConnectorId
  def model: Option[String]
  def timeout: Duration
  def maxRetries: Int

case class ApiConnectorConfig(
  connectorId: ConnectorId,
  model: Option[String],
  baseUrl: Option[String],
  apiKey: Option[String],
  timeout: Duration = 300.seconds,
  maxRetries: Int = 3,
  requestsPerMinute: Int = 60,
  burstSize: Int = 10,
  acquireTimeout: Duration = 30.seconds,
  temperature: Option[Double],
  maxTokens: Option[Int],
) extends ConnectorConfig

case class CliConnectorConfig(
  connectorId: ConnectorId,
  model: Option[String],
  timeout: Duration = 300.seconds,
  maxRetries: Int = 3,
  flags: Map[String, String] = Map.empty,
  sandbox: Option[CliSandbox] = None,
  turnLimit: Option[Int] = None,
  envVars: Map[String, String] = Map.empty,
) extends ConnectorConfig
```

### ConnectorId

Typed wrapper with known constants:

```scala
case class ConnectorId(value: String) derives JsonCodec, Schema

object ConnectorId:
  // API
  val OpenAI: ConnectorId      = ConnectorId("openai")
  val Anthropic: ConnectorId   = ConnectorId("anthropic")
  val GeminiApi: ConnectorId   = ConnectorId("gemini-api")
  val LmStudio: ConnectorId    = ConnectorId("lm-studio")
  val Ollama: ConnectorId      = ConnectorId("ollama")
  // CLI
  val ClaudeCli: ConnectorId   = ConnectorId("claude-cli")
  val GeminiCli: ConnectorId   = ConnectorId("gemini-cli")
  val OpenCode: ConnectorId    = ConnectorId("opencode")
  val Codex: ConnectorId       = ConnectorId("codex")
  val Copilot: ConnectorId     = ConnectorId("copilot")
  // Test
  val Mock: ConnectorId        = ConnectorId("mock")
```

### FallbackChain

```scala
case class FallbackChain(connectors: List[ConnectorConfig])
```

### Migration

- `LlmProvider` enum gains `toConnectorId` bridge method during transition, then is deprecated and removed
- `ProviderConfig` in config-domain becomes a thin wrapper delegating to library `ConnectorConfig`, then is removed
- **Existing event-sourced data**: `WorkspaceEvent.Created` and `WorkspaceEvent.Updated` contain `cliTool: String`. During event replay, `cliTool` is read but ignored for connector resolution (the agent/global config is authoritative). The field stays in the event schema for backward compatibility but is not written to new events. `Workspace.fromEvents` can populate a deprecated `legacyCli: Option[String]` for data migration visibility.
- **Settings key migration**: Existing `ai.*` keys are read by `ConnectorConfigResolver` as a fallback during transition. New writes go to `connector.default.*` keys. Once all users have re-saved settings, the `ai.*` fallback path is removed.

---

## Connector Registry & Factory

Replaces the gateway's `ConfigAwareLlmService.buildProvider()` match expression:

```scala
trait ConnectorRegistry:
  def resolve(config: ConnectorConfig): IO[LlmError, Connector]
  def resolveApi(config: ApiConnectorConfig): IO[LlmError, ApiConnector]
  def resolveCli(config: CliConnectorConfig): IO[LlmError, CliConnector]
  def available: UIO[List[ConnectorId]]
  def healthCheckAll: IO[LlmError, Map[ConnectorId, HealthStatus]]
```

Factory pattern for instantiation:

```scala
trait ConnectorFactory:
  def connectorId: ConnectorId
  def kind: ConnectorKind
  def create(config: ConnectorConfig): IO[LlmError, Connector]

object ConnectorRegistry:
  val live: ZLayer[HttpClient & CliProcessExecutor, Nothing, ConnectorRegistry] =
    ZLayer.fromFunction { (http: HttpClient, cli: CliProcessExecutor) =>
      ConnectorRegistryLive(Map(
        ConnectorId.OpenAI    -> OpenAIConnectorFactory(http),
        ConnectorId.Anthropic -> AnthropicConnectorFactory(http),
        ConnectorId.GeminiApi -> GeminiApiConnectorFactory(http),
        ConnectorId.LmStudio  -> LmStudioConnectorFactory(http),
        ConnectorId.Ollama    -> OllamaConnectorFactory(http),
        ConnectorId.ClaudeCli -> ClaudeCliConnectorFactory(cli),
        ConnectorId.GeminiCli -> GeminiCliConnectorFactory(cli),
        ConnectorId.OpenCode  -> OpenCodeConnectorFactory(cli),
        ConnectorId.Codex     -> CodexConnectorFactory(cli),
        ConnectorId.Copilot   -> CopilotConnectorFactory(cli),
        ConnectorId.Mock      -> MockConnectorFactory(),
      ))
    }
```

### CliProcessExecutor

Generalizes the current `GeminiCliExecutor` into a library-level abstraction:

```scala
trait CliProcessExecutor:
  def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult]
  def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String]): Stream[LlmError, String]

case class ProcessResult(stdout: List[String], exitCode: Int)
```

The gateway provides `CliProcessExecutor.live` (wrapping `ProcessBuilder`). Tests inject `MockCliProcessExecutor`.

---

## Gateway Configuration Resolution

### Storage Keys

Settings page stores connector config as the global default:

```
connector.default.id = "gemini-cli"
connector.default.model = "gemini-2.5-flash"
connector.default.timeout = 300
connector.default.flags.yolo = "true"
```

Agent-level overrides:

```
agent.<name>.connector.id = "claude-cli"
agent.<name>.connector.model = "claude-sonnet-4"
agent.<name>.connector.timeout = 600
```

### Resolution Chain

```
Agent override -> Global default -> Library defaults
```

Single resolver service:

```scala
trait ConnectorConfigResolver:
  def resolve(agentName: Option[String]): IO[PersistenceError, ConnectorConfig]
```

Reads from `ConfigRepository`, merges layers, returns typed `ConnectorConfig`.

### What Gets Removed from Workspace

- `Workspace.cliTool` field -- connector selection moves to agent/global config
- `Workspace` keeps `runMode: RunMode` (Host/Docker/Cloud) -- execution environment, not connector selection
- Templates no longer ask "CLI Tool" (Q6)

### What Gets Removed Entirely

- Advanced Config tab, `ab-config-editor` component, `/api/config/*` endpoints, `ConfigController` config editing routes
- `ProviderConfig` in config-domain (replaced by library `ConnectorConfig`)
- `ConfigAwareLlmService.buildProvider()` match expression (replaced by `ConnectorRegistry.resolve()`)
- `CliAgentRunner.buildArgv()` tool-specific match cases (moved into each `CliConnector`)
- `LlmProvider` enum (replaced by `ConnectorId`)
- `GeminiCliExecutor` trait (replaced by `CliProcessExecutor`)

### What CliAgentRunner Becomes

A thin orchestrator combining a resolved `CliConnector` with `RunMode`:

```scala
object CliAgentRunner:
  def execute(
    connector: CliConnector,
    prompt: String,
    ctx: CliContext,
    runMode: RunMode,
    onLine: String => Task[Unit],
  ): IO[WorkspaceError, ExecutionResult]
```

Docker/Cloud wrapping stays in the gateway (execution environment concern). The argv core comes from the connector.

---

## Unified Settings UI

### Connectors Tab (replaces AI tab)

1. **Connector selector** -- dropdown grouped by kind:
   ```
   -- API Providers --
   OpenAI
   Anthropic
   Gemini API
   LM Studio
   Ollama
   -- CLI Tools --
   Claude CLI
   Gemini CLI
   OpenCode
   Codex
   Copilot
   ```

2. **Dynamic form fields** -- adapts based on selected connector kind:
   - API selected: model, baseUrl, apiKey, rate limits (requestsPerMinute, burstSize, acquireTimeout), temperature, maxTokens
   - CLI selected: model, flags (tool-specific key-value pairs), sandbox, turnLimit, envVars
   - Common fields always visible: timeout, maxRetries

3. **Test Connection** -- calls `ConnectorRegistry.resolve(config).flatMap(_.healthCheck)`, works for both API and CLI

4. **Fallback Chain** -- ordered list of backup connectors (connectorId + model per entry)

5. **Available Models** section -- grouped by connector, health badges

### Agent Config Page (`/agents/{name}/config`)

- Same connector selector + dynamic form, all fields optional
- Empty = inherit global default (shown as placeholder)
- "Reset to Global" clears all overrides

### Tabs Removed

- "Advanced Config" -- gone entirely
- "AI" renamed to "Connectors"

### Tabs Unchanged

Channels, Gateway, Issue Templates, Governance, Daemons, System, Demo

---

## Testing Strategy

### Tier 1: Fixture-Based Unit Tests (always run in CI)

Each connector gets a spec using mock executors:

```scala
// API connectors -- MockHttpClient (existing, reused)
object OpenAIConnectorSpec extends ZIOSpecDefault:
  def spec = suite("OpenAIConnector")(
    test("completes prompt successfully") { ... },
    test("handles rate limit with retry-after") { ... },
    test("returns AuthenticationError on 401") { ... },
    test("streams chunks correctly") { ... },
    test("healthCheck returns Healthy on 200") { ... },
  ).provideLayer(OpenAIConnector.make(config, MockHttpClient(fixtures)))

// CLI connectors -- MockCliProcessExecutor (new)
object ClaudeCliConnectorSpec extends ZIOSpecDefault:
  def spec = suite("ClaudeCliConnector")(
    test("buildArgv produces correct flags") { ... },
    test("completes prompt from stdout") { ... },
    test("handles non-zero exit code") { ... },
    test("healthCheck verifies tool installed") { ... },
    test("interactionSupport is InteractiveStdin") { ... },
  ).provideLayer(ClaudeCliConnector.make(config, MockCliProcessExecutor(fixtures)))
```

`MockCliProcessExecutor` records argv calls and returns canned `ProcessResult`s.

### Tier 2: Smoke Tests Against Real Services (gated behind env vars)

```scala
object ConnectorSmokeSpec extends ZIOSpecDefault:
  def spec = suite("Connector Smoke")(
    test("OpenAI responds") { ... } @@ ifEnvSet("OPENAI_API_KEY"),
    test("Anthropic responds") { ... } @@ ifEnvSet("ANTHROPIC_API_KEY"),
    test("Gemini CLI installed and responds") { ... } @@ ifEnvSet("GEMINI_CLI_AVAILABLE"),
    test("Claude CLI installed and responds") { ... } @@ ifEnvSet("CLAUDE_CLI_AVAILABLE"),
    test("LM Studio reachable") { ... } @@ ifEnvSet("LM_STUDIO_URL"),
  ) @@ sequential
```

Each smoke test sends a minimal prompt and asserts non-error response. Full connector lifecycle independent of gateway.

### ConnectorRegistry Integration Test

```scala
object ConnectorRegistrySpec extends ZIOSpecDefault:
  def spec = suite("ConnectorRegistry")(
    test("resolves API connector from config") { ... },
    test("resolves CLI connector from config") { ... },
    test("returns ConfigError for unknown connectorId") { ... },
    test("healthCheckAll returns status for all registered") { ... },
  )
```

### Migration of Existing Tests

- `CliAgentRunnerSpec` argv-building tests -> become `*CliConnectorSpec` in the library
- `CliAgentRunnerSpec` keeps only thin orchestrator tests (RunMode wrapping, Docker execution)
- Existing `GeminiCliProviderSpec`, `OpenAIProviderSpec`, etc. -> renamed to `*ConnectorSpec`, gain `healthCheck` tests

---

## Verification Plan

1. **Library compiles independently**: `sbt llm4zio/compile` with all new types and connectors
2. **Library tests pass**: `sbt llm4zio/test` -- all fixture-based connector specs green
3. **Gateway compiles**: `sbt compile` -- config-domain, agent-domain, workspace-domain all resolve new types
4. **Gateway tests pass**: `sbt test` -- existing 1121+ tests still pass after migration
5. **Integration tests**: `sbt it:test` -- connector resolution, config persistence, health checks
6. **Smoke tests**: `GEMINI_CLI_AVAILABLE=true sbt llm4zio/test` (or similar) -- at least one real connector validates end-to-end
7. **UI verification**: Start gateway (`sbt run`), navigate to `/settings/connectors`, configure a connector, test connection, verify agent override page works
8. **Advanced Config removed**: Verify `/settings/advanced` returns 404, `/api/config` endpoints gone

---

## Critical Files

### Library (llm4zio/)
- `llm4zio/src/main/scala/llm4zio/core/Models.scala` -- add `ConnectorId`, `ConnectorKind`, `ConnectorConfig` ADT
- `llm4zio/src/main/scala/llm4zio/core/LlmService.scala` -- `Connector`, `ApiConnector`, `CliConnector` traits
- `llm4zio/src/main/scala/llm4zio/core/Errors.scala` -- unchanged (reused as-is)
- `llm4zio/src/main/scala/llm4zio/providers/HttpClient.scala` -- unchanged
- `llm4zio/src/main/scala/llm4zio/providers/*.scala` -- each provider adds `extends ApiConnector` or becomes `CliConnector`
- `llm4zio/src/main/scala/llm4zio/core/ConnectorRegistry.scala` -- new file
- `llm4zio/src/main/scala/llm4zio/core/CliProcessExecutor.scala` -- new file
- `llm4zio/src/test/scala/llm4zio/providers/*Spec.scala` -- rename + extend with healthCheck tests

### Gateway - Config Domain
- `modules/config-domain/src/main/scala/config/entity/ProviderModels.scala` -- replaced by library ConnectorConfig
- `modules/config-domain/src/main/scala/config/entity/AIModels.scala` -- ConnectorId replaces LlmProvider references
- `modules/config-domain/src/main/scala/config/entity/GatewayConfigModels.scala` -- uses ConnectorConfig
- `modules/config-domain/src/main/scala/config/boundary/ModelsView.scala` -- uses ConnectorId for grouping

### Gateway - Agent Domain
- `modules/agent-domain/src/main/scala/agent/entity/Agent.scala` -- remove `cliTool`, add `connectorOverride: Option[ConnectorConfig]`
- `modules/agent-domain/src/main/scala/agent/entity/AgentPermissions.scala` -- `allowedCliTools` references ConnectorId
- `modules/agent-domain/src/main/scala/agent/boundary/AgentsView.scala` -- agent config page uses connector selector

### Gateway - Workspace Domain
- `modules/workspace-domain/src/main/scala/workspace/entity/WorkspaceModels.scala` -- remove `cliTool` field
- `modules/workspace-domain/src/main/scala/workspace/entity/WorkspaceEvent.scala` -- remove `cliTool` from events

### Gateway - Root
- `src/main/scala/app/ConfigAwareLlmService.scala` -- delegates to ConnectorRegistry
- `src/main/scala/workspace/control/CliAgentRunner.scala` -- thin orchestrator, no tool-specific argv
- `src/main/scala/workspace/control/WorkspaceRunService.scala` -- uses ConnectorConfigResolver
- `src/main/scala/orchestration/control/AgentConfigResolver.scala` -- replaced by ConnectorConfigResolver
- `src/main/scala/config/boundary/SettingsController.scala` -- connector.* keys, remove /api/config routes

### Gateway - Removed
- `src/main/scala/config/boundary/ConfigController.scala` -- config editing routes (deleted)
- `src/main/resources/static/client/components/ab-config-editor.js` -- web component (deleted)
- `modules/shared-web/src/main/scala/shared/web/SettingsView.scala` -- `advancedTab()` removed, `aiTab()` becomes `connectorsTab()`
