# ApplicationDI Cleanup + LlmConfig Unification

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Reduce ApplicationDI to pure DI plumbing and eliminate the redundant AIProvider/AIProviderConfig types by unifying on LlmProvider/LlmConfig from llm4zio.

**Spec:** `docs/superpowers/specs/2026-04-10-applicationdi-cleanup-design.md`

**Architecture:** Part 2 runs first (extract business logic from ApplicationDI — mechanical moves, no type changes). Part 1 runs second (unify AIProvider→LlmProvider across ~30 files — type changes are easier with a smaller ApplicationDI).

---

### Task 1: Extract ConfigAwareTelegramClient

**Files:**
- Create: `src/main/scala/gateway/boundary/telegram/ConfigAwareTelegramClient.scala`
- Modify: `src/main/scala/app/ApplicationDI.scala`

- [ ] **Step 1: Create ConfigAwareTelegramClient.scala**

Create `src/main/scala/gateway/boundary/telegram/ConfigAwareTelegramClient.scala`:

```scala
package gateway.boundary.telegram

import scala.concurrent.{ ExecutionContext, Future }

import zio.*

import com.bot4s.telegram.clients.FutureSttpClient
import gateway.entity.*

final case class ConfigAwareTelegramClient(
  configRef: Ref[config.entity.GatewayConfig],
  clientsRef: Ref.Synchronized[Map[String, TelegramClient]],
  backend: sttp.client4.WebSocketBackend[Future],
) extends TelegramClient:

  private given ExecutionContext = ExecutionContext.global

  override def getUpdates(
    offset: Option[Long],
    limit: Int,
    timeoutSeconds: Int,
    timeout: Duration,
  ): IO[TelegramClientError, List[TelegramUpdate]] =
    currentClient.flatMap(_.getUpdates(offset, limit, timeoutSeconds, timeout))

  override def sendMessage(
    request: TelegramSendMessage,
    timeout: Duration,
  ): IO[TelegramClientError, TelegramMessage] =
    currentClient.flatMap(_.sendMessage(request, timeout))

  override def sendDocument(
    request: TelegramSendDocument,
    timeout: Duration,
  ): IO[TelegramClientError, TelegramMessage] =
    currentClient.flatMap(_.sendDocument(request, timeout))

  private def currentClient: IO[TelegramClientError, TelegramClient] =
    for
      config <- configRef.get
      token  <- ZIO
                  .fromOption(config.telegram.botToken.map(_.trim).filter(_.nonEmpty))
                  .orElseFail(
                    TelegramClientError.InvalidConfig(
                      "telegram bot token is not configured; set telegram.botToken in Settings"
                    )
                  )
      client <- clientsRef.modifyZIO { current =>
                  current.get(token) match
                    case Some(existing) =>
                      ZIO.succeed((existing, current))
                    case None           =>
                      ZIO
                        .attempt {
                          val handler = FutureSttpClient(
                            token = token,
                            telegramHost = "api.telegram.org",
                          )(using backend, summon[ExecutionContext])
                          TelegramClient.fromRequestHandler(handler)
                        }
                        .mapError(err =>
                          TelegramClientError.InvalidConfig(
                            s"failed to initialize telegram client: ${Option(err.getMessage).getOrElse(err.toString)}"
                          )
                        )
                        .map(created => (created, current + (token -> created)))
                }
    yield client
```

- [ ] **Step 2: Remove ConfigAwareTelegramClient from ApplicationDI**

In `src/main/scala/app/ApplicationDI.scala`, remove the entire `ConfigAwareTelegramClient` case class (lines 508-566). The `channelRegistryLayer` already constructs it by name — since both are in the same package universe, updating the import reference is all that's needed.

Add this import at the top of ApplicationDI:

```scala
import gateway.boundary.telegram.ConfigAwareTelegramClient
```

- [ ] **Step 3: Compile**

Run: `sbt compile`

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/gateway/boundary/telegram/ConfigAwareTelegramClient.scala src/main/scala/app/ApplicationDI.scala
git commit -m "refactor: extract ConfigAwareTelegramClient from ApplicationDI"
```

---

### Task 2: Extract ConfigAwareLlmService

**Files:**
- Create: `src/main/scala/app/ConfigAwareLlmService.scala`
- Modify: `src/main/scala/app/ApplicationDI.scala`

- [ ] **Step 1: Create ConfigAwareLlmService.scala**

Create `src/main/scala/app/ConfigAwareLlmService.scala`:

```scala
package app

import zio.*
import zio.stream

import _root_.config.entity.{ AIProviderConfig, GatewayConfig }
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.{ AnyTool, JsonSchema }

final case class ConfigAwareLlmService(
  configRef: Ref[GatewayConfig],
  http: HttpClient,
  cliExec: GeminiCliExecutor,
  cacheRef: Ref.Synchronized[Map[LlmConfig, LlmService]],
) extends LlmService:

  override def executeStream(prompt: String): stream.Stream[LlmError, LlmChunk] =
    stream.ZStream.unwrap(serviceChain.map(chain => failoverStream(chain)(_.executeStream(prompt))))

  override def executeStreamWithHistory(messages: List[Message]): stream.Stream[LlmError, LlmChunk] =
    stream.ZStream.unwrap(serviceChain.map(chain => failoverStream(chain)(_.executeStreamWithHistory(messages))))

  override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    withFailover(_.executeWithTools(prompt, tools))

  override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    withFailover(_.executeStructured(prompt, schema))

  override def isAvailable: UIO[Boolean] =
    serviceChain
      .flatMap { chain =>
        ZIO.foreach(chain)(_.isAvailable).map(_.exists(identity))
      }
      .orElseSucceed(false)

  private def serviceChain: IO[LlmError, List[LlmService]] =
    for
      aiCfg <- configRef.get.map(_.resolvedProviderConfig)
      cfgs   = fallbackConfigs(aiCfg)
      svcs  <- ZIO.foreach(cfgs)(providerFor)
    yield svcs

  private def providerFor(cfg: LlmConfig): IO[LlmError, LlmService] =
    cacheRef.modifyZIO { current =>
      current.get(cfg) match
        case Some(existing) => ZIO.succeed((existing, current))
        case None           =>
          ZIO
            .attempt(buildProvider(cfg))
            .mapError(th => LlmError.ConfigError(Option(th.getMessage).getOrElse(th.toString)))
            .map(created => (created, current + (cfg -> created)))
    }

  private def fallbackConfigs(primary: AIProviderConfig): List[LlmConfig] =
    val primaryLlm = ApplicationDI.aiConfigToLlmConfig(primary)
    val fallback   = primary.fallbackChain.models.map { ref =>
      ApplicationDI.aiConfigToLlmConfig(
        AIProviderConfig.withDefaults(
          primary.copy(
            provider = ref.provider.getOrElse(primary.provider),
            model = ref.modelId,
          )
        )
      )
    }
    (primaryLlm :: fallback).distinct

  private def withFailover[A](run: LlmService => IO[LlmError, A]): IO[LlmError, A] =
    serviceChain.flatMap { chain =>
      failoverIO(chain)(run)
    }

  private def failoverIO[A](services: List[LlmService])(run: LlmService => IO[LlmError, A]): IO[LlmError, A] =
    services match
      case head :: tail =>
        run(head).catchAll { err =>
          tail match
            case Nil => ZIO.fail(err)
            case _   => failoverIO(tail)(run)
        }
      case Nil          =>
        ZIO.fail(LlmError.ConfigError("No LLM provider configured"))

  private def failoverStream(
    services: List[LlmService]
  )(
    run: LlmService => stream.Stream[LlmError, LlmChunk]
  ): stream.Stream[LlmError, LlmChunk] =
    services match
      case head :: tail =>
        run(head).catchAll { err =>
          tail match
            case Nil => stream.ZStream.fail(err)
            case _   => failoverStream(tail)(run)
        }
      case Nil          =>
        stream.ZStream.fail(LlmError.ConfigError("No LLM provider configured"))

  private def buildProvider(cfg: LlmConfig): LlmService =
    import llm4zio.providers.*
    cfg.provider match
      case LlmProvider.GeminiCli => GeminiCliProvider.make(cfg, cliExec)
      case LlmProvider.GeminiApi => GeminiApiProvider.make(cfg, http)
      case LlmProvider.OpenAI    => OpenAIProvider.make(cfg, http)
      case LlmProvider.Anthropic => AnthropicProvider.make(cfg, http)
      case LlmProvider.LmStudio  => LmStudioProvider.make(cfg, http)
      case LlmProvider.Ollama    => OllamaProvider.make(cfg, http)
      case LlmProvider.OpenCode  => OpenCodeProvider.make(cfg, http)
      case LlmProvider.Mock      => MockProvider.make(cfg)
```

- [ ] **Step 2: Remove ConfigAwareLlmService from ApplicationDI**

In `src/main/scala/app/ApplicationDI.scala`, remove the `ConfigAwareLlmService` case class (lines 568-668, after the previous extraction this will be lower). Keep the `configAwareLlmServiceLayer` val unchanged — it already references `ConfigAwareLlmService(...)` by constructor and since both are in `package app`, no import is needed.

- [ ] **Step 3: Compile**

Run: `sbt compile`

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/app/ConfigAwareLlmService.scala src/main/scala/app/ApplicationDI.scala
git commit -m "refactor: extract ConfigAwareLlmService from ApplicationDI"
```

---

### Task 3: Extract ChannelRegistryFactory

**Files:**
- Create: `src/main/scala/gateway/control/ChannelRegistryFactory.scala`
- Modify: `src/main/scala/app/ApplicationDI.scala`

- [ ] **Step 1: Create ChannelRegistryFactory.scala**

Create `src/main/scala/gateway/control/ChannelRegistryFactory.scala`:

```scala
package gateway.control

import scala.concurrent.{ ExecutionContext, Future }

import zio.*

import _root_.config.entity.ConfigRepository
import db.TaskRepository
import gateway.boundary.telegram.{ ConfigAwareTelegramClient, TelegramChannel, WorkflowNotifierLive }
import gateway.entity.*
import orchestration.entity.{ AgentRegistry, TaskExecutor }
import sttp.client4.DefaultFutureBackend

object ChannelRegistryFactory:

  val live
    : ZLayer[
      Ref[config.entity.GatewayConfig] & AgentRegistry & TaskRepository & TaskExecutor & ConfigRepository &
        decision.entity.DecisionRepository,
      Nothing,
      ChannelRegistry,
    ] =
    ZLayer.scoped {
      for
        configRef     <- ZIO.service[Ref[config.entity.GatewayConfig]]
        agentRegistry <- ZIO.service[AgentRegistry]
        repository    <- ZIO.service[TaskRepository]
        taskExecutor  <- ZIO.service[TaskExecutor]
        configRepo    <- ZIO.service[ConfigRepository]
        decisionRepo  <- ZIO.service[decision.entity.DecisionRepository]
        channels      <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
        runtime       <- Ref.Synchronized.make(Map.empty[String, ChannelRuntime])
        clients       <- Ref.Synchronized.make(Map.empty[String, TelegramClient])
        backend       <- ZIO.attempt {
                           given ExecutionContext = ExecutionContext.global
                           DefaultFutureBackend()
                         }.orDie
        registry       = ChannelRegistryLive(channels, runtime)
        settings      <- configRepo.getAllSettings.orElseSucceed(Nil)
        settingMap     = settings.map(row => row.key -> row.value).toMap
        websocket     <- WebSocketChannel.make(
                           scopeStrategy = parseSessionScopeStrategy(
                             settingMap.get("channel.websocket.sessionScopeStrategy")
                           )
                         )
        telegramClient = ConfigAwareTelegramClient(configRef, clients, backend)
        telegram      <- TelegramChannel.make(
                           client = telegramClient,
                           workflowNotifier = WorkflowNotifierLive(telegramClient, agentRegistry, repository, taskExecutor),
                           taskRepository = Some(repository),
                           taskExecutor = Some(taskExecutor),
                           decisionRepository = Some(decisionRepo),
                           scopeStrategy = parseSessionScopeStrategy(settingMap.get("telegram.sessionScopeStrategy")),
                         )
        _             <- registry.register(websocket)
        _             <- registry.register(telegram)
        _             <- registerOptionalExternalChannel(
                           registry = registry,
                           name = "discord",
                           enabled = settingMap.get("channel.discord.enabled").exists(_.equalsIgnoreCase("true")),
                           channel =
                             DiscordChannel.make(
                               scopeStrategy = parseSessionScopeStrategy(
                                 settingMap.get("channel.discord.sessionScopeStrategy")
                               ),
                               config = DiscordConfig(
                                 botToken = settingMap.getOrElse("channel.discord.botToken", ""),
                                 guildId = settingMap.get("channel.discord.guildId").map(_.trim).filter(_.nonEmpty),
                                 defaultChannelId = settingMap.get("channel.discord.channelId").map(_.trim).filter(_.nonEmpty),
                               ),
                             ),
                         )
        _             <- registerOptionalExternalChannel(
                           registry = registry,
                           name = "slack",
                           enabled = settingMap.get("channel.slack.enabled").exists(_.equalsIgnoreCase("true")),
                           channel =
                             SlackChannel.make(
                               scopeStrategy = parseSessionScopeStrategy(settingMap.get("channel.slack.sessionScopeStrategy")),
                               config = SlackConfig(
                                 appToken = settingMap.getOrElse("channel.slack.appToken", ""),
                                 botToken = settingMap.get("channel.slack.botToken").map(_.trim).filter(_.nonEmpty),
                                 defaultChannelId = settingMap.get("channel.slack.channelId").map(_.trim).filter(_.nonEmpty),
                                 socketMode = settingMap.get("channel.slack.socketMode").exists(_.equalsIgnoreCase("true")),
                               ),
                             ),
                         )
      yield registry
    }

  private def registerOptionalExternalChannel(
    registry: ChannelRegistry,
    name: String,
    enabled: Boolean,
    channel: UIO[MessageChannel],
  ): UIO[Unit] =
    if enabled then
      channel.flatMap(registry.register)
    else registry.markNotConfigured(name)

  private def parseSessionScopeStrategy(raw: Option[String]): SessionScopeStrategy =
    raw
      .flatMap(SessionScopeStrategy.fromString)
      .getOrElse(SessionScopeStrategy.PerConversation)
```

- [ ] **Step 2: Replace in ApplicationDI**

In `src/main/scala/app/ApplicationDI.scala`:

1. Remove the `channelRegistryLayer` val, `registerOptionalExternalChannel` method, and `parseSessionScopeStrategy` method.
2. In `commonLayers`, replace `channelRegistryLayer,` with `ChannelRegistryFactory.live,`.
3. Add import: `import gateway.control.ChannelRegistryFactory`
4. Remove now-unused imports: `gateway.entity.*` entries no longer needed (like `SlackChannel`, `SlackConfig`, `DiscordChannel`, `DiscordConfig`, `WebSocketChannel`, `ChannelRegistryLive`, `ChannelRuntime`, `SessionScopeStrategy`), `sttp.client4.DefaultFutureBackend`, and `scala.concurrent.{ExecutionContext, Future}` if no longer used.

- [ ] **Step 3: Compile**

Run: `sbt compile`

Fix any unused import warnings — `-Werror` makes them fatal.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/gateway/control/ChannelRegistryFactory.scala src/main/scala/app/ApplicationDI.scala
git commit -m "refactor: extract ChannelRegistryFactory from ApplicationDI"
```

---

### Task 4: Extract WorkspaceRunServiceFactory and IssueWorkReportProjectionFactory

**Files:**
- Create: `src/main/scala/workspace/control/WorkspaceRunServiceFactory.scala`
- Create: `src/main/scala/board/control/IssueWorkReportProjectionFactory.scala`
- Modify: `src/main/scala/app/ApplicationDI.scala`

- [ ] **Step 1: Create WorkspaceRunServiceFactory.scala**

Create `src/main/scala/workspace/control/WorkspaceRunServiceFactory.scala`:

```scala
package workspace.control

import zio.*

import _root_.config.entity.ConfigRepository
import demo.control.MockAgentRunner
import demo.entity.DemoConfig

object WorkspaceRunServiceFactory:

  val live: ZLayer[ConfigRepository & WorkspaceRunService.LiveDeps, Nothing, WorkspaceRunService] =
    ZLayer.scoped {
      for
        configRepo                               <- ZIO.service[ConfigRepository]
        rows                                     <- configRepo.getAllSettings.orElseSucceed(Nil)
        demoConfig                                = DemoConfig.fromSettings(rows.map(r => r.key -> r.value).toMap)
        mockFn                                    = MockAgentRunner.runner(demoConfig)
        runner: WorkspaceRunService.RunCliAgentFn =
          (argv, cwd, onLine, envVars) =>
            if argv.headOption.contains("mock") then mockFn(argv, cwd, onLine, envVars)
            else CliAgentRunner.runProcessStreaming(argv, cwd, onLine, envVars)
        wsService                                <- WorkspaceRunService.liveWithAgent(runner).build.map(_.get[WorkspaceRunService])
      yield wsService
    }
```

- [ ] **Step 2: Create IssueWorkReportProjectionFactory.scala**

Create `src/main/scala/board/control/IssueWorkReportProjectionFactory.scala`:

```scala
package board.control

import zio.*

import issues.control.{ IssueWorkReportHydrator, IssueWorkReportSubscriber }
import orchestration.control.WorkReportEventBus

object IssueWorkReportProjectionFactory:

  val live
    : ZLayer[WorkReportEventBus & issues.entity.IssueRepository & taskrun.entity.TaskRunRepository, Nothing, issues.entity.IssueWorkReportProjection] =
    ZLayer.scoped {
      for
        bus         <- ZIO.service[WorkReportEventBus]
        issueRepo   <- ZIO.service[issues.entity.IssueRepository]
        taskRunRepo <- ZIO.service[taskrun.entity.TaskRunRepository]
        projection  <- issues.entity.IssueWorkReportProjection.make
        _           <- IssueWorkReportHydrator.runStartup(projection, issueRepo, taskRunRepo)
        _           <- IssueWorkReportSubscriber(bus, projection, issueRepo).start
      yield projection
    }
```

- [ ] **Step 3: Update ApplicationDI**

In `src/main/scala/app/ApplicationDI.scala`:

1. Remove `workspaceRunServiceLayer` val.
2. Remove `issueWorkReportProjectionLayer` val.
3. In `webServerLayer`, replace `workspaceRunServiceLayer,` with `WorkspaceRunServiceFactory.live,`.
4. In `webServerLayer`, replace `issueWorkReportProjectionLayer,` with `IssueWorkReportProjectionFactory.live,`.
5. Add imports:
   ```scala
   import workspace.control.WorkspaceRunServiceFactory
   import board.control.IssueWorkReportProjectionFactory
   ```
6. Remove now-unused imports: `demo.control.MockAgentRunner`, `demo.entity.DemoConfig`, `issues.control.{IssueWorkReportHydrator, IssueWorkReportSubscriber}` (if only used by removed code).

- [ ] **Step 4: Compile**

Run: `sbt compile`

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/workspace/control/WorkspaceRunServiceFactory.scala src/main/scala/board/control/IssueWorkReportProjectionFactory.scala src/main/scala/app/ApplicationDI.scala
git commit -m "refactor: extract WorkspaceRunServiceFactory and IssueWorkReportProjectionFactory from ApplicationDI"
```

---

### Task 5: Part 2 verification — compile and test

- [ ] **Step 1: Full compile**

Run: `sbt compile`

- [ ] **Step 2: Run all tests**

Run: `sbt test`

- [ ] **Step 3: Commit any fixups**

If any unused import or minor issue was found, fix and commit.

---

### Task 6: Add llm4zio dependency to configDomain in build.sbt

**Files:**
- Modify: `build.sbt`

- [ ] **Step 1: Update configDomain.dependsOn**

In `build.sbt`, change (around line 300):

```scala
lazy val configDomain = (project in file("modules/config-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore)
```

To:

```scala
lazy val configDomain = (project in file("modules/config-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, llm4zio)
```

- [ ] **Step 2: Compile**

Run: `sbt compile`

- [ ] **Step 3: Commit**

```bash
git add build.sbt
git commit -m "build: add llm4zio dependency to configDomain"
```

---

### Task 7: Create ProviderConfig, update ProviderModels + AIModels

**Files:**
- Modify: `modules/config-domain/src/main/scala/config/entity/ProviderModels.scala`
- Modify: `modules/config-domain/src/main/scala/config/entity/AIModels.scala`
- Modify: `modules/config-domain/src/main/scala/config/entity/ModelServiceModels.scala`

This task replaces `AIProvider` with `LlmProvider` and `AIProviderConfig` with `LlmConfig` + `ProviderConfig`. It also adds a `ProviderConfig.toLlmConfig` helper.

- [ ] **Step 1: Rewrite ProviderModels.scala**

Replace the entire file `modules/config-domain/src/main/scala/config/entity/ProviderModels.scala`:

```scala
package config.entity

import zio.*
import zio.json.*

import llm4zio.core.{ LlmConfig, LlmProvider }

/** Re-export LlmProvider so existing config-domain consumers can still write `config.entity.LlmProvider`.
  * Also provides the `defaultBaseUrl` function that used to live on the deleted `AIProvider` companion.
  */
object LlmProviderOps:
  def defaultBaseUrl(provider: LlmProvider): Option[String] = LlmProvider.defaultBaseUrl(provider)

case class AIResponse(
  output: String,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

/** Application-level config wrapping the library LlmConfig with app-specific fields.
  *
  * This is the type that GatewayConfig, SettingsApplier, AgentConfigResolver, etc. use.
  * It carries the same fields as LlmConfig plus `fallbackChain`.
  */
case class ProviderConfig(
  provider: LlmProvider = LlmProvider.GeminiCli,
  model: String = "gemini-2.5-flash",
  baseUrl: Option[String] = None,
  apiKey: Option[String] = None,
  timeout: zio.Duration = 300.seconds,
  maxRetries: Int = 3,
  requestsPerMinute: Int = 60,
  burstSize: Int = 10,
  acquireTimeout: zio.Duration = 30.seconds,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  fallbackChain: ModelFallbackChain = ModelFallbackChain.empty,
) derives JsonCodec:

  def toLlmConfig: LlmConfig =
    LlmConfig(
      provider = provider,
      model = model,
      baseUrl = baseUrl,
      apiKey = apiKey,
      timeout = timeout,
      maxRetries = maxRetries,
      requestsPerMinute = requestsPerMinute,
      burstSize = burstSize,
      acquireTimeout = acquireTimeout,
      temperature = temperature,
      maxTokens = maxTokens,
    )

object ProviderConfig:
  def withDefaults(config: ProviderConfig): ProviderConfig =
    config.baseUrl match
      case Some(_) => config
      case None    => config.copy(baseUrl = LlmProvider.defaultBaseUrl(config.provider))

case class GeminiResponse(
  output: String,
  exitCode: Int,
) derives JsonCodec

/** Type aliases for migration — these let us do a find/replace in consumer files. */
type AIProvider = LlmProvider
val AIProvider = LlmProvider

type AIProviderConfig = ProviderConfig
val AIProviderConfig = ProviderConfig
```

**Important:** The type aliases `AIProvider = LlmProvider` and `AIProviderConfig = ProviderConfig` at the bottom allow all 30 consumer files to compile immediately. We remove these aliases in a later task after migrating all consumers.

- [ ] **Step 2: Update AIModels.scala**

In `modules/config-domain/src/main/scala/config/entity/AIModels.scala`:

Add import at top:

```scala
import llm4zio.core.LlmProvider
```

Replace all occurrences of `AIProvider` with `LlmProvider`. Specifically:

- `case class AIModel(provider: AIProvider,` → `case class AIModel(provider: LlmProvider,`
- `case class ModelRef(provider: Option[AIProvider],` → `case class ModelRef(provider: Option[LlmProvider],`
- All `AIProvider.GeminiCli` → `LlmProvider.GeminiCli`, etc. (but the type alias means either works)

Actually, since we defined `type AIProvider = LlmProvider` in ProviderModels.scala, this file will compile without changes. We'll do the rename in a later sweep task.

- [ ] **Step 3: Update ModelServiceModels.scala**

In `modules/config-domain/src/main/scala/config/entity/ModelServiceModels.scala`, the type alias handles this — no changes needed now.

- [ ] **Step 4: Compile**

Run: `sbt configDomain/compile`

- [ ] **Step 5: Full compile**

Run: `sbt compile`

The type aliases ensure all consumers still compile.

- [ ] **Step 6: Commit**

```bash
git add modules/config-domain/src/main/scala/config/entity/ProviderModels.scala
git commit -m "refactor: replace AIProvider/AIProviderConfig with LlmProvider/ProviderConfig + type aliases"
```

---

### Task 8: Update GatewayConfig to use ProviderConfig natively

**Files:**
- Modify: `modules/config-domain/src/main/scala/config/entity/GatewayConfigModels.scala`

- [ ] **Step 1: Update GatewayConfig**

In `modules/config-domain/src/main/scala/config/entity/GatewayConfigModels.scala`:

Change the defaults for `gemini*` fields to reference `ProviderConfig()` instead of `AIProviderConfig()` (which is now an alias anyway, but let's use the real name):

```scala
  geminiModel: String = ProviderConfig().model,
  geminiTimeout: zio.Duration = ProviderConfig().timeout,
  geminiMaxRetries: Int = ProviderConfig().maxRetries,
  geminiRequestsPerMinute: Int = ProviderConfig().requestsPerMinute,
  geminiBurstSize: Int = ProviderConfig().burstSize,
  geminiAcquireTimeout: zio.Duration = ProviderConfig().acquireTimeout,
  aiProvider: Option[ProviderConfig] = None,
```

And update `resolvedProviderConfig`:

```scala
  def resolvedProviderConfig: ProviderConfig =
    ProviderConfig.withDefaults(
      aiProvider.getOrElse(
        ProviderConfig(
          model = geminiModel,
          timeout = geminiTimeout,
          maxRetries = geminiMaxRetries,
          requestsPerMinute = geminiRequestsPerMinute,
          burstSize = geminiBurstSize,
          acquireTimeout = geminiAcquireTimeout,
        )
      )
    )
```

Do the same for the `MigrationConfig.apply` factory method — replace `AIProviderConfig` references with `ProviderConfig`.

- [ ] **Step 2: Compile**

Run: `sbt compile`

- [ ] **Step 3: Commit**

```bash
git add modules/config-domain/src/main/scala/config/entity/GatewayConfigModels.scala
git commit -m "refactor: update GatewayConfig to use ProviderConfig directly"
```

---

### Task 9: Update RateLimiter.fromAIProviderConfig → fromProviderConfig

**Files:**
- Modify: `modules/shared-services/src/main/scala/shared/services/RateLimiter.scala`
- Modify: `src/test/scala/app/control/RateLimiterPropertySpec.scala`

- [ ] **Step 1: Update RateLimiter**

In `modules/shared-services/src/main/scala/shared/services/RateLimiter.scala`:

Change the import:

```scala
import _root_.config.entity.{ MigrationConfig, ProviderConfig }
```

Rename the method:

```scala
object RateLimiterConfig:
  def fromProviderConfig(config: ProviderConfig): RateLimiterConfig =
    RateLimiterConfig(
      requestsPerMinute = config.requestsPerMinute,
      burstSize = config.burstSize,
      acquireTimeout = config.acquireTimeout,
    )

  def fromMigrationConfig(config: MigrationConfig): RateLimiterConfig =
    fromProviderConfig(config.resolvedProviderConfig)
```

- [ ] **Step 2: Update RateLimiterPropertySpec**

In `src/test/scala/app/control/RateLimiterPropertySpec.scala`:

Change:
```scala
import _root_.config.entity.{ AIProvider, AIProviderConfig, MigrationConfig }
```
To:
```scala
import _root_.config.entity.{ MigrationConfig, ProviderConfig }
import llm4zio.core.LlmProvider
```

Change:
```scala
      test("fromAIProviderConfig maps all fields correctly") {
```
To:
```scala
      test("fromProviderConfig maps all fields correctly") {
```

Change:
```scala
          val providerConfig    = AIProviderConfig(
```
To:
```scala
          val providerConfig    = ProviderConfig(
```

Change all `AIProvider.` references to `LlmProvider.`.

Change:
```scala
          val rateLimiterConfig = RateLimiterConfig.fromAIProviderConfig(providerConfig)
```
To:
```scala
          val rateLimiterConfig = RateLimiterConfig.fromProviderConfig(providerConfig)
```

- [ ] **Step 3: Find and update all callers of fromAIProviderConfig**

Run: `grep -rn "fromAIProviderConfig" --include="*.scala" src/ modules/`

Update each caller to use `fromProviderConfig`.

- [ ] **Step 4: Compile and test**

Run: `sbt compile && sbt 'testOnly app.control.RateLimiterPropertySpec'`

- [ ] **Step 5: Commit**

```bash
git add modules/shared-services/src/main/scala/shared/services/RateLimiter.scala src/test/scala/app/control/RateLimiterPropertySpec.scala
git commit -m "refactor: rename RateLimiter.fromAIProviderConfig to fromProviderConfig"
```

---

### Task 10: Update SettingsApplier to produce ProviderConfig

**Files:**
- Modify: `src/main/scala/config/SettingsApplier.scala`

- [ ] **Step 1: Update SettingsApplier**

In `src/main/scala/config/SettingsApplier.scala`:

Change import:
```scala
import _root_.config.entity.*
```

Rename `toAIProviderConfig` → `toProviderConfig` and update its return type and body to produce `ProviderConfig`:

```scala
  def toProviderConfig(settings: Map[String, String]): Option[ProviderConfig] =
    settings.get("ai.provider").map { providerStr =>
      val provider = parseLlmProvider(providerStr).getOrElse(LlmProvider.GeminiCli)
      ProviderConfig(
        provider = provider,
        model = settings.getOrElse("ai.model", "gemini-2.5-flash"),
        baseUrl = settings.get("ai.baseUrl").filter(_.nonEmpty),
        apiKey = settings.get("ai.apiKey").filter(_.nonEmpty),
        timeout = parseDuration(settings.get("ai.timeout")).getOrElse(300.seconds),
        maxRetries = settings.get("ai.maxRetries").flatMap(_.toIntOption).getOrElse(3),
        requestsPerMinute = settings.get("ai.requestsPerMinute").flatMap(_.toIntOption).getOrElse(60),
        burstSize = settings.get("ai.burstSize").flatMap(_.toIntOption).getOrElse(10),
        acquireTimeout = parseDuration(settings.get("ai.acquireTimeout")).getOrElse(30.seconds),
        temperature = settings.get("ai.temperature").flatMap(_.toDoubleOption),
        maxTokens = settings.get("ai.maxTokens").flatMap(_.toIntOption),
        fallbackChain = parseFallbackChain(settings.get("ai.fallbackChain"), provider),
      )
    }
```

Update `toGatewayConfig` to call `toProviderConfig` instead of `toAIProviderConfig`:
```scala
    GatewayConfig(
      aiProvider = toProviderConfig(settings),
```

Rename `parseAIProvider` → `parseLlmProvider` and update its body to use `LlmProvider`:

```scala
  private def parseLlmProvider(str: String): Option[LlmProvider] =
    str match
      case "GeminiCli" => Some(LlmProvider.GeminiCli)
      case "GeminiApi" => Some(LlmProvider.GeminiApi)
      case "OpenAi"    => Some(LlmProvider.OpenAI)
      case "Anthropic" => Some(LlmProvider.Anthropic)
      case "LmStudio"  => Some(LlmProvider.LmStudio)
      case "Ollama"    => Some(LlmProvider.Ollama)
      case "OpenCode"  => Some(LlmProvider.OpenCode)
      case "Mock"      => Some(LlmProvider.Mock)
      case _           => None
```

Update `parseFallbackChain` signature to use `LlmProvider`:
```scala
  private def parseFallbackChain(valueOpt: Option[String], defaultProvider: LlmProvider): ModelFallbackChain =
```

Update `parseModelRef` to use `LlmProvider`:
```scala
  private def parseModelRef(raw: String, defaultProvider: LlmProvider): Option[ModelRef] =
    raw.split(":", 2).toList match
      case providerRaw :: modelRaw :: Nil =>
        val modelId = modelRaw.trim
        parseLlmProvider(providerRaw.trim).filter(_ => modelId.nonEmpty).map { provider =>
          ModelRef(provider = Some(provider), modelId = modelId)
        }
      case modelOnly :: Nil               =>
        val modelId = modelOnly.trim
        if modelId.nonEmpty then Some(ModelRef(provider = Some(defaultProvider), modelId = modelId))
        else None
      case _                              => None
```

Add the `LlmProvider` import:
```scala
import llm4zio.core.LlmProvider
```

- [ ] **Step 2: Find callers of toAIProviderConfig**

Run: `grep -rn "toAIProviderConfig" --include="*.scala" src/ modules/`

Update any callers to use `toProviderConfig`.

- [ ] **Step 3: Compile**

Run: `sbt compile`

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/config/SettingsApplier.scala
git commit -m "refactor: rename SettingsApplier.toAIProviderConfig to toProviderConfig, use LlmProvider"
```

---

### Task 11: Update ConfigLoader to use ProviderConfig and LlmProvider

**Files:**
- Modify: `src/main/scala/config/ConfigLoader.scala`
- Modify: `src/test/scala/config/ConfigLoaderSpec.scala`

- [ ] **Step 1: Update ConfigLoader**

In `src/main/scala/config/ConfigLoader.scala`:

Change import:
```scala
import _root_.config.entity.{ GatewayConfig, ProviderConfig, TelegramMode }
import llm4zio.core.LlmProvider
```

Replace all `AIProviderConfig` with `ProviderConfig` and `AIProvider` with `LlmProvider`. Key changes:

- `def loadAIProviderFromDefaultConfig` → `def loadProviderFromDefaultConfig` (return `Option[ProviderConfig]`)
- `def loadAIProviderFromFile` → `def loadProviderFromFile` (return `Option[ProviderConfig]`)
- `def applyAIEnvironmentOverrides` — keep name but use `ProviderConfig` internally
- `def validateAIProvider` → `def validateProvider`
- `private def parseAIProvider` → `private def parseLlmProvider`, use `LlmProvider` enum
- `private def parseAIProviderSection` → `private def parseProviderSection`, use `ProviderConfig`
- All `AIProviderConfig()` → `ProviderConfig()`
- All `AIProvider.` → `LlmProvider.`
- `config.copy(aiProvider = Some(providerConfig))` stays as-is (field name unchanged)

- [ ] **Step 2: Update ConfigLoaderSpec**

In `src/test/scala/config/ConfigLoaderSpec.scala`:

Change import to use `ProviderConfig` and `LlmProvider`. Update:
- `AIProviderConfig(` → `ProviderConfig(`
- `AIProvider.OpenAi` → `LlmProvider.OpenAI`
- `AIProvider.Anthropic` → `LlmProvider.Anthropic`
- `AIProvider.GeminiApi` → `LlmProvider.GeminiApi`
- `loadAIProviderFromFile` → `loadProviderFromFile`
- `loadAIProviderFromDefaultConfig` → `loadProviderFromDefaultConfig`

- [ ] **Step 3: Compile and test**

Run: `sbt compile && sbt 'testOnly config.ConfigLoaderSpec'`

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/config/ConfigLoader.scala src/test/scala/config/ConfigLoaderSpec.scala
git commit -m "refactor: update ConfigLoader to use ProviderConfig and LlmProvider"
```

---

### Task 12: Update ModelService to use ProviderConfig and LlmProvider

**Files:**
- Modify: `src/main/scala/config/control/ModelService.scala`
- Modify: `modules/config-domain/src/main/scala/config/entity/ModelServiceModels.scala`
- Modify: `modules/config-domain/src/main/scala/config/entity/AIModels.scala`
- Modify: `src/test/scala/web/controllers/SettingsControllerSpec.scala`
- Modify: `src/test/scala/web/controllers/HealthControllerSpec.scala`

- [ ] **Step 1: Update ModelServiceModels.scala**

In `modules/config-domain/src/main/scala/config/entity/ModelServiceModels.scala`:

Add import:
```scala
import llm4zio.core.LlmProvider
```

Replace `AIProvider` with `LlmProvider`:
- `provider: AIProvider` → `provider: LlmProvider` in `ProviderProbeStatus`, `ProviderModelGroup`
- `case ProbeFailed(provider: AIProvider,` → `case ProbeFailed(provider: LlmProvider,`

- [ ] **Step 2: Update AIModels.scala**

In `modules/config-domain/src/main/scala/config/entity/AIModels.scala`:

Add import:
```scala
import llm4zio.core.LlmProvider
```

Replace `AIProvider` with `LlmProvider`:
- `case class AIModel(provider: AIProvider,` → `case class AIModel(provider: LlmProvider,`
- `case class ModelRef(provider: Option[AIProvider],` → `case class ModelRef(provider: Option[LlmProvider],`

- [ ] **Step 3: Update ModelService.scala**

In `src/main/scala/config/control/ModelService.scala`:

Change import:
```scala
import _root_.config.entity.*
import llm4zio.core.LlmProvider
```

Replace:
- `def resolveFallbackChain(primary: AIProviderConfig)` → `def resolveFallbackChain(primary: ProviderConfig)`
- All `AIProviderConfig` → `ProviderConfig`
- All `AIProvider.` → `LlmProvider.`
- All `AIProviderConfig.withDefaults(` → `ProviderConfig.withDefaults(`
- All `AIProvider.defaultBaseUrl(` → `LlmProvider.defaultBaseUrl(`

- [ ] **Step 4: Update test stubs**

In `src/test/scala/web/controllers/SettingsControllerSpec.scala`:
- `override def resolveFallbackChain(primary: AIProviderConfig): UIO[List[AIProviderConfig]]` → `override def resolveFallbackChain(primary: ProviderConfig): UIO[List[ProviderConfig]]`

In `src/test/scala/web/controllers/HealthControllerSpec.scala`:
- Same pattern as above, plus update imports.

- [ ] **Step 5: Compile and test**

Run: `sbt compile && sbt 'testOnly web.controllers.SettingsControllerSpec' && sbt 'testOnly web.controllers.HealthControllerSpec'`

- [ ] **Step 6: Commit**

```bash
git add modules/config-domain/src/main/scala/config/entity/ModelServiceModels.scala modules/config-domain/src/main/scala/config/entity/AIModels.scala src/main/scala/config/control/ModelService.scala src/test/scala/web/controllers/SettingsControllerSpec.scala src/test/scala/web/controllers/HealthControllerSpec.scala
git commit -m "refactor: update ModelService and entity models to use LlmProvider/ProviderConfig"
```

---

### Task 13: Update AgentConfigResolver, AgentDispatcher, EmbeddingService

**Files:**
- Modify: `src/main/scala/orchestration/control/AgentConfigResolver.scala`
- Modify: `src/main/scala/orchestration/control/AgentDispatcher.scala`
- Modify: `src/main/scala/memory/control/EmbeddingService.scala`
- Modify: `src/test/scala/orchestration/AgentConfigResolverSpec.scala`
- Modify: `src/test/scala/memory/EmbeddingServiceSpec.scala`

- [ ] **Step 1: Update AgentConfigResolver**

In `src/main/scala/orchestration/control/AgentConfigResolver.scala`:

Change import:
```scala
import _root_.config.entity.{ ModelFallbackChain, ModelRef, ProviderConfig }
import llm4zio.core.LlmProvider
```

Replace:
- All `AIProviderConfig` → `ProviderConfig`
- All `AIProvider` → `LlmProvider`
- `def resolveConfig(agentName: String): IO[PersistenceError, AIProviderConfig]` → `def resolveConfig(agentName: String): IO[PersistenceError, ProviderConfig]`
- `AIProvider.defaultBaseUrl(` → `LlmProvider.defaultBaseUrl(`
- `val live: ZLayer[TaskRepository & AIProviderConfig,` → `val live: ZLayer[TaskRepository & ProviderConfig,`
- `startupConfig: AIProviderConfig` → `startupConfig: ProviderConfig`
- `AIProviderConfig.withDefaults(` → `ProviderConfig.withDefaults(`
- All `Some(AIProvider.GeminiCli)` → `Some(LlmProvider.GeminiCli)` etc.
- `private def parseProvider(value: String): Option[AIProvider]` → `private def parseProvider(value: String): Option[LlmProvider]`
- `private def defaultModelFor(provider: AIProvider)` → `private def defaultModelFor(provider: LlmProvider)`

- [ ] **Step 2: Update AgentDispatcher**

In `src/main/scala/orchestration/control/AgentDispatcher.scala`:

Change import to remove `AIProvider, AIProviderConfig`. The file uses these for the `fallbackConfigs` and `aiConfigToLlmConfig` private methods. Replace:
- All `AIProviderConfig` → `ProviderConfig`
- Remove `aiConfigToLlmConfig` and `aiProviderToLlmProvider` private methods — use `config.toLlmConfig` instead
- In `fallbackConfigs(primary: ProviderConfig)`: change `aiConfigToLlmConfig(primary)` → `primary.toLlmConfig`
- Change `aiConfigToLlmConfig(AIProviderConfig.withDefaults(...))` → `ProviderConfig.withDefaults(...).toLlmConfig`

- [ ] **Step 3: Update EmbeddingService**

In `src/main/scala/memory/control/EmbeddingService.scala`:

Change import:
```scala
import _root_.config.entity.{ GatewayConfig, ProviderConfig }
import llm4zio.core.LlmProvider
```

Replace:
- All `AIProvider.` → `LlmProvider.`
- All `config: AIProviderConfig` → `config: ProviderConfig`

- [ ] **Step 4: Update tests**

In `src/test/scala/orchestration/AgentConfigResolverSpec.scala`:
- Change import to `ProviderConfig` and `LlmProvider`
- Replace `AIProviderConfig(` → `ProviderConfig(`
- Replace `AIProvider.` → `LlmProvider.`

In `src/test/scala/memory/EmbeddingServiceSpec.scala`:
- Change import to `ProviderConfig` and `LlmProvider`
- Replace `AIProviderConfig(` → `ProviderConfig(`
- Replace `AIProvider.` → `LlmProvider.`

- [ ] **Step 5: Compile and test**

Run: `sbt compile && sbt 'testOnly orchestration.AgentConfigResolverSpec' && sbt 'testOnly memory.EmbeddingServiceSpec'`

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/orchestration/control/AgentConfigResolver.scala src/main/scala/orchestration/control/AgentDispatcher.scala src/main/scala/memory/control/EmbeddingService.scala src/test/scala/orchestration/AgentConfigResolverSpec.scala src/test/scala/memory/EmbeddingServiceSpec.scala
git commit -m "refactor: update AgentConfigResolver, AgentDispatcher, EmbeddingService to use ProviderConfig/LlmProvider"
```

---

### Task 14: Update ChatController, PlannerAgentService, AnalysisAgentRunner

**Files:**
- Modify: `src/main/scala/conversation/boundary/ChatController.scala`
- Modify: `src/main/scala/orchestration/control/PlannerAgentService.scala`
- Modify: `src/main/scala/analysis/control/AnalysisAgentRunner.scala`
- Modify: `src/test/scala/web/controllers/ChatControllerGatewaySpec.scala`
- Modify: `src/test/scala/orchestration/control/PlannerAgentServiceSpec.scala`
- Modify: `src/test/scala/web/controllers/IssueControllerSpec.scala`

- [ ] **Step 1: Update ChatController**

In `src/main/scala/conversation/boundary/ChatController.scala`:

Change import — replace `AIProvider, AIProviderConfig` with `ProviderConfig` and add `LlmProvider`:
```scala
import _root_.config.entity.ProviderConfig
import llm4zio.core.LlmProvider
```

Replace:
- All `AIProviderConfig` → `ProviderConfig`
- All `AIProvider.` → `LlmProvider.`
- Remove private `aiConfigToLlmConfig` and `aiProviderToLlmProvider` methods — use `config.toLlmConfig`
- In `fallbackConfigs`: `aiConfigToLlmConfig(primary)` → `primary.toLlmConfig`, `aiConfigToLlmConfig(AIProviderConfig.withDefaults(...))` → `ProviderConfig.withDefaults(...).toLlmConfig`

- [ ] **Step 2: Update PlannerAgentService**

In `src/main/scala/orchestration/control/PlannerAgentService.scala`:

Same pattern — change import, replace types, remove `aiConfigToLlmConfig`/`aiProviderToLlmProvider`, use `config.toLlmConfig`.

For `startupAiConfig: AIProviderConfig` → `startupAiConfig: ProviderConfig` (field in the case class).

For `AIProviderConfig` in ZLayer deps → `ProviderConfig`.

In `resolveGlobalAiConfig`:
```scala
SettingsApplier.toProviderConfig(settings).map(ProviderConfig.withDefaults).getOrElse(startupAiConfig)
```

- [ ] **Step 3: Update AnalysisAgentRunner**

In `src/main/scala/analysis/control/AnalysisAgentRunner.scala`:

Same pattern. Replace `AIProvider, AIProviderConfig` imports with `ProviderConfig` and `LlmProvider`. Remove private conversion methods, use `.toLlmConfig`.

- [ ] **Step 4: Update test files**

In `src/test/scala/web/controllers/ChatControllerGatewaySpec.scala`:
- Replace `AIProviderConfig` → `ProviderConfig`, add `LlmProvider` import
- `AIProviderConfig.withDefaults(AIProviderConfig())` → `ProviderConfig.withDefaults(ProviderConfig())`

In `src/test/scala/orchestration/control/PlannerAgentServiceSpec.scala`:
- Same replacements
- `AIProviderConfig.withDefaults(AIProviderConfig())` → `ProviderConfig.withDefaults(ProviderConfig())`

In `src/test/scala/web/controllers/IssueControllerSpec.scala`:
- Replace `AIProviderConfig` → `ProviderConfig`
- `ZIO.succeed(AIProviderConfig())` → `ZIO.succeed(ProviderConfig())`

- [ ] **Step 5: Compile and test**

Run: `sbt compile && sbt test`

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/conversation/boundary/ChatController.scala src/main/scala/orchestration/control/PlannerAgentService.scala src/main/scala/analysis/control/AnalysisAgentRunner.scala src/test/scala/web/controllers/ChatControllerGatewaySpec.scala src/test/scala/orchestration/control/PlannerAgentServiceSpec.scala src/test/scala/web/controllers/IssueControllerSpec.scala
git commit -m "refactor: update ChatController, PlannerAgentService, AnalysisAgentRunner to ProviderConfig/LlmProvider"
```

---

### Task 15: Update ApplicationDI — remove conversion functions, update CommonServices type

**Files:**
- Modify: `src/main/scala/app/ApplicationDI.scala`
- Modify: `src/main/scala/app/ConfigAwareLlmService.scala`

- [ ] **Step 1: Update ApplicationDI**

In `src/main/scala/app/ApplicationDI.scala`:

1. Remove `aiProviderToLlmProvider` method.
2. Remove `aiConfigToLlmConfig` method.
3. In the `CommonServices` type alias, change `AIProviderConfig` → `ProviderConfig`.
4. Update imports — remove `AIProvider, AIProviderConfig` from the config.entity import, add `ProviderConfig`.
5. Remove any unused imports that `-Werror` will flag.

- [ ] **Step 2: Update ConfigAwareLlmService**

In `src/main/scala/app/ConfigAwareLlmService.scala`:

Replace the `fallbackConfigs` method to use `ProviderConfig.toLlmConfig` instead of `ApplicationDI.aiConfigToLlmConfig`:

```scala
  private def fallbackConfigs(primary: ProviderConfig): List[LlmConfig] =
    val primaryLlm = primary.toLlmConfig
    val fallback   = primary.fallbackChain.models.map { ref =>
      ProviderConfig.withDefaults(
        primary.copy(
          provider = ref.provider.getOrElse(primary.provider),
          model = ref.modelId,
        )
      ).toLlmConfig
    }
    (primaryLlm :: fallback).distinct
```

Update import:
```scala
import _root_.config.entity.{ GatewayConfig, ProviderConfig }
```

Remove:
```scala
import app.ApplicationDI
```

- [ ] **Step 3: Compile**

Run: `sbt compile`

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/app/ApplicationDI.scala src/main/scala/app/ConfigAwareLlmService.scala
git commit -m "refactor: remove AIProvider conversion functions from ApplicationDI"
```

---

### Task 16: Update SettingsValidator

**Files:**
- Modify: `modules/config-domain/src/main/scala/config/boundary/SettingsValidator.scala`

- [ ] **Step 1: Update validateAIProvider**

In `modules/config-domain/src/main/scala/config/boundary/SettingsValidator.scala`:

The `validateAIProvider` private method uses string matching, not enum references. However, since `AIProvider.OpenAi` is now `LlmProvider.OpenAI`, the valid values submitted via settings form stay as strings like "OpenAi", "GeminiCli" etc. — these are form values, not enum names.

Check if this validator needs `LlmProvider` import. Looking at the code: it validates string values `"GeminiCli", "GeminiApi", "OpenAi", "Anthropic", "LmStudio", "Ollama", "OpenCode", "Mock"` — these are the serialized form values that `SettingsApplier.parseLlmProvider` expects. No code change needed here since it doesn't reference the `AIProvider` type.

- [ ] **Step 2: Compile**

Run: `sbt configDomain/compile`

No changes expected. Skip commit.

---

### Task 17: Remove type aliases, final cleanup

**Files:**
- Modify: `modules/config-domain/src/main/scala/config/entity/ProviderModels.scala`

- [ ] **Step 1: Remove type aliases**

In `modules/config-domain/src/main/scala/config/entity/ProviderModels.scala`, remove the aliases at the bottom:

```scala
/** Type aliases for migration — these let us do a find/replace in consumer files. */
type AIProvider = LlmProvider
val AIProvider = LlmProvider

type AIProviderConfig = ProviderConfig
val AIProviderConfig = ProviderConfig
```

- [ ] **Step 2: Compile**

Run: `sbt compile`

If any file still references `AIProvider` or `AIProviderConfig`, fix it. The grep from earlier identified all 30 files — if Tasks 7-15 covered them all, this should compile clean.

- [ ] **Step 3: Run grep to verify no remaining references**

```bash
grep -rn 'AIProvider\|AIProviderConfig' --include="*.scala" src/ modules/ | grep -v target | grep -v docs | grep -v '\.bak'
```

Should return empty (or only comments).

- [ ] **Step 4: Commit**

```bash
git add modules/config-domain/src/main/scala/config/entity/ProviderModels.scala
git commit -m "refactor: remove AIProvider/AIProviderConfig type aliases — migration complete"
```

---

### Task 18: Final verification

- [ ] **Step 1: Full compile**

Run: `sbt compile`

- [ ] **Step 2: Run all tests**

Run: `sbt test`

- [ ] **Step 3: Verify no remaining AIProvider references**

```bash
grep -rn 'AIProvider\b' --include="*.scala" src/ modules/ llm4zio/ | grep -v target | grep -v docs
grep -rn 'AIProviderConfig' --include="*.scala" src/ modules/ llm4zio/ | grep -v target | grep -v docs
grep -rn 'aiConfigToLlmConfig\|aiProviderToLlmProvider' --include="*.scala" src/ modules/ | grep -v target | grep -v docs
grep -rn 'fromAIProviderConfig\|toAIProviderConfig' --include="*.scala" src/ modules/ | grep -v target | grep -v docs
```

All should return empty.

- [ ] **Step 4: Verify ApplicationDI is smaller**

```bash
wc -l src/main/scala/app/ApplicationDI.scala
```

Should be ~385 lines (down from 668).

- [ ] **Step 5: Fix any remaining issues and commit**
