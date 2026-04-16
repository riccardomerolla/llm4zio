# Unified Connector Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify AI providers and CLI tools under a single `Connector` type hierarchy in the llm4zio library, with a single Settings UI and agent-level overrides, removing scattered configuration and the legacy Advanced Config.

**Architecture:** Two-branch type hierarchy (`ApiConnector` / `CliConnector`) with shared `Connector` supertrait in the library. `ConnectorRegistry` replaces the gateway's provider dispatch. `ConnectorConfigResolver` replaces scattered config resolution. Settings page becomes the single source of truth.

**Tech Stack:** Scala 3, ZIO 2.x, zio-json, zio-http, Scalatags, HTMX, ZIO Test

**Spec:** `docs/superpowers/specs/2026-04-15-unified-connector-architecture-design.md`

---

## Phase 1: Library Core Types

### Task 1: ConnectorId and ConnectorKind

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/core/Models.scala`
- Test: `llm4zio/src/test/scala/llm4zio/core/ConnectorIdSpec.scala`

- [ ] **Step 1: Write test for ConnectorId**

Create `llm4zio/src/test/scala/llm4zio/core/ConnectorIdSpec.scala`:

```scala
package llm4zio.core

import zio.*
import zio.json.*
import zio.test.*

object ConnectorIdSpec extends ZIOSpecDefault:
  def spec = suite("ConnectorId")(
    test("known API connector ids") {
      assertTrue(
        ConnectorId.OpenAI.value == "openai",
        ConnectorId.Anthropic.value == "anthropic",
        ConnectorId.GeminiApi.value == "gemini-api",
        ConnectorId.LmStudio.value == "lm-studio",
        ConnectorId.Ollama.value == "ollama",
      )
    },
    test("known CLI connector ids") {
      assertTrue(
        ConnectorId.ClaudeCli.value == "claude-cli",
        ConnectorId.GeminiCli.value == "gemini-cli",
        ConnectorId.OpenCode.value == "opencode",
        ConnectorId.Codex.value == "codex",
        ConnectorId.Copilot.value == "copilot",
      )
    },
    test("ConnectorKind enum values") {
      assertTrue(
        ConnectorKind.Api != ConnectorKind.Cli,
      )
    },
    test("JSON round-trip") {
      val id = ConnectorId.OpenAI
      val json = id.toJson
      val parsed = json.fromJson[ConnectorId]
      assertTrue(parsed == Right(id))
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zio/testOnly llm4zio.core.ConnectorIdSpec'`
Expected: Compilation failure — `ConnectorId` not found.

- [ ] **Step 3: Implement ConnectorId and ConnectorKind**

Add to `llm4zio/src/main/scala/llm4zio/core/Models.scala` after the `LlmProvider` enum:

```scala
case class ConnectorId(value: String) derives JsonCodec

object ConnectorId:
  // API
  val OpenAI: ConnectorId    = ConnectorId("openai")
  val Anthropic: ConnectorId = ConnectorId("anthropic")
  val GeminiApi: ConnectorId = ConnectorId("gemini-api")
  val LmStudio: ConnectorId  = ConnectorId("lm-studio")
  val Ollama: ConnectorId    = ConnectorId("ollama")
  // CLI
  val ClaudeCli: ConnectorId = ConnectorId("claude-cli")
  val GeminiCli: ConnectorId = ConnectorId("gemini-cli")
  val OpenCode: ConnectorId  = ConnectorId("opencode")
  val Codex: ConnectorId     = ConnectorId("codex")
  val Copilot: ConnectorId   = ConnectorId("copilot")
  // Test
  val Mock: ConnectorId      = ConnectorId("mock")

  val allApi: List[ConnectorId] = List(OpenAI, Anthropic, GeminiApi, LmStudio, Ollama)
  val allCli: List[ConnectorId] = List(ClaudeCli, GeminiCli, OpenCode, Codex, Copilot)
  val all: List[ConnectorId]    = allApi ++ allCli :+ Mock

enum ConnectorKind derives JsonCodec:
  case Api, Cli
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zio/testOnly llm4zio.core.ConnectorIdSpec'`
Expected: All 4 tests PASS.

- [ ] **Step 5: Add LlmProvider.toConnectorId bridge**

Add to the `LlmProvider` companion in `Models.scala`:

```scala
object LlmProvider:
  extension (p: LlmProvider)
    def toConnectorId: ConnectorId = p match
      case LlmProvider.GeminiCli => ConnectorId.GeminiCli
      case LlmProvider.GeminiApi => ConnectorId.GeminiApi
      case LlmProvider.OpenAI    => ConnectorId.OpenAI
      case LlmProvider.Anthropic => ConnectorId.Anthropic
      case LlmProvider.LmStudio  => ConnectorId.LmStudio
      case LlmProvider.Ollama    => ConnectorId.Ollama
      case LlmProvider.OpenCode  => ConnectorId.OpenCode
      case LlmProvider.Mock      => ConnectorId.Mock
```

- [ ] **Step 6: Compile library**

Run: `sbt llm4zio/compile`
Expected: SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/Models.scala llm4zio/src/test/scala/llm4zio/core/ConnectorIdSpec.scala
git commit -m "feat: add ConnectorId, ConnectorKind, and LlmProvider.toConnectorId bridge"
```

---

### Task 2: ConnectorConfig ADT

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/core/ConnectorConfig.scala`
- Test: `llm4zio/src/test/scala/llm4zio/core/ConnectorConfigSpec.scala`

- [ ] **Step 1: Write test for ConnectorConfig**

Create `llm4zio/src/test/scala/llm4zio/core/ConnectorConfigSpec.scala`:

```scala
package llm4zio.core

import zio.*
import zio.json.*
import zio.test.*

object ConnectorConfigSpec extends ZIOSpecDefault:
  def spec = suite("ConnectorConfig")(
    test("ApiConnectorConfig has correct defaults") {
      val cfg = ApiConnectorConfig(connectorId = ConnectorId.OpenAI, model = Some("gpt-4o"))
      assertTrue(
        cfg.timeout == 300.seconds,
        cfg.maxRetries == 3,
        cfg.requestsPerMinute == 60,
        cfg.burstSize == 10,
        cfg.acquireTimeout == 30.seconds,
        cfg.baseUrl.isEmpty,
        cfg.apiKey.isEmpty,
        cfg.temperature.isEmpty,
        cfg.maxTokens.isEmpty,
      )
    },
    test("CliConnectorConfig has correct defaults") {
      val cfg = CliConnectorConfig(connectorId = ConnectorId.GeminiCli, model = Some("gemini-2.5-flash"))
      assertTrue(
        cfg.timeout == 300.seconds,
        cfg.maxRetries == 3,
        cfg.flags.isEmpty,
        cfg.sandbox.isEmpty,
        cfg.turnLimit.isEmpty,
        cfg.envVars.isEmpty,
      )
    },
    test("ConnectorConfig sealed trait dispatches correctly") {
      val api: ConnectorConfig = ApiConnectorConfig(ConnectorId.OpenAI, Some("gpt-4o"))
      val cli: ConnectorConfig = CliConnectorConfig(ConnectorId.GeminiCli, Some("gemini-2.5-flash"))
      assertTrue(
        api.connectorId == ConnectorId.OpenAI,
        cli.connectorId == ConnectorId.GeminiCli,
      )
    },
    test("ApiConnectorConfig JSON round-trip") {
      val cfg = ApiConnectorConfig(
        connectorId = ConnectorId.Anthropic,
        model = Some("claude-sonnet-4"),
        apiKey = Some("sk-test"),
      )
      val json = cfg.toJson
      val parsed = json.fromJson[ApiConnectorConfig]
      assertTrue(parsed == Right(cfg))
    },
    test("CliConnectorConfig JSON round-trip") {
      val cfg = CliConnectorConfig(
        connectorId = ConnectorId.ClaudeCli,
        model = Some("claude-sonnet-4"),
        flags = Map("print" -> "true"),
      )
      val json = cfg.toJson
      val parsed = json.fromJson[CliConnectorConfig]
      assertTrue(parsed == Right(cfg))
    },
    test("FallbackChain preserves order") {
      val chain = FallbackChain(List(
        ApiConnectorConfig(ConnectorId.OpenAI, Some("gpt-4o")),
        ApiConnectorConfig(ConnectorId.Anthropic, Some("claude-sonnet-4")),
      ))
      assertTrue(chain.connectors.size == 2)
      assertTrue(chain.connectors.head.connectorId == ConnectorId.OpenAI)
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zio/testOnly llm4zio.core.ConnectorConfigSpec'`
Expected: Compilation failure — types not found.

- [ ] **Step 3: Implement ConnectorConfig ADT**

Create `llm4zio/src/main/scala/llm4zio/core/ConnectorConfig.scala`:

```scala
package llm4zio.core

import zio.*
import zio.json.*

sealed trait ConnectorConfig:
  def connectorId: ConnectorId
  def model: Option[String]
  def timeout: Duration
  def maxRetries: Int

object ConnectorConfig:
  given JsonCodec[ConnectorConfig] = DeriveJsonCodec.gen[ConnectorConfig]

final case class ApiConnectorConfig(
  connectorId: ConnectorId,
  model: Option[String] = None,
  baseUrl: Option[String] = None,
  apiKey: Option[String] = None,
  timeout: Duration = 300.seconds,
  maxRetries: Int = 3,
  requestsPerMinute: Int = 60,
  burstSize: Int = 10,
  acquireTimeout: Duration = 30.seconds,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
) extends ConnectorConfig derives JsonCodec

final case class CliConnectorConfig(
  connectorId: ConnectorId,
  model: Option[String] = None,
  timeout: Duration = 300.seconds,
  maxRetries: Int = 3,
  flags: Map[String, String] = Map.empty,
  sandbox: Option[CliSandbox] = None,
  turnLimit: Option[Int] = None,
  envVars: Map[String, String] = Map.empty,
) extends ConnectorConfig derives JsonCodec

enum CliSandbox derives JsonCodec:
  case Docker(image: String, mount: Boolean = true, network: Option[String] = None)
  case Podman, SeatbeltMacOS, Runsc, Lxc

final case class FallbackChain(connectors: List[ConnectorConfig] = Nil) derives JsonCodec:
  def nonEmpty: Boolean = connectors.nonEmpty
  def isEmpty: Boolean  = connectors.isEmpty

object FallbackChain:
  val empty: FallbackChain = FallbackChain()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zio/testOnly llm4zio.core.ConnectorConfigSpec'`
Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/ConnectorConfig.scala llm4zio/src/test/scala/llm4zio/core/ConnectorConfigSpec.scala
git commit -m "feat: add ConnectorConfig ADT with ApiConnectorConfig, CliConnectorConfig, CliSandbox, FallbackChain"
```

---

### Task 3: Connector, ApiConnector, CliConnector Traits

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/core/Connector.scala`
- Test: `llm4zio/src/test/scala/llm4zio/core/ConnectorSpec.scala`

- [ ] **Step 1: Write test for Connector traits**

Create `llm4zio/src/test/scala/llm4zio/core/ConnectorSpec.scala`:

```scala
package llm4zio.core

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*
import llm4zio.tools.{AnyTool, JsonSchema}

object ConnectorSpec extends ZIOSpecDefault:

  val stubApiConnector: ApiConnector = new ApiConnector:
    def id: ConnectorId = ConnectorId.OpenAI
    def healthCheck: IO[LlmError, HealthStatus] =
      ZIO.succeed(HealthStatus(Availability.Healthy, AuthStatus.Valid, Some(50.millis)))
    def isAvailable: UIO[Boolean] = ZIO.succeed(true)
    def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("not implemented"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.InvalidRequestError("not implemented"))

  val stubCliConnector: CliConnector = new CliConnector:
    def id: ConnectorId = ConnectorId.ClaudeCli
    def healthCheck: IO[LlmError, HealthStatus] =
      ZIO.succeed(HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
    def isAvailable: UIO[Boolean] = ZIO.succeed(true)
    def interactionSupport: InteractionSupport = InteractionSupport.InteractiveStdin
    def buildArgv(prompt: String, ctx: CliContext): List[String] = List("claude", "--print", prompt)
    def buildInteractiveArgv(ctx: CliContext): List[String] = List("claude")
    def complete(prompt: String): IO[LlmError, String] = ZIO.succeed("ok")
    def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] = ZStream.empty

  def spec = suite("Connector")(
    test("ApiConnector has Api kind") {
      assertTrue(stubApiConnector.kind == ConnectorKind.Api)
    },
    test("CliConnector has Cli kind") {
      assertTrue(stubCliConnector.kind == ConnectorKind.Cli)
    },
    test("ApiConnector healthCheck returns HealthStatus") {
      for status <- stubApiConnector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
    test("CliConnector buildArgv produces correct list") {
      val argv = stubCliConnector.buildArgv("hello", CliContext("/work", "/repo"))
      assertTrue(argv == List("claude", "--print", "hello"))
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zio/testOnly llm4zio.core.ConnectorSpec'`
Expected: Compilation failure — `Connector`, `ApiConnector`, `CliConnector` not found.

- [ ] **Step 3: Implement Connector traits**

Create `llm4zio/src/main/scala/llm4zio/core/Connector.scala`:

```scala
package llm4zio.core

import zio.*
import zio.json.*
import zio.stream.ZStream

trait Connector:
  def id: ConnectorId
  def kind: ConnectorKind
  def healthCheck: IO[LlmError, HealthStatus]
  def isAvailable: UIO[Boolean]

trait ApiConnector extends Connector, LlmService:
  final def kind: ConnectorKind = ConnectorKind.Api

trait CliConnector extends Connector:
  final def kind: ConnectorKind = ConnectorKind.Cli
  def interactionSupport: InteractionSupport
  def buildArgv(prompt: String, ctx: CliContext): List[String]
  def buildInteractiveArgv(ctx: CliContext): List[String]
  def complete(prompt: String): IO[LlmError, String]
  def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk]

enum Availability derives JsonCodec:
  case Healthy, Degraded, Unhealthy, Unknown

enum AuthStatus derives JsonCodec:
  case Valid, Missing, Invalid, Unknown

final case class HealthStatus(
  availability: Availability,
  authStatus: AuthStatus,
  latency: Option[Duration],
) derives JsonCodec

enum InteractionSupport derives JsonCodec:
  case InteractiveStdin, ContinuationOnly

final case class CliContext(
  worktreePath: String,
  repoPath: String,
  envVars: Map[String, String] = Map.empty,
  sandbox: Option[CliSandbox] = None,
  turnLimit: Option[Int] = None,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zio/testOnly llm4zio.core.ConnectorSpec'`
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/Connector.scala llm4zio/src/test/scala/llm4zio/core/ConnectorSpec.scala
git commit -m "feat: add Connector, ApiConnector, CliConnector traits with HealthStatus, CliContext"
```

---

### Task 4: CliProcessExecutor Trait

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/core/CliProcessExecutor.scala`
- Test: `llm4zio/src/test/scala/llm4zio/core/CliProcessExecutorSpec.scala`

- [ ] **Step 1: Write test for CliProcessExecutor**

Create `llm4zio/src/test/scala/llm4zio/core/CliProcessExecutorSpec.scala`:

```scala
package llm4zio.core

import zio.*
import zio.stream.ZStream
import zio.test.*

object CliProcessExecutorSpec extends ZIOSpecDefault:

  class MockCliProcessExecutor(
    responses: Map[List[String], ProcessResult] = Map.empty,
    streamResponses: Map[List[String], List[String]] = Map.empty,
  ) extends CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      ZIO.fromOption(responses.get(argv))
        .orElseFail(LlmError.ProviderError(s"No mock response for argv: ${argv.mkString(" ")}"))
    override def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.fromIterable(streamResponses.getOrElse(argv, Nil))

  def spec = suite("CliProcessExecutor")(
    test("MockCliProcessExecutor returns canned response") {
      val mock = MockCliProcessExecutor(
        responses = Map(List("echo", "hello") -> ProcessResult(List("hello"), 0))
      )
      for result <- mock.run(List("echo", "hello"), "/tmp", Map.empty)
      yield assertTrue(
        result.stdout == List("hello"),
        result.exitCode == 0,
      )
    },
    test("MockCliProcessExecutor fails for unknown argv") {
      val mock = MockCliProcessExecutor()
      for result <- mock.run(List("unknown"), "/tmp", Map.empty).exit
      yield assertTrue(result.isFailure)
    },
    test("MockCliProcessExecutor streams lines") {
      val mock = MockCliProcessExecutor(
        streamResponses = Map(List("tail", "-f") -> List("line1", "line2", "line3"))
      )
      for lines <- mock.runStreaming(List("tail", "-f"), "/tmp", Map.empty).runCollect
      yield assertTrue(lines.toList == List("line1", "line2", "line3"))
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zio/testOnly llm4zio.core.CliProcessExecutorSpec'`
Expected: Compilation failure — `CliProcessExecutor`, `ProcessResult` not found.

- [ ] **Step 3: Implement CliProcessExecutor**

Create `llm4zio/src/main/scala/llm4zio/core/CliProcessExecutor.scala`:

```scala
package llm4zio.core

import zio.*
import zio.stream.ZStream

trait CliProcessExecutor:
  def run(argv: List[String], cwd: String, envVars: Map[String, String] = Map.empty): IO[LlmError, ProcessResult]
  def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String] = Map.empty): ZStream[Any, LlmError, String]

final case class ProcessResult(stdout: List[String], exitCode: Int)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zio/testOnly llm4zio.core.CliProcessExecutorSpec'`
Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/CliProcessExecutor.scala llm4zio/src/test/scala/llm4zio/core/CliProcessExecutorSpec.scala
git commit -m "feat: add CliProcessExecutor trait and ProcessResult with MockCliProcessExecutor tests"
```

---

### Task 5: ConnectorRegistry and ConnectorFactory Traits

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/core/ConnectorRegistry.scala`
- Test: `llm4zio/src/test/scala/llm4zio/core/ConnectorRegistrySpec.scala`

- [ ] **Step 1: Write test for ConnectorRegistry**

Create `llm4zio/src/test/scala/llm4zio/core/ConnectorRegistrySpec.scala`:

```scala
package llm4zio.core

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*
import llm4zio.tools.{AnyTool, JsonSchema}

object ConnectorRegistrySpec extends ZIOSpecDefault:

  val mockApiFactory: ConnectorFactory = new ConnectorFactory:
    def connectorId: ConnectorId = ConnectorId.Mock
    def kind: ConnectorKind = ConnectorKind.Api
    def create(config: ConnectorConfig): IO[LlmError, Connector] =
      ZIO.succeed(new ApiConnector:
        def id: ConnectorId = ConnectorId.Mock
        def healthCheck: IO[LlmError, HealthStatus] =
          ZIO.succeed(HealthStatus(Availability.Healthy, AuthStatus.Valid, Some(1.millis)))
        def isAvailable: UIO[Boolean] = ZIO.succeed(true)
        def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] = ZStream.empty
        def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] = ZStream.empty
        def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.fail(LlmError.InvalidRequestError("mock"))
        def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          ZIO.fail(LlmError.InvalidRequestError("mock"))
      )

  def spec = suite("ConnectorRegistry")(
    test("resolve returns connector for known id") {
      val registry = ConnectorRegistryLive(Map(ConnectorId.Mock -> mockApiFactory))
      val config = ApiConnectorConfig(ConnectorId.Mock, Some("mock-model"))
      for connector <- registry.resolve(config)
      yield assertTrue(connector.id == ConnectorId.Mock)
    },
    test("resolve fails for unknown id") {
      val registry = ConnectorRegistryLive(Map.empty)
      val config = ApiConnectorConfig(ConnectorId("unknown"), Some("model"))
      for result <- registry.resolve(config).exit
      yield assertTrue(result match
        case Exit.Failure(cause) => cause.failureOption.exists(_.isInstanceOf[LlmError.ConfigError])
        case _                   => false
      )
    },
    test("available returns registered ids") {
      val registry = ConnectorRegistryLive(Map(ConnectorId.Mock -> mockApiFactory))
      for ids <- registry.available
      yield assertTrue(ids == List(ConnectorId.Mock))
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zio/testOnly llm4zio.core.ConnectorRegistrySpec'`
Expected: Compilation failure — `ConnectorRegistry`, `ConnectorFactory`, `ConnectorRegistryLive` not found.

- [ ] **Step 3: Implement ConnectorRegistry**

Create `llm4zio/src/main/scala/llm4zio/core/ConnectorRegistry.scala`:

```scala
package llm4zio.core

import zio.*

trait ConnectorFactory:
  def connectorId: ConnectorId
  def kind: ConnectorKind
  def create(config: ConnectorConfig): IO[LlmError, Connector]

trait ConnectorRegistry:
  def resolve(config: ConnectorConfig): IO[LlmError, Connector]
  def resolveApi(config: ApiConnectorConfig): IO[LlmError, ApiConnector]
  def resolveCli(config: CliConnectorConfig): IO[LlmError, CliConnector]
  def available: UIO[List[ConnectorId]]
  def healthCheckAll: IO[LlmError, Map[ConnectorId, HealthStatus]]

final case class ConnectorRegistryLive(factories: Map[ConnectorId, ConnectorFactory]) extends ConnectorRegistry:

  override def resolve(config: ConnectorConfig): IO[LlmError, Connector] =
    ZIO.fromOption(factories.get(config.connectorId))
      .orElseFail(LlmError.ConfigError(s"Unknown connector: ${config.connectorId.value}"))
      .flatMap(_.create(config))

  override def resolveApi(config: ApiConnectorConfig): IO[LlmError, ApiConnector] =
    resolve(config).flatMap {
      case api: ApiConnector => ZIO.succeed(api)
      case other             => ZIO.fail(LlmError.ConfigError(s"Expected ApiConnector, got ${other.kind}"))
    }

  override def resolveCli(config: CliConnectorConfig): IO[LlmError, CliConnector] =
    resolve(config).flatMap {
      case cli: CliConnector => ZIO.succeed(cli)
      case other             => ZIO.fail(LlmError.ConfigError(s"Expected CliConnector, got ${other.kind}"))
    }

  override def available: UIO[List[ConnectorId]] =
    ZIO.succeed(factories.keys.toList)

  override def healthCheckAll: IO[LlmError, Map[ConnectorId, HealthStatus]] =
    ZIO.foreach(factories.toList) { case (id, factory) =>
      val defaultConfig: ConnectorConfig =
        if factory.kind == ConnectorKind.Api then ApiConnectorConfig(id)
        else CliConnectorConfig(id)
      factory.create(defaultConfig)
        .flatMap(_.healthCheck)
        .map(status => id -> status)
        .catchAll(err => ZIO.succeed(id -> HealthStatus(Availability.Unknown, AuthStatus.Unknown, None)))
    }.map(_.toMap)

object ConnectorRegistry:
  def resolve(config: ConnectorConfig): ZIO[ConnectorRegistry, LlmError, Connector] =
    ZIO.serviceWithZIO[ConnectorRegistry](_.resolve(config))
  def resolveApi(config: ApiConnectorConfig): ZIO[ConnectorRegistry, LlmError, ApiConnector] =
    ZIO.serviceWithZIO[ConnectorRegistry](_.resolveApi(config))
  def resolveCli(config: CliConnectorConfig): ZIO[ConnectorRegistry, LlmError, CliConnector] =
    ZIO.serviceWithZIO[ConnectorRegistry](_.resolveCli(config))
  def available: ZIO[ConnectorRegistry, Nothing, List[ConnectorId]] =
    ZIO.serviceWithZIO[ConnectorRegistry](_.available)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zio/testOnly llm4zio.core.ConnectorRegistrySpec'`
Expected: All 3 tests PASS.

- [ ] **Step 5: Run all library tests**

Run: `sbt llm4zio/test`
Expected: All existing tests still PASS alongside new tests.

- [ ] **Step 6: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/ConnectorRegistry.scala llm4zio/src/test/scala/llm4zio/core/ConnectorRegistrySpec.scala
git commit -m "feat: add ConnectorRegistry, ConnectorFactory, ConnectorRegistryLive with resolution and health checks"
```

---

## Phase 2: Migrate Existing Providers to ApiConnector

### Task 6: Migrate OpenAIProvider to ApiConnector

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/OpenAIProvider.scala`
- Modify: `llm4zio/src/test/scala/llm4zio/providers/OpenAIProviderSpec.scala`

- [ ] **Step 1: Add healthCheck test to OpenAIProviderSpec**

Add to the test suite in `OpenAIProviderSpec.scala`:

```scala
test("healthCheck returns Healthy on success") {
  val httpClient = new MockHttpClient(shouldSucceed = true)
  val config = LlmConfig(LlmProvider.OpenAI, "gpt-4o", Some("https://api.openai.com/v1"), Some("sk-test"))
  val provider = OpenAIProvider.make(config, httpClient)
  for status <- provider.asInstanceOf[ApiConnector].healthCheck
  yield assertTrue(status.availability == Availability.Healthy)
},
test("healthCheck returns Unhealthy on auth failure") {
  val httpClient = new MockHttpClient(shouldSucceed = false)
  val config = LlmConfig(LlmProvider.OpenAI, "gpt-4o", Some("https://api.openai.com/v1"), Some("bad-key"))
  val provider = OpenAIProvider.make(config, httpClient)
  for status <- provider.asInstanceOf[ApiConnector].healthCheck
  yield assertTrue(status.availability == Availability.Unhealthy)
},
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zio/testOnly llm4zio.providers.OpenAIProviderSpec'`
Expected: Compilation failure or ClassCastException — provider doesn't extend `ApiConnector` yet.

- [ ] **Step 3: Modify OpenAIProvider.make to return ApiConnector**

In `OpenAIProvider.scala`, change the `make` method return type and add `id` + `healthCheck`:

```scala
object OpenAIProvider:
  def make(config: LlmConfig, httpClient: HttpClient): ApiConnector =
    new ApiConnector:
      override def id: ConnectorId = ConnectorId.OpenAI
      override def healthCheck: IO[LlmError, HealthStatus] =
        val start = java.lang.System.nanoTime()
        isAvailable.map { available =>
          val latency = Duration.fromNanos(java.lang.System.nanoTime() - start)
          if available then HealthStatus(Availability.Healthy, AuthStatus.Valid, Some(latency))
          else HealthStatus(Availability.Unhealthy, AuthStatus.Invalid, Some(latency))
        }
      // ... rest of existing LlmService methods unchanged
```

Since `ApiConnector extends Connector, LlmService`, all existing `LlmService` methods remain valid. The `kind` is provided by `ApiConnector` as `final def kind = ConnectorKind.Api`.

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zio/testOnly llm4zio.providers.OpenAIProviderSpec'`
Expected: All tests PASS including new healthCheck tests.

- [ ] **Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/OpenAIProvider.scala llm4zio/src/test/scala/llm4zio/providers/OpenAIProviderSpec.scala
git commit -m "feat: migrate OpenAIProvider to ApiConnector with healthCheck"
```

---

### Task 7: Migrate AnthropicProvider to ApiConnector

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/AnthropicProvider.scala`
- Modify: `llm4zio/src/test/scala/llm4zio/providers/AnthropicProviderSpec.scala`

Follow the same pattern as Task 6:

- [ ] **Step 1: Add healthCheck test to AnthropicProviderSpec**

Same pattern as OpenAI: test Healthy on success, Unhealthy on auth failure.

- [ ] **Step 2: Modify AnthropicProvider.make to return ApiConnector**

Change return type to `ApiConnector`, add `id = ConnectorId.Anthropic` and `healthCheck` using `isAvailable`.

- [ ] **Step 3: Run tests**

Run: `sbt 'llm4zio/testOnly llm4zio.providers.AnthropicProviderSpec'`
Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/AnthropicProvider.scala llm4zio/src/test/scala/llm4zio/providers/AnthropicProviderSpec.scala
git commit -m "feat: migrate AnthropicProvider to ApiConnector with healthCheck"
```

---

### Task 8: Migrate remaining API providers (GeminiApi, LmStudio, Ollama, OpenCode, Mock)

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiApiProvider.scala`
- Modify: `llm4zio/src/main/scala/llm4zio/providers/LmStudioProvider.scala`
- Modify: `llm4zio/src/main/scala/llm4zio/providers/OllamaProvider.scala`
- Modify: `llm4zio/src/main/scala/llm4zio/providers/OpenCodeProvider.scala`
- Modify: `llm4zio/src/main/scala/llm4zio/providers/MockProvider.scala`
- Modify: corresponding test files

Apply the same pattern to each:
- Return type → `ApiConnector` (or `Connector` for Mock)
- Add `id: ConnectorId` using the corresponding `ConnectorId.*` constant
- Add `healthCheck` using `isAvailable` + latency measurement
- Mock uses `ConnectorId.Mock` and always returns `Healthy`

- [ ] **Step 1: Migrate GeminiApiProvider**

`id = ConnectorId.GeminiApi`, same healthCheck pattern.

- [ ] **Step 2: Migrate LmStudioProvider**

`id = ConnectorId.LmStudio`, same healthCheck pattern.

- [ ] **Step 3: Migrate OllamaProvider**

`id = ConnectorId.Ollama`, same healthCheck pattern.

- [ ] **Step 4: Migrate OpenCodeProvider**

`id = ConnectorId.OpenCode`, same healthCheck pattern. Note: OpenCode HTTP API stays as `ApiConnector`. The CLI connector for `opencode run` will be a separate `CliConnector` in Phase 3.

- [ ] **Step 5: Migrate MockProvider**

`id = ConnectorId.Mock`, healthCheck always returns `HealthStatus(Availability.Healthy, AuthStatus.Valid, Some(0.millis))`.

- [ ] **Step 6: Run all library tests**

Run: `sbt llm4zio/test`
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/ llm4zio/src/test/scala/llm4zio/providers/
git commit -m "feat: migrate GeminiApi, LmStudio, Ollama, OpenCode, Mock providers to ApiConnector"
```

---

### Task 9: Update LlmService.fromConfig to use ConnectorRegistry

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/core/LlmService.scala`

The `LlmService.fromConfig` ZLayer currently pattern-matches on `LlmProvider`. Update it to delegate to provider factories while maintaining backward compatibility.

- [ ] **Step 1: Update fromConfig to return Connector**

In `LlmService.scala`, update the `fromConfig` layer (lines 51-88) to build providers using the same factory pattern but keeping the `LlmConfig`-based API for backward compat:

```scala
val fromConfig: ZLayer[LlmConfig & HttpClient & GeminiCliExecutor, Nothing, LlmService] =
  ZLayer.fromFunction { (config: LlmConfig, http: HttpClient, cliExec: GeminiCliExecutor) =>
    val service: LlmService = config.provider match
      case LlmProvider.GeminiCli => GeminiCliProvider.make(config, cliExec)
      case LlmProvider.GeminiApi => GeminiApiProvider.make(config, http)
      case LlmProvider.OpenAI    => OpenAIProvider.make(config, http)
      case LlmProvider.Anthropic => AnthropicProvider.make(config, http)
      case LlmProvider.LmStudio  => LmStudioProvider.make(config, http)
      case LlmProvider.Ollama    => OllamaProvider.make(config, http)
      case LlmProvider.OpenCode  => OpenCodeProvider.make(config, http)
      case LlmProvider.Mock      => MockProvider.make(config)
    service
  }
```

This stays unchanged for now — the pattern match still works because `ApiConnector extends LlmService`. The gateway will migrate to `ConnectorRegistry` in Phase 5.

- [ ] **Step 2: Compile library**

Run: `sbt llm4zio/compile`
Expected: SUCCESS — `ApiConnector` is a subtype of `LlmService`, so return type still matches.

- [ ] **Step 3: Run all library tests**

Run: `sbt llm4zio/test`
Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/LlmService.scala
git commit -m "refactor: verify LlmService.fromConfig compatibility with ApiConnector return types"
```

---

## Phase 3: Create CLI Connectors

### Task 10: GeminiCliConnector

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`
- Modify: `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

The existing `GeminiCliProvider` already implements prompt completion via `GeminiCliExecutor`. It needs to become a `CliConnector` and gain `buildArgv` (moved from gateway's `CliAgentRunner.buildArgvForHost` gemini case).

- [ ] **Step 1: Add CliConnector tests to GeminiCliProviderSpec**

Add to the existing spec:

```scala
test("implements CliConnector with correct id") {
  val executor = new MockGeminiCliExecutor()
  val config = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
  val connector = GeminiCliProvider.make(config, executor)
  assertTrue(
    connector.isInstanceOf[CliConnector],
    connector.asInstanceOf[CliConnector].id == ConnectorId.GeminiCli,
    connector.asInstanceOf[CliConnector].kind == ConnectorKind.Cli,
  )
},
test("buildArgv produces gemini CLI flags") {
  val executor = new MockGeminiCliExecutor()
  val config = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
  val connector = GeminiCliProvider.make(config, executor).asInstanceOf[CliConnector]
  val ctx = CliContext(worktreePath = "/workspace", repoPath = "/repo")
  val argv = connector.buildArgv("fix the bug", ctx)
  assertTrue(
    argv.contains("gemini"),
    argv.contains("--yolo"),
    argv.contains("-p"),
    argv.contains("fix the bug"),
  )
},
test("interactionSupport is InteractiveStdin") {
  val executor = new MockGeminiCliExecutor()
  val config = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
  val connector = GeminiCliProvider.make(config, executor).asInstanceOf[CliConnector]
  assertTrue(connector.interactionSupport == InteractionSupport.InteractiveStdin)
},
```

- [ ] **Step 2: Modify GeminiCliProvider.make to return CliConnector**

Change `GeminiCliProvider.make` return type from `LlmService` to `CliConnector`. Add the `CliConnector` methods:

```scala
def make(
  config: LlmConfig,
  executor: GeminiCliExecutor,
  executionContext: GeminiCliExecutionContext = GeminiCliExecutionContext.default,
): CliConnector =
  new CliConnector:
    override def id: ConnectorId = ConnectorId.GeminiCli
    override def interactionSupport: InteractionSupport = InteractionSupport.InteractiveStdin

    override def healthCheck: IO[LlmError, HealthStatus] =
      executor.checkGeminiInstalled.as(
        HealthStatus(Availability.Healthy, AuthStatus.Valid, None)
      ).catchAll(_ =>
        ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None))
      )

    override def buildArgv(prompt: String, ctx: CliContext): List[String] =
      val base = List("gemini", "--yolo")
      val dirs = if ctx.repoPath.nonEmpty then List("--include-directories", ctx.repoPath) else Nil
      val turn = ctx.turnLimit.map(l => List("--turn-limit", l.toString)).getOrElse(Nil)
      base ++ dirs ++ turn ++ List("-p", prompt)

    override def buildInteractiveArgv(ctx: CliContext): List[String] =
      val base = List("gemini", "--yolo")
      val dirs = if ctx.repoPath.nonEmpty then List("--include-directories", ctx.repoPath) else Nil
      base ++ dirs

    override def complete(prompt: String): IO[LlmError, String] =
      executor.runGeminiProcess(prompt, config, executionContext)
        .flatMap(output => ZIO.fromEither(extractResponse(output))
          .mapError(msg => LlmError.ParseError(msg, output)))

    override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
      executeStream(prompt)  // reuse existing streaming implementation

    // ... existing LlmService methods (executeStream, executeStreamWithHistory, etc.) unchanged
```

Note: `CliConnector` does not extend `LlmService`, so the existing `executeStream` etc. become private helpers used by `completeStream`. The `complete` method wraps the existing process execution.

- [ ] **Step 3: Run tests**

Run: `sbt 'llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec'`
Expected: All tests PASS including new CliConnector tests.

- [ ] **Step 4: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat: migrate GeminiCliProvider to CliConnector with buildArgv and healthCheck"
```

---

### Task 11: ClaudeCliConnector

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/providers/ClaudeCliConnector.scala`
- Create: `llm4zio/src/test/scala/llm4zio/providers/ClaudeCliConnectorSpec.scala`

- [ ] **Step 1: Write tests**

Create `llm4zio/src/test/scala/llm4zio/providers/ClaudeCliConnectorSpec.scala`:

```scala
package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*
import llm4zio.core.*

object ClaudeCliConnectorSpec extends ZIOSpecDefault:

  class MockCliExec(
    responses: Map[List[String], ProcessResult] = Map.empty,
  ) extends CliProcessExecutor:
    var lastArgv: List[String] = Nil
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      lastArgv = argv
      ZIO.fromOption(responses.get(argv))
        .orElse(ZIO.succeed(ProcessResult(List("mocked response"), 0)))
    override def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.fromIterable(responses.get(argv).map(_.stdout).getOrElse(List("mocked")))

  def spec = suite("ClaudeCliConnector")(
    test("id is claude-cli") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      assertTrue(connector.id == ConnectorId.ClaudeCli)
    },
    test("kind is Cli") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      assertTrue(connector.kind == ConnectorKind.Cli)
    },
    test("interactionSupport is InteractiveStdin") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      assertTrue(connector.interactionSupport == InteractionSupport.InteractiveStdin)
    },
    test("buildArgv produces claude --print prompt") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      val ctx = CliContext("/workspace", "/repo")
      val argv = connector.buildArgv("fix the bug", ctx)
      assertTrue(argv == List("claude", "--print", "fix the bug"))
    },
    test("buildInteractiveArgv produces claude without --print") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      val ctx = CliContext("/workspace", "/repo")
      val argv = connector.buildInteractiveArgv(ctx)
      assertTrue(argv == List("claude"))
    },
    test("complete returns stdout joined") {
      val mock = new MockCliExec()
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), mock)
      for result <- connector.complete("hello")
      yield assertTrue(result == "mocked response")
    },
    test("healthCheck returns Healthy when claude is installed") {
      val mock = new MockCliExec(responses = Map(
        List("claude", "--version") -> ProcessResult(List("claude 1.0.0"), 0)
      ))
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), mock)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zio/testOnly llm4zio.providers.ClaudeCliConnectorSpec'`
Expected: Compilation failure — `ClaudeCliConnector` not found.

- [ ] **Step 3: Implement ClaudeCliConnector**

Create `llm4zio/src/main/scala/llm4zio/providers/ClaudeCliConnector.scala`:

```scala
package llm4zio.providers

import zio.*
import zio.stream.ZStream
import llm4zio.core.*

object ClaudeCliConnector:

  def make(config: CliConnectorConfig, executor: CliProcessExecutor): CliConnector =
    new CliConnector:
      override def id: ConnectorId = ConnectorId.ClaudeCli

      override def interactionSupport: InteractionSupport = InteractionSupport.InteractiveStdin

      override def healthCheck: IO[LlmError, HealthStatus] =
        executor.run(List("claude", "--version"), ".", Map.empty)
          .map(_ => HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
          .catchAll(_ => ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)))

      override def isAvailable: UIO[Boolean] =
        healthCheck.map(_.availability == Availability.Healthy).catchAll(_ => ZIO.succeed(false))

      override def buildArgv(prompt: String, ctx: CliContext): List[String] =
        List("claude", "--print", prompt)

      override def buildInteractiveArgv(ctx: CliContext): List[String] =
        List("claude")

      override def complete(prompt: String): IO[LlmError, String] =
        executor.run(buildArgv(prompt, CliContext(".", "")), ".", config.envVars)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else ZIO.fail(LlmError.ProviderError(s"claude exited with code ${result.exitCode}: ${result.stdout.mkString("\n")}"))
          }

      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(complete(prompt)).map(text => LlmChunk(delta = text))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zio/testOnly llm4zio.providers.ClaudeCliConnectorSpec'`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/ClaudeCliConnector.scala llm4zio/src/test/scala/llm4zio/providers/ClaudeCliConnectorSpec.scala
git commit -m "feat: add ClaudeCliConnector implementing CliConnector with buildArgv and healthCheck"
```

---

### Task 12: OpenCodeCliConnector, CodexConnector, CopilotConnector

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/providers/OpenCodeCliConnector.scala`
- Create: `llm4zio/src/main/scala/llm4zio/providers/CodexConnector.scala`
- Create: `llm4zio/src/main/scala/llm4zio/providers/CopilotConnector.scala`
- Create: corresponding test files

Follow the same pattern as Task 11 for each. Key differences per tool:

**OpenCodeCliConnector:**
- `id = ConnectorId.OpenCode`
- `interactionSupport = InteractionSupport.ContinuationOnly`
- `buildArgv(prompt, ctx) = List("opencode", "run", "--prompt", prompt)`
- `buildInteractiveArgv(ctx) = List("opencode")`
- `healthCheck` runs `List("opencode", "--version")`

**CodexConnector:**
- `id = ConnectorId.Codex`
- `interactionSupport = InteractionSupport.InteractiveStdin`
- `buildArgv(prompt, ctx) = List("codex", prompt)`
- `buildInteractiveArgv(ctx) = List("codex")`
- `healthCheck` runs `List("codex", "--version")`

**CopilotConnector:**
- `id = ConnectorId.Copilot`
- `interactionSupport = InteractionSupport.ContinuationOnly`
- `buildArgv(prompt, ctx) = List("gh", "copilot", "suggest", "-t", "shell", prompt)`
- `buildInteractiveArgv(ctx) = List("gh", "copilot")`
- `healthCheck` runs `List("gh", "copilot", "--version")`

- [ ] **Step 1: Create OpenCodeCliConnector + test**
- [ ] **Step 2: Create CodexConnector + test**
- [ ] **Step 3: Create CopilotConnector + test**
- [ ] **Step 4: Run all library tests**

Run: `sbt llm4zio/test`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/OpenCodeCliConnector.scala llm4zio/src/main/scala/llm4zio/providers/CodexConnector.scala llm4zio/src/main/scala/llm4zio/providers/CopilotConnector.scala llm4zio/src/test/scala/llm4zio/providers/
git commit -m "feat: add OpenCodeCli, Codex, Copilot CLI connectors implementing CliConnector"
```

---

### Task 13: ConnectorFactory Implementations and Wire Registry

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/providers/ConnectorFactories.scala`
- Modify: `llm4zio/src/main/scala/llm4zio/core/ConnectorRegistry.scala` (add live ZLayer)
- Modify: `llm4zio/src/test/scala/llm4zio/core/ConnectorRegistrySpec.scala` (add integration test)

- [ ] **Step 1: Write integration test for full registry**

Add to `ConnectorRegistrySpec.scala`:

```scala
test("live registry resolves all known API connector ids") {
  val registry = ConnectorFactories.createRegistry(new MockHttpClient(true), mockCliExec)
  for ids <- registry.available
  yield assertTrue(
    ids.contains(ConnectorId.OpenAI),
    ids.contains(ConnectorId.Anthropic),
    ids.contains(ConnectorId.GeminiApi),
    ids.contains(ConnectorId.ClaudeCli),
    ids.contains(ConnectorId.GeminiCli),
    ids.contains(ConnectorId.Mock),
  )
},
```

(Where `MockHttpClient` is imported from the existing test infrastructure and `mockCliExec` is a `MockCliProcessExecutor`.)

- [ ] **Step 2: Implement ConnectorFactories**

Create `llm4zio/src/main/scala/llm4zio/providers/ConnectorFactories.scala`:

```scala
package llm4zio.providers

import zio.*
import llm4zio.core.*

object ConnectorFactories:

  def createRegistry(http: HttpClient, cli: CliProcessExecutor): ConnectorRegistry =
    ConnectorRegistryLive(Map(
      ConnectorId.OpenAI    -> apiFactory(ConnectorId.OpenAI, cfg => OpenAIProvider.make(cfg, http)),
      ConnectorId.Anthropic -> apiFactory(ConnectorId.Anthropic, cfg => AnthropicProvider.make(cfg, http)),
      ConnectorId.GeminiApi -> apiFactory(ConnectorId.GeminiApi, cfg => GeminiApiProvider.make(cfg, http)),
      ConnectorId.LmStudio  -> apiFactory(ConnectorId.LmStudio, cfg => LmStudioProvider.make(cfg, http)),
      ConnectorId.Ollama    -> apiFactory(ConnectorId.Ollama, cfg => OllamaProvider.make(cfg, http)),
      ConnectorId.OpenCode  -> cliFactory(ConnectorId.OpenCode, cfg => OpenCodeCliConnector.make(cfg, cli)),
      ConnectorId.ClaudeCli -> cliFactory(ConnectorId.ClaudeCli, cfg => ClaudeCliConnector.make(cfg, cli)),
      ConnectorId.GeminiCli -> geminiCliFactory(cli),
      ConnectorId.Codex     -> cliFactory(ConnectorId.Codex, cfg => CodexConnector.make(cfg, cli)),
      ConnectorId.Copilot   -> cliFactory(ConnectorId.Copilot, cfg => CopilotConnector.make(cfg, cli)),
      ConnectorId.Mock      -> apiFactory(ConnectorId.Mock, cfg => MockProvider.make(cfg)),
    ))

  val live: ZLayer[HttpClient & CliProcessExecutor, Nothing, ConnectorRegistry] =
    ZLayer.fromFunction(createRegistry)

  private def apiFactory(id: ConnectorId, build: LlmConfig => ApiConnector): ConnectorFactory =
    new ConnectorFactory:
      def connectorId: ConnectorId = id
      def kind: ConnectorKind = ConnectorKind.Api
      def create(config: ConnectorConfig): IO[LlmError, Connector] = config match
        case api: ApiConnectorConfig => ZIO.succeed(build(api.toLlmConfig))
        case _                       => ZIO.fail(LlmError.ConfigError(s"Expected ApiConnectorConfig for $id"))

  private def cliFactory(id: ConnectorId, build: CliConnectorConfig => CliConnector): ConnectorFactory =
    new ConnectorFactory:
      def connectorId: ConnectorId = id
      def kind: ConnectorKind = ConnectorKind.Cli
      def create(config: ConnectorConfig): IO[LlmError, Connector] = config match
        case cli: CliConnectorConfig => ZIO.succeed(build(cli))
        case _                       => ZIO.fail(LlmError.ConfigError(s"Expected CliConnectorConfig for $id"))

  private def geminiCliFactory(cli: CliProcessExecutor): ConnectorFactory =
    new ConnectorFactory:
      def connectorId: ConnectorId = ConnectorId.GeminiCli
      def kind: ConnectorKind = ConnectorKind.Cli
      def create(config: ConnectorConfig): IO[LlmError, Connector] = config match
        case cli: CliConnectorConfig =>
          val llmConfig = LlmConfig(LlmProvider.GeminiCli, cli.model.getOrElse("gemini-2.5-flash"))
          ZIO.succeed(GeminiCliProvider.make(llmConfig, GeminiCliExecutor.default))
        case _ => ZIO.fail(LlmError.ConfigError("Expected CliConnectorConfig for gemini-cli"))
```

- [ ] **Step 3: Add `toLlmConfig` method to ApiConnectorConfig**

In `ConnectorConfig.scala`, add to `ApiConnectorConfig`:

```scala
  def toLlmConfig: LlmConfig = LlmConfig(
    provider = connectorId match
      case ConnectorId.OpenAI    => LlmProvider.OpenAI
      case ConnectorId.Anthropic => LlmProvider.Anthropic
      case ConnectorId.GeminiApi => LlmProvider.GeminiApi
      case ConnectorId.LmStudio  => LlmProvider.LmStudio
      case ConnectorId.Ollama    => LlmProvider.Ollama
      case ConnectorId.Mock      => LlmProvider.Mock
      case _                     => LlmProvider.OpenAI,
    model = model.getOrElse(""),
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
```

This bridge allows `ConnectorFactories` to create providers from the new config ADT while existing providers still accept `LlmConfig`.

- [ ] **Step 4: Run tests**

Run: `sbt llm4zio/test`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/ConnectorFactories.scala llm4zio/src/main/scala/llm4zio/core/ConnectorConfig.scala llm4zio/src/main/scala/llm4zio/core/ConnectorRegistry.scala llm4zio/src/test/scala/llm4zio/core/ConnectorRegistrySpec.scala
git commit -m "feat: add ConnectorFactories wiring all providers into ConnectorRegistry with live ZLayer"
```

---

## Phase 4: Gateway Config Migration

### Task 14: ConnectorConfigResolver

**Files:**
- Create: `src/main/scala/config/control/ConnectorConfigResolver.scala`
- Create: `src/test/scala/config/control/ConnectorConfigResolverSpec.scala`

This replaces `AgentConfigResolver` and `SettingsApplier.toProviderConfig`.

- [ ] **Step 1: Write tests**

Create `src/test/scala/config/control/ConnectorConfigResolverSpec.scala`:

```scala
package config.control

import zio.*
import zio.test.*
import llm4zio.core.*
import config.entity.ConfigRepository
import shared.errors.PersistenceError

object ConnectorConfigResolverSpec extends ZIOSpecDefault:

  class StubConfigRepo(settings: Map[String, String]) extends ConfigRepository:
    def getAllSettings: IO[PersistenceError, Map[String, String]] = ZIO.succeed(settings)
    def getSetting(key: String): IO[PersistenceError, Option[String]] = ZIO.succeed(settings.get(key))
    def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] = ZIO.unit
    def getSettingsByPrefix(prefix: String): IO[PersistenceError, Map[String, String]] =
      ZIO.succeed(settings.filter(_._1.startsWith(prefix)))
    // ... other ConfigRepository methods as no-ops

  def spec = suite("ConnectorConfigResolver")(
    test("resolves global API connector config") {
      val repo = StubConfigRepo(Map(
        "connector.default.id" -> "openai",
        "connector.default.model" -> "gpt-4o",
        "connector.default.apiKey" -> "sk-test",
        "connector.default.timeout" -> "600",
      ))
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = None)
      yield assertTrue(
        config.connectorId == ConnectorId.OpenAI,
        config.model == Some("gpt-4o"),
        config.timeout == 600.seconds,
        config.isInstanceOf[ApiConnectorConfig],
        config.asInstanceOf[ApiConnectorConfig].apiKey == Some("sk-test"),
      )
    },
    test("agent override merges with global defaults") {
      val repo = StubConfigRepo(Map(
        "connector.default.id" -> "openai",
        "connector.default.model" -> "gpt-4o",
        "connector.default.apiKey" -> "sk-test",
        "agent.coder.connector.id" -> "claude-cli",
        "agent.coder.connector.model" -> "claude-sonnet-4",
      ))
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = Some("coder"))
      yield assertTrue(
        config.connectorId == ConnectorId.ClaudeCli,
        config.model == Some("claude-sonnet-4"),
        config.isInstanceOf[CliConnectorConfig],
      )
    },
    test("falls back to library defaults when no settings") {
      val repo = StubConfigRepo(Map.empty)
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = None)
      yield assertTrue(
        config.connectorId == ConnectorId.GeminiCli,
        config.isInstanceOf[CliConnectorConfig],
      )
    },
    test("reads legacy ai.* keys as fallback") {
      val repo = StubConfigRepo(Map(
        "ai.provider" -> "Anthropic",
        "ai.model" -> "claude-sonnet-4",
        "ai.apiKey" -> "sk-legacy",
      ))
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = None)
      yield assertTrue(
        config.connectorId == ConnectorId.Anthropic,
        config.model == Some("claude-sonnet-4"),
      )
    },
  )
```

- [ ] **Step 2: Implement ConnectorConfigResolver**

Create `src/main/scala/config/control/ConnectorConfigResolver.scala`:

```scala
package config.control

import zio.*
import llm4zio.core.*
import config.entity.ConfigRepository
import shared.errors.PersistenceError

trait ConnectorConfigResolver:
  def resolve(agentName: Option[String]): IO[PersistenceError, ConnectorConfig]

object ConnectorConfigResolver:
  def resolve(agentName: Option[String]): ZIO[ConnectorConfigResolver, PersistenceError, ConnectorConfig] =
    ZIO.serviceWithZIO[ConnectorConfigResolver](_.resolve(agentName))
  val live: ZLayer[ConfigRepository, Nothing, ConnectorConfigResolver] =
    ZLayer.fromFunction(ConnectorConfigResolverLive.apply)

final case class ConnectorConfigResolverLive(repo: ConfigRepository) extends ConnectorConfigResolver:

  override def resolve(agentName: Option[String]): IO[PersistenceError, ConnectorConfig] =
    for
      agentSettings  <- agentName.fold(ZIO.succeed(Map.empty[String, String]))(name =>
                          repo.getSettingsByPrefix(s"agent.$name.connector."))
      globalSettings <- repo.getSettingsByPrefix("connector.default.")
      legacySettings <- repo.getSettingsByPrefix("ai.")
    yield buildConfig(agentSettings, globalSettings, legacySettings, agentName)

  private def buildConfig(
    agentSettings: Map[String, String],
    globalSettings: Map[String, String],
    legacySettings: Map[String, String],
    agentName: Option[String],
  ): ConnectorConfig =
    // Strip prefixes for uniform key access
    val agent  = agentName.fold(Map.empty[String, String])(name =>
      agentSettings.map { case (k, v) => k.stripPrefix(s"agent.$name.connector.") -> v })
    val global = globalSettings.map { case (k, v) => k.stripPrefix("connector.default.") -> v }
    val legacy = legacySettings.map { case (k, v) => k.stripPrefix("ai.") -> v }

    def get(key: String): Option[String] =
      agent.get(key).filter(_.nonEmpty)
        .orElse(global.get(key).filter(_.nonEmpty))
        .orElse(legacy.get(key).filter(_.nonEmpty))

    val connectorId = get("id").flatMap(parseConnectorId)
      .orElse(get("provider").flatMap(parseLegacyProvider))
      .getOrElse(ConnectorId.GeminiCli)

    val model   = get("model")
    val timeout = get("timeout").flatMap(s => scala.util.Try(s.toLong).toOption).map(_.seconds).getOrElse(300.seconds)
    val retries = get("maxRetries").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(3)

    if ConnectorId.allCli.contains(connectorId) then
      CliConnectorConfig(
        connectorId = connectorId,
        model = model,
        timeout = timeout,
        maxRetries = retries,
        flags = extractFlags(agent, global),
        envVars = extractEnvVars(agent, global),
      )
    else
      ApiConnectorConfig(
        connectorId = connectorId,
        model = model,
        baseUrl = get("baseUrl"),
        apiKey = get("apiKey"),
        timeout = timeout,
        maxRetries = retries,
        requestsPerMinute = get("requestsPerMinute").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(60),
        burstSize = get("burstSize").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(10),
        acquireTimeout = get("acquireTimeout").flatMap(s => scala.util.Try(s.toLong).toOption).map(_.seconds).getOrElse(30.seconds),
        temperature = get("temperature").flatMap(s => scala.util.Try(s.toDouble).toOption),
        maxTokens = get("maxTokens").flatMap(s => scala.util.Try(s.toInt).toOption),
      )

  private def parseConnectorId(value: String): Option[ConnectorId] =
    ConnectorId.all.find(_.value == value.toLowerCase.trim)

  private def parseLegacyProvider(value: String): Option[ConnectorId] =
    value.trim.toLowerCase match
      case "geminicli"  => Some(ConnectorId.GeminiCli)
      case "geminiapi"  => Some(ConnectorId.GeminiApi)
      case "openai"     => Some(ConnectorId.OpenAI)
      case "anthropic"  => Some(ConnectorId.Anthropic)
      case "lmstudio"   => Some(ConnectorId.LmStudio)
      case "ollama"     => Some(ConnectorId.Ollama)
      case "opencode"   => Some(ConnectorId.OpenCode)
      case "mock"       => Some(ConnectorId.Mock)
      case _            => None

  private def extractFlags(agent: Map[String, String], global: Map[String, String]): Map[String, String] =
    val prefix = "flags."
    val globalFlags = global.collect { case (k, v) if k.startsWith(prefix) => k.stripPrefix(prefix) -> v }
    val agentFlags  = agent.collect { case (k, v) if k.startsWith(prefix) => k.stripPrefix(prefix) -> v }
    globalFlags ++ agentFlags  // agent overrides global

  private def extractEnvVars(agent: Map[String, String], global: Map[String, String]): Map[String, String] =
    val prefix = "env."
    val globalEnv = global.collect { case (k, v) if k.startsWith(prefix) => k.stripPrefix(prefix) -> v }
    val agentEnv  = agent.collect { case (k, v) if k.startsWith(prefix) => k.stripPrefix(prefix) -> v }
    globalEnv ++ agentEnv
```

- [ ] **Step 3: Run tests**

Run: `sbt 'testOnly config.control.ConnectorConfigResolverSpec'`
Expected: All 4 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/config/control/ConnectorConfigResolver.scala src/test/scala/config/control/ConnectorConfigResolverSpec.scala
git commit -m "feat: add ConnectorConfigResolver with agent override, global default, and legacy ai.* fallback"
```

---

### Task 15: Migrate ConfigAwareLlmService to ConnectorRegistry

**Files:**
- Modify: `src/main/scala/app/ConfigAwareLlmService.scala`

- [ ] **Step 1: Replace buildProvider with ConnectorRegistry**

Refactor `ConfigAwareLlmService` to take `ConnectorRegistry` instead of `HttpClient & GeminiCliExecutor`:

```scala
final private[app] case class ConfigAwareLlmService(
  configRef: Ref[GatewayConfig],
  registry: ConnectorRegistry,
  cacheRef: Ref.Synchronized[Map[ConnectorConfig, Connector]],
) extends LlmService:

  private def serviceChain: IO[LlmError, List[LlmService]] =
    for
      config    <- configRef.get.map(_.resolvedConnectorConfig)
      primary   <- registry.resolve(config)
      fallbacks <- ZIO.foreach(config.fallbackChain.connectors)(registry.resolve)
    yield (primary :: fallbacks).collect { case api: ApiConnector => api: LlmService }

  // ... rest of failover methods unchanged, operating on LlmService
```

Note: During transition, `GatewayConfig` needs a `resolvedConnectorConfig` method. This is wired in Task 17.

- [ ] **Step 2: Compile gateway**

Run: `sbt compile`
Expected: May have compilation errors until `GatewayConfig.resolvedConnectorConfig` is added. That's OK — this task and Task 17 are done together.

- [ ] **Step 3: Commit (may be combined with Task 17)**

---

### Task 16: Migrate CliAgentRunner to Use CliConnector

**Files:**
- Modify: `src/main/scala/workspace/control/CliAgentRunner.scala`

- [ ] **Step 1: Thin down CliAgentRunner**

Remove all tool-specific argv construction from `buildArgvForHost` (lines 44-78) and `buildInteractiveArgvForHost` (lines 84-102). Replace with connector delegation:

```scala
object CliAgentRunner:

  def execute(
    connector: CliConnector,
    prompt: String,
    ctx: CliContext,
    runMode: RunMode,
    onLine: String => Task[Unit],
    executor: CliProcessExecutor,
  ): Task[Int] =
    val baseArgv = connector.buildArgv(prompt, ctx)
    val argv = wrapForRunMode(baseArgv, ctx.worktreePath, runMode, ctx.envVars)
    executor.runStreaming(argv, ctx.worktreePath, ctx.envVars)
      .tap(line => ZStream.fromZIO(onLine(line)))
      .runDrain
      .as(0)

  def interactionSupport(connector: CliConnector): InteractionSupport =
    connector.interactionSupport

  // Keep RunMode wrapping logic (Docker/Cloud) — this is execution environment, not connector concern
  private def wrapForRunMode(
    argv: List[String],
    worktreePath: String,
    runMode: RunMode,
    envVars: Map[String, String],
  ): List[String] =
    runMode match
      case RunMode.Host    => argv
      case d: RunMode.Docker =>
        val mount = if d.mountWorktree then List("-v", s"$worktreePath:/workspace", "--workdir", "/workspace") else Nil
        val net   = d.network.map(n => List("--network", n)).getOrElse(Nil)
        List("docker", "run", "--rm", "-i") ++ mount ++ net ++ List(d.image) ++ argv
      case c: RunMode.Cloud =>
        argv // Cloud runtime handles this at a higher level

  // Keep runProcess and runProcessStreaming for backward compat during transition
  def runProcess(argv: List[String], cwd: String, envVars: Map[String, String] = Map.empty): Task[(List[String], Int)] = ...
  def runProcessStreaming(argv: List[String], cwd: String, onLine: String => Task[Unit], envVars: Map[String, String] = Map.empty): Task[Int] = ...
```

- [ ] **Step 2: Update WorkspaceRunService to resolve CliConnector**

In `WorkspaceRunService`, replace `CliAgentRunner.buildArgv(workspace.cliTool, ...)` calls with:
1. Resolve `ConnectorConfig` via `ConnectorConfigResolver.resolve(Some(agentName))`
2. Resolve `CliConnector` via `ConnectorRegistry.resolveCli(config)`
3. Call `CliAgentRunner.execute(connector, prompt, ctx, runMode, onLine, executor)`

- [ ] **Step 3: Compile and test**

Run: `sbt compile && sbt test`
Expected: Compilation succeeds. Existing tests may need updating where they mock `CliAgentRunner.buildArgv`.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/workspace/control/CliAgentRunner.scala src/main/scala/workspace/control/WorkspaceRunService.scala
git commit -m "refactor: thin CliAgentRunner to delegate argv construction to CliConnector"
```

---

### Task 17: GatewayConfig and SettingsController Migration

**Files:**
- Modify: `modules/config-domain/src/main/scala/config/entity/GatewayConfigModels.scala`
- Modify: `src/main/scala/config/SettingsApplier.scala`
- Modify: `src/main/scala/config/boundary/SettingsController.scala`

- [ ] **Step 1: Add resolvedConnectorConfig to GatewayConfig**

In `GatewayConfigModels.scala`, add alongside the existing `resolvedProviderConfig`:

```scala
def resolvedConnectorConfig: ConnectorConfig =
  aiProvider.map(_.toConnectorConfig).getOrElse(
    CliConnectorConfig(ConnectorId.GeminiCli, Some(geminiModel))
  )
```

And add `toConnectorConfig` to `ProviderConfig` in `ProviderModels.scala`:

```scala
def toConnectorConfig: ConnectorConfig =
  if ConnectorId.allCli.contains(provider.toConnectorId) then
    CliConnectorConfig(
      connectorId = provider.toConnectorId,
      model = Some(model),
      timeout = timeout,
      maxRetries = maxRetries,
    )
  else
    ApiConnectorConfig(
      connectorId = provider.toConnectorId,
      model = Some(model),
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
```

- [ ] **Step 2: Update SettingsController for connector.* keys**

In `SettingsController`, update the POST `/settings/ai` handler to accept both old `ai.*` keys and new `connector.default.*` keys. Write new keys alongside old keys for backward compat:

```scala
// When saving connector settings, write both formats during transition
val connectorKeys = settings.filter(_._1.startsWith("connector."))
val legacyMappings = connectorKeys.map { case (k, v) =>
  k.replace("connector.default.", "ai.") -> v
}
```

- [ ] **Step 3: Compile and test**

Run: `sbt compile && sbt test`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add modules/config-domain/src/main/scala/config/entity/ src/main/scala/config/
git commit -m "feat: add resolvedConnectorConfig bridge and dual-write connector/ai settings keys"
```

---

## Phase 5: Remove Workspace.cliTool and Agent.cliTool

### Task 18: Remove cliTool from Workspace and Agent

**Files:**
- Modify: `modules/workspace-domain/src/main/scala/workspace/entity/WorkspaceModels.scala`
- Modify: `modules/workspace-domain/src/main/scala/workspace/entity/WorkspaceEvent.scala`
- Modify: `modules/agent-domain/src/main/scala/agent/entity/Agent.scala`
- Modify: `modules/agent-domain/src/main/scala/agent/entity/AgentPermissions.scala`

- [ ] **Step 1: Deprecate Workspace.cliTool**

In `WorkspaceModels.scala`, keep the field but make it optional with a default for backward compat during event replay:

```scala
case class Workspace(
  // ... existing fields
  @deprecated("Use ConnectorConfigResolver instead", "2026-04-15")
  cliTool: String = "gemini",   // kept for event replay compat
  // ...
)
```

In `WorkspaceEvent.Created` and `WorkspaceEvent.Updated`, keep `cliTool` in the event schema but mark deprecated.

- [ ] **Step 2: Update AgentPermissions.defaults to accept ConnectorId**

In `AgentPermissions.scala`, add an overload:

```scala
def defaults(
  trustLevel: TrustLevel,
  connectorId: ConnectorId,
  timeout: Duration,
  maxEstimatedTokens: Option[Long] = None,
): AgentPermissions =
  defaults(trustLevel, connectorId.value, timeout, maxEstimatedTokens)
```

- [ ] **Step 3: Update all callers in WorkspaceRunService**

Replace `workspace.cliTool` references with connector resolution:

```scala
// Before:
val cliTool = workspace.cliTool
CliAgentRunner.buildArgv(cliTool, prompt, ...)

// After:
for
  config    <- ConnectorConfigResolver.resolve(Some(agentName))
  connector <- ConnectorRegistry.resolveCli(config.asInstanceOf[CliConnectorConfig])
yield CliAgentRunner.execute(connector, prompt, ctx, workspace.runMode, onLine, executor)
```

- [ ] **Step 4: Compile and run tests**

Run: `sbt compile && sbt test`
Expected: Deprecation warnings for `cliTool` usage, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add modules/workspace-domain/ modules/agent-domain/ src/main/scala/workspace/
git commit -m "refactor: deprecate Workspace.cliTool and Agent.cliTool, use ConnectorConfigResolver"
```

---

## Phase 6: UI Refactoring

### Task 19: Connectors Tab (replaces AI Tab)

**Files:**
- Modify: `modules/shared-web/src/main/scala/shared/web/SettingsView.scala`
- Modify: `modules/shared-web-core/src/main/scala/shared/web/SettingsShell.scala`

- [ ] **Step 1: Update SettingsShell tabs**

In `SettingsShell.scala` (line 8-18), replace `("ai", "AI Models")` with `("connectors", "Connectors")` and remove `("advanced", "Advanced Config")`:

```scala
val tabs: List[(String, String)] = List(
  ("connectors", "Connectors"),
  ("channels", "Channels"),
  ("gateway", "Gateway"),
  ("issues-templates", "Issue Templates"),
  ("governance", "Governance"),
  ("daemons", "Daemons"),
  ("system", "System"),
  ("demo", "Demo"),
)
```

- [ ] **Step 2: Create connectorsTab in SettingsView**

Replace `aiTab` with `connectorsTab` in `SettingsView.scala`. The new method renders a grouped connector selector with dynamic form fields:

```scala
def connectorsTab(
  settings: Map[String, String],
  registry: config.entity.ModelRegistryResponse,
  statuses: List[config.entity.ProviderProbeStatus],
  flash: Option[String] = None,
  errors: Map[String, String] = Map.empty,
): String =
  settingsShell("connectors", "Settings — Connectors")(
    // Flash message
    flash.map(msg => div(cls := "flash-message", msg)),
    
    // Connector selector grouped by kind
    div(cls := "form-group")(
      label("Connector"),
      select(name := "connector.default.id", id := "connector-selector",
        attr("hx-get") := "/settings/connectors/fields",
        attr("hx-target") := "#connector-fields",
        attr("hx-trigger") := "change",
      )(
        optgroup(attr("label") := "API Providers")(
          option(value := "openai")("OpenAI"),
          option(value := "anthropic")("Anthropic"),
          option(value := "gemini-api")("Gemini API"),
          option(value := "lm-studio")("LM Studio"),
          option(value := "ollama")("Ollama"),
        ),
        optgroup(attr("label") := "CLI Tools")(
          option(value := "claude-cli")("Claude CLI"),
          option(value := "gemini-cli")("Gemini CLI"),
          option(value := "opencode")("OpenCode"),
          option(value := "codex")("Codex"),
          option(value := "copilot")("Copilot"),
        ),
      ),
    ),
    
    // Dynamic fields container (swapped by HTMX based on selection)
    div(id := "connector-fields")(
      // Render fields based on current settings
      connectorFields(settings),
    ),
    
    // Common fields
    commonFields(settings),
    
    // Test connection + Save buttons
    // ... same pattern as existing aiTab
  ).render
```

- [ ] **Step 3: Remove advancedTab**

Delete the `advancedTab` method from `SettingsView.scala` (lines 374-394).

- [ ] **Step 4: Update SettingsController routes**

In `SettingsController.scala`:
- Add route `GET /settings/connectors` → renders `connectorsTab`
- Add route `POST /settings/connectors` → saves `connector.default.*` keys (plus `ai.*` dual-write)
- Add route `GET /settings/connectors/fields` → returns dynamic form fields HTML fragment
- Remove route `GET /settings/advanced`
- Redirect `GET /settings/ai` → `/settings/connectors` for backward compat

- [ ] **Step 5: Compile and test UI**

Run: `sbt compile && sbt run`
Navigate to `http://localhost:8080/settings/connectors`. Verify:
- Grouped dropdown renders
- Selecting a connector shows appropriate fields
- Save persists to DB
- Test Connection works

- [ ] **Step 6: Commit**

```bash
git add modules/shared-web/ modules/shared-web-core/ src/main/scala/config/boundary/
git commit -m "feat: replace AI tab with unified Connectors tab in Settings, remove Advanced Config tab"
```

---

### Task 20: Agent Config Page with Connector Override

**Files:**
- Modify: `modules/agent-domain/src/main/scala/agent/boundary/AgentsView.scala`

- [ ] **Step 1: Update agentConfigPage**

Replace the AI provider override form (lines 743-812) with connector override form:

```scala
def agentConfigPage(
  agent: AgentInfo,
  overrideSettings: Map[String, String],
  globalSettings: Map[String, String],
  flash: Option[String],
): String =
  // Same grouped connector selector as Settings, but with "Use global" empty option
  // All fields optional, placeholders show global defaults
  // POST to /agents/{name}/config with connector.* keys
```

- [ ] **Step 2: Update AgentsController**

Update the POST handler for `/agents/{name}/config` to write `agent.<name>.connector.*` keys instead of `agent.<name>.ai.*` keys.

- [ ] **Step 3: Test in browser**

Run: `sbt run`, navigate to agent config page, verify override form works.

- [ ] **Step 4: Commit**

```bash
git add modules/agent-domain/src/main/scala/agent/boundary/ src/main/scala/agent/boundary/
git commit -m "feat: update agent config page to use connector override form"
```

---

## Phase 7: Remove Legacy Code

### Task 21: Delete Advanced Config Infrastructure

**Files:**
- Delete: `src/main/scala/config/boundary/ConfigController.scala`
- Delete: `src/main/resources/static/client/components/ab-config-editor.js`
- Modify: `src/main/scala/app/ApplicationDI.scala` (remove ConfigController wiring)

- [ ] **Step 1: Remove ConfigController**

Delete the file and remove its route registration from `ApplicationDI` or wherever routes are composed.

- [ ] **Step 2: Remove ab-config-editor.js**

Delete the web component file.

- [ ] **Step 3: Compile and test**

Run: `sbt compile && sbt test`
Expected: All tests pass. No references to deleted code remain.

- [ ] **Step 4: Verify /settings/advanced returns 404**

Run: `sbt run`, navigate to `http://localhost:8080/settings/advanced`. Should redirect or 404.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: remove Advanced Config tab, ConfigController, and ab-config-editor component"
```

---

### Task 22: Deprecate AgentConfigResolver and ProviderConfig

**Files:**
- Modify: `src/main/scala/orchestration/control/AgentConfigResolver.scala`
- Modify: `modules/config-domain/src/main/scala/config/entity/ProviderModels.scala`

- [ ] **Step 1: Mark as deprecated**

Add `@deprecated` annotations to `AgentConfigResolver` and `ProviderConfig`. Update all remaining callers to use `ConnectorConfigResolver` and `ConnectorConfig`.

- [ ] **Step 2: Compile and verify no non-deprecated usages remain**

Run: `sbt compile` — only deprecation warnings, no errors.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/orchestration/ modules/config-domain/
git commit -m "chore: deprecate AgentConfigResolver and ProviderConfig in favor of Connector types"
```

---

## Phase 8: Smoke Tests

### Task 23: Connector Smoke Test Suite

**Files:**
- Create: `llm4zio/src/test/scala/llm4zio/providers/ConnectorSmokeSpec.scala`

- [ ] **Step 1: Create smoke test suite**

```scala
package llm4zio.providers

import zio.*
import zio.test.*
import zio.test.TestAspect.*
import llm4zio.core.*

object ConnectorSmokeSpec extends ZIOSpecDefault:

  private def ifEnvSet(key: String) = TestAspect.ifEnvSet(key)

  def spec = suite("Connector Smoke Tests")(
    test("OpenAI connector responds to ping") {
      for
        apiKey    <- ZIO.system.env("OPENAI_API_KEY").someOrFail(new RuntimeException("missing key"))
        config     = ApiConnectorConfig(ConnectorId.OpenAI, Some("gpt-4o-mini"), apiKey = Some(apiKey))
        http      <- ZIO.service[HttpClient]
        connector  = OpenAIProvider.make(config.toLlmConfig, http)
        status    <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    } @@ ifEnvSet("OPENAI_API_KEY"),

    test("Anthropic connector responds to ping") {
      for
        apiKey    <- ZIO.system.env("ANTHROPIC_API_KEY").someOrFail(new RuntimeException("missing key"))
        config     = ApiConnectorConfig(ConnectorId.Anthropic, Some("claude-haiku-4-5-20251001"), apiKey = Some(apiKey))
        http      <- ZIO.service[HttpClient]
        connector  = AnthropicProvider.make(config.toLlmConfig, http)
        status    <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    } @@ ifEnvSet("ANTHROPIC_API_KEY"),

    test("Gemini CLI connector installed and responds") {
      val executor = GeminiCliExecutor.default
      val config = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
      val connector = GeminiCliProvider.make(config, executor)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    } @@ ifEnvSet("GEMINI_CLI_AVAILABLE"),

    test("Claude CLI connector installed") {
      // Use a simple live executor that delegates to ProcessBuilder
      val executor = new CliProcessExecutor:
        def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
          ZIO.attemptBlocking {
            val pb = new ProcessBuilder(argv*)
            pb.directory(new java.io.File(cwd))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val lines = scala.io.Source.fromInputStream(proc.getInputStream).getLines().toList
            val exit = proc.waitFor()
            ProcessResult(lines, exit)
          }.mapError(e => LlmError.ProviderError(e.getMessage, Some(e)))
        def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String]): ZStream[Any, LlmError, String] =
          ZStream.fromZIO(run(argv, cwd, envVars)).flatMap(r => ZStream.fromIterable(r.stdout))
      val config = CliConnectorConfig(ConnectorId.ClaudeCli)
      val connector = ClaudeCliConnector.make(config, executor)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    } @@ ifEnvSet("CLAUDE_CLI_AVAILABLE"),
  ) @@ sequential
```

- [ ] **Step 2: Run smoke tests locally**

Run (with at least one env var set):
```bash
GEMINI_CLI_AVAILABLE=true sbt 'llm4zio/testOnly llm4zio.providers.ConnectorSmokeSpec'
```
Expected: Tests with matching env vars run and pass. Others are skipped.

- [ ] **Step 3: Commit**

```bash
git add llm4zio/src/test/scala/llm4zio/providers/ConnectorSmokeSpec.scala
git commit -m "feat: add connector smoke test suite gated behind env vars"
```

---

## Phase 9: Final Verification

### Task 24: End-to-End Verification

- [ ] **Step 1: Run full library test suite**

Run: `sbt llm4zio/test`
Expected: All tests PASS.

- [ ] **Step 2: Run full gateway test suite**

Run: `sbt test`
Expected: All 1121+ tests PASS.

- [ ] **Step 3: Run integration tests**

Run: `sbt it:test`
Expected: All 18+ integration tests PASS.

- [ ] **Step 4: Start gateway and verify UI**

Run: `sbt run`
Verify:
1. `/settings/connectors` — grouped dropdown, dynamic fields, test connection works
2. `/agents/{name}/config` — connector override form with global defaults as placeholders
3. `/settings/advanced` — returns 404 or redirects
4. `/api/config` endpoints — return 404
5. Configure a CLI connector (e.g. Gemini CLI), save, verify runs use it

- [ ] **Step 5: Run formatting**

Run: `sbt fmt`
Expected: All files formatted.

- [ ] **Step 6: Final commit if any formatting changes**

```bash
git add -A
git commit -m "chore: format after unified connector architecture refactoring"
```
