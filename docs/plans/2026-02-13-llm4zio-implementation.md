# llm4zio Module Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create llm4zio module with enhanced LlmService, migrate existing AIService code, and update all usages

**Architecture:** Single sbt module with clean package structure (core, providers, tools, observability, agents). Big bang migration replacing AIService with enhanced LlmService supporting streaming, conversation history, tool calling, and observability.

**Tech Stack:** Scala 3, ZIO 2.x, zio-streams, zio-json, zio-http, zio-logging, zio-test

---

## Task 1: Create llm4zio Module Structure

**Files:**
- Modify: `build.sbt`
- Create: `llm4zio/src/main/scala/llm4zio/core/.gitkeep`
- Create: `llm4zio/src/main/scala/llm4zio/providers/.gitkeep`
- Create: `llm4zio/src/main/scala/llm4zio/tools/.gitkeep`
- Create: `llm4zio/src/main/scala/llm4zio/observability/.gitkeep`
- Create: `llm4zio/src/main/scala/llm4zio/agents/.gitkeep`
- Create: `llm4zio/src/test/scala/llm4zio/core/.gitkeep`
- Create: `llm4zio/src/test/scala/llm4zio/providers/.gitkeep`
- Create: `llm4zio/src/test/scala/llm4zio/tools/.gitkeep`
- Create: `llm4zio/src/test/scala/llm4zio/observability/.gitkeep`

**Step 1: Create directory structure**

```bash
mkdir -p llm4zio/src/main/scala/llm4zio/{core,providers,tools,observability,agents}
mkdir -p llm4zio/src/test/scala/llm4zio/{core,providers,tools,observability}
touch llm4zio/src/main/scala/llm4zio/{core,providers,tools,observability,agents}/.gitkeep
touch llm4zio/src/test/scala/llm4zio/{core,providers,tools,observability}/.gitkeep
```

**Step 2: Add llm4zio module to build.sbt**

Modify `build.sbt` - add before the `root` project definition:

```scala
lazy val llm4zio = (project in file("llm4zio"))
  .configs(It)
  .settings(inConfig(It)(Defaults.testSettings): _*)
  .settings(
    name := "llm4zio",
    description := "ZIO-native LLM framework",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.24",
      "dev.zio" %% "zio-streams" % "2.1.24",
      "dev.zio" %% "zio-json" % "0.9.0",
      "dev.zio" %% "zio-http" % "3.8.1",
      "dev.zio" %% "zio-logging" % "2.4.0",
      "dev.zio" %% "zio-test" % "2.1.24" % "test,it",
      "dev.zio" %% "zio-test-sbt" % "2.1.24" % "test,it",
      "dev.zio" %% "zio-test-magnolia" % "2.1.24" % "test,it"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    It / testFrameworks ++= (Test / testFrameworks).value,
  )
```

And update the `root` project to depend on `llm4zio`:

```scala
lazy val root = (project in file("."))
  .dependsOn(llm4zio)
  .configs(It)
  // ... rest of existing settings
```

**Step 3: Verify module structure compiles**

Run: `sbt llm4zio/compile`
Expected: SUCCESS (empty module compiles)

**Step 4: Commit**

```bash
git add build.sbt llm4zio/
git commit -m "feat: create llm4zio module structure"
```

---

## Task 2: Core Models and Error Types

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/core/Models.scala`
- Create: `llm4zio/src/main/scala/llm4zio/core/Errors.scala`
- Test: `llm4zio/src/test/scala/llm4zio/core/ModelsSpec.scala`

**Step 1: Write failing test for core models**

Create `llm4zio/src/test/scala/llm4zio/core/ModelsSpec.scala`:

```scala
package llm4zio.core

import zio.*
import zio.test.*
import zio.json.*

object ModelsSpec extends ZIOSpecDefault:
  def spec = suite("Models")(
    test("LlmProvider should serialize to JSON") {
      val provider = LlmProvider.OpenAI
      val json = provider.toJson
      assertTrue(json == "\"OpenAI\"")
    },
    test("MessageRole should serialize to JSON") {
      val role = MessageRole.User
      val json = role.toJson
      assertTrue(json == "\"User\"")
    },
    test("Message should serialize and deserialize") {
      val message = Message(MessageRole.User, "Hello")
      val json = message.toJson
      val decoded = json.fromJson[Message]
      assertTrue(decoded == Right(message))
    },
    test("LlmResponse should serialize and deserialize") {
      val response = LlmResponse(
        content = "Hello world",
        usage = Some(TokenUsage(prompt = 10, completion = 5, total = 15)),
        metadata = Map("model" -> "gpt-4")
      )
      val json = response.toJson
      val decoded = json.fromJson[LlmResponse]
      assertTrue(decoded == Right(response))
    },
    test("LlmChunk should serialize and deserialize") {
      val chunk = LlmChunk(delta = "Hello", finishReason = Some("stop"))
      val json = chunk.toJson
      val decoded = json.fromJson[LlmChunk]
      assertTrue(decoded == Right(chunk))
    },
    test("LlmConfig should have defaults") {
      val config = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.5-flash"
      )
      assertTrue(
        config.temperature.isEmpty,
        config.maxTokens.isEmpty,
        config.baseUrl.isEmpty
      )
    }
  )
```

**Step 2: Run test to verify it fails**

Run: `sbt llm4zio/test`
Expected: FAIL with "object LlmProvider is not a member of package llm4zio.core"

**Step 3: Implement core models**

Create `llm4zio/src/main/scala/llm4zio/core/Models.scala`:

```scala
package llm4zio.core

import zio.*
import zio.json.*

enum LlmProvider derives JsonCodec:
  case GeminiCli, GeminiApi, OpenAI, Anthropic

object LlmProvider:
  def defaultBaseUrl(provider: LlmProvider): Option[String] = provider match
    case LlmProvider.GeminiCli => None
    case LlmProvider.GeminiApi => Some("https://generativelanguage.googleapis.com")
    case LlmProvider.OpenAI    => Some("https://api.openai.com/v1")
    case LlmProvider.Anthropic => Some("https://api.anthropic.com")

enum MessageRole derives JsonCodec:
  case System, User, Assistant, Tool

case class Message(
  role: MessageRole,
  content: String,
) derives JsonCodec

case class TokenUsage(
  prompt: Int,
  completion: Int,
  total: Int,
) derives JsonCodec

case class LlmResponse(
  content: String,
  usage: Option[TokenUsage] = None,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

case class LlmChunk(
  delta: String,
  finishReason: Option[String] = None,
  usage: Option[TokenUsage] = None,
) derives JsonCodec

case class LlmConfig(
  provider: LlmProvider,
  model: String,
  baseUrl: Option[String] = None,
  apiKey: Option[String] = None,
  timeout: Duration = 300.seconds,
  maxRetries: Int = 3,
  requestsPerMinute: Int = 60,
  burstSize: Int = 10,
  acquireTimeout: Duration = 30.seconds,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
) derives JsonCodec

object LlmConfig:
  def withDefaults(config: LlmConfig): LlmConfig =
    config.baseUrl match
      case Some(_) => config
      case None    => config.copy(baseUrl = LlmProvider.defaultBaseUrl(config.provider))
```

**Step 4: Write test for error types**

Add to `llm4zio/src/test/scala/llm4zio/core/ModelsSpec.scala`:

```scala
test("LlmError types should be distinct") {
  val providerError: LlmError = LlmError.ProviderError("test")
  val rateLimitError: LlmError = LlmError.RateLimitError(Some(10.seconds))
  val authError: LlmError = LlmError.AuthenticationError("invalid key")

  assertTrue(
    providerError.isInstanceOf[LlmError.ProviderError],
    rateLimitError.isInstanceOf[LlmError.RateLimitError],
    authError.isInstanceOf[LlmError.AuthenticationError]
  )
}
```

**Step 5: Implement error types**

Create `llm4zio/src/main/scala/llm4zio/core/Errors.scala`:

```scala
package llm4zio.core

import zio.*
import zio.json.*

sealed trait LlmError extends Throwable derives JsonCodec

object LlmError:
  case class ProviderError(message: String, cause: Option[Throwable] = None) extends LlmError derives JsonCodec
  case class RateLimitError(retryAfter: Option[Duration] = None) extends LlmError derives JsonCodec
  case class AuthenticationError(message: String) extends LlmError derives JsonCodec
  case class InvalidRequestError(message: String) extends LlmError derives JsonCodec
  case class TimeoutError(duration: Duration) extends LlmError derives JsonCodec
  case class ParseError(message: String, raw: String) extends LlmError derives JsonCodec
  case class ToolError(toolName: String, message: String) extends LlmError derives JsonCodec
  case class ConfigError(message: String) extends LlmError derives JsonCodec
```

**Step 6: Run tests to verify they pass**

Run: `sbt llm4zio/test`
Expected: PASS

**Step 7: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/Models.scala
git add llm4zio/src/main/scala/llm4zio/core/Errors.scala
git add llm4zio/src/test/scala/llm4zio/core/ModelsSpec.scala
git commit -m "feat: add core models and error types"
```

---

## Task 3: Move RateLimiter to llm4zio.core

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/core/RateLimiter.scala`
- Test: `llm4zio/src/test/scala/llm4zio/core/RateLimiterSpec.scala`

**Step 1: Copy RateLimiter implementation**

```bash
cp src/main/scala/core/RateLimiter.scala llm4zio/src/main/scala/llm4zio/core/RateLimiter.scala
```

**Step 2: Update package declaration**

Modify `llm4zio/src/main/scala/llm4zio/core/RateLimiter.scala`:

Change `package core` to `package llm4zio.core`

**Step 3: Copy RateLimiter tests if they exist**

```bash
if [ -f src/test/scala/core/RateLimiterSpec.scala ]; then
  cp src/test/scala/core/RateLimiterSpec.scala llm4zio/src/test/scala/llm4zio/core/RateLimiterSpec.scala
fi
```

**Step 4: Update test package if copied**

If test file exists, change `package core` to `package llm4zio.core`

**Step 5: Verify tests pass**

Run: `sbt llm4zio/test`
Expected: PASS

**Step 6: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/RateLimiter.scala
git commit -m "feat: move RateLimiter to llm4zio.core"
```

---

## Task 4: Move RetryPolicy to llm4zio.core

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/core/RetryPolicy.scala`

**Step 1: Copy RetryPolicy implementation**

```bash
cp src/main/scala/core/RetryPolicy.scala llm4zio/src/main/scala/llm4zio/core/RetryPolicy.scala
```

**Step 2: Update package declaration**

Modify `llm4zio/src/main/scala/llm4zio/core/RetryPolicy.scala`:

Change `package core` to `package llm4zio.core`

**Step 3: Verify compilation**

Run: `sbt llm4zio/compile`
Expected: SUCCESS

**Step 4: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/RetryPolicy.scala
git commit -m "feat: move RetryPolicy to llm4zio.core"
```

---

## Task 5: LlmService Trait Definition

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/core/LlmService.scala`
- Test: `llm4zio/src/test/scala/llm4zio/core/LlmServiceSpec.scala`

**Step 1: Write failing test for LlmService contract**

Create `llm4zio/src/test/scala/llm4zio/core/LlmServiceSpec.scala`:

```scala
package llm4zio.core

import zio.*
import zio.test.*
import zio.stream.*

object LlmServiceSpec extends ZIOSpecDefault:
  // Mock implementation for testing
  class MockLlmService extends LlmService:
    override def execute(prompt: String): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse(content = s"Response to: $prompt"))

    override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
      ZStream(
        LlmChunk(delta = "Hello", finishReason = None),
        LlmChunk(delta = " world", finishReason = Some("stop"))
      )

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse(content = s"Response to ${messages.length} messages"))

    override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
      ZStream(LlmChunk(delta = "Stream response", finishReason = Some("stop")))

    override def executeWithTools(prompt: String, tools: List[Tool]): IO[LlmError, ToolCallResponse] =
      ZIO.succeed(ToolCallResponse(content = Some("No tools needed"), toolCalls = List.empty, finishReason = "stop"))

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.InvalidRequestError("Not implemented in mock"))

    override def isAvailable: UIO[Boolean] =
      ZIO.succeed(true)

  def spec = suite("LlmService")(
    test("execute should return response") {
      val service = new MockLlmService()
      for {
        response <- service.execute("test prompt")
      } yield assertTrue(response.content == "Response to: test prompt")
    },
    test("executeStream should return chunks") {
      val service = new MockLlmService()
      for {
        chunks <- service.executeStream("test").runCollect
      } yield assertTrue(
        chunks.size == 2,
        chunks(0).delta == "Hello",
        chunks(1).finishReason == Some("stop")
      )
    },
    test("executeWithHistory should handle messages") {
      val service = new MockLlmService()
      val messages = List(
        Message(MessageRole.User, "Hello"),
        Message(MessageRole.Assistant, "Hi there")
      )
      for {
        response <- service.executeWithHistory(messages)
      } yield assertTrue(response.content.contains("2 messages"))
    },
    test("isAvailable should return true for mock") {
      val service = new MockLlmService()
      for {
        available <- service.isAvailable
      } yield assertTrue(available)
    },
    test("ZIO service accessors should work") {
      for {
        response <- LlmService.execute("test")
      } yield assertTrue(response.content.nonEmpty)
    }.provide(ZLayer.succeed[LlmService](new MockLlmService()))
  )
```

**Step 2: Run test to verify it fails**

Run: `sbt llm4zio/test`
Expected: FAIL with "object LlmService is not a member of package llm4zio.core"

**Step 3: Implement LlmService trait**

Create `llm4zio/src/main/scala/llm4zio/core/LlmService.scala`:

```scala
package llm4zio.core

import zio.*
import zio.stream.*
import zio.json.*

// Placeholder for Tool (will be defined in tools package)
type Tool = Any
type JsonSchema = Any
case class ToolCall(id: String, name: String, arguments: String) derives JsonCodec
case class ToolCallResponse(
  content: Option[String],
  toolCalls: List[ToolCall],
  finishReason: String,
) derives JsonCodec

trait LlmService:
  // Basic execution
  def execute(prompt: String): IO[LlmError, LlmResponse]

  // Streaming primitives
  def executeStream(prompt: String): Stream[LlmError, LlmChunk]

  // Conversation support
  def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse]
  def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]

  // Tool calling
  def executeWithTools(prompt: String, tools: List[Tool]): IO[LlmError, ToolCallResponse]

  // Structured output
  def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]

  // Health check
  def isAvailable: UIO[Boolean]

object LlmService:
  // ZIO service accessors
  def execute(prompt: String): ZIO[LlmService, LlmError, LlmResponse] =
    ZIO.serviceWithZIO[LlmService](_.execute(prompt))

  def executeStream(prompt: String): ZStream[LlmService, LlmError, LlmChunk] =
    ZStream.serviceWithStream[LlmService](_.executeStream(prompt))

  def executeWithHistory(messages: List[Message]): ZIO[LlmService, LlmError, LlmResponse] =
    ZIO.serviceWithZIO[LlmService](_.executeWithHistory(messages))

  def executeStreamWithHistory(messages: List[Message]): ZStream[LlmService, LlmError, LlmChunk] =
    ZStream.serviceWithStream[LlmService](_.executeStreamWithHistory(messages))

  def executeWithTools(prompt: String, tools: List[Tool]): ZIO[LlmService, LlmError, ToolCallResponse] =
    ZIO.serviceWithZIO[LlmService](_.executeWithTools(prompt, tools))

  def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): ZIO[LlmService, LlmError, A] =
    ZIO.serviceWithZIO[LlmService](_.executeStructured(prompt, schema))

  def isAvailable: ZIO[LlmService, Nothing, Boolean] =
    ZIO.serviceWithZIO[LlmService](_.isAvailable)
```

**Step 4: Run tests to verify they pass**

Run: `sbt llm4zio/test`
Expected: PASS

**Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/LlmService.scala
git add llm4zio/src/test/scala/llm4zio/core/LlmServiceSpec.scala
git commit -m "feat: add LlmService trait with ZIO accessors"
```

---

## Task 6: Provider Models and HTTP Client

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/core/ProviderModels.scala`
- Create: `llm4zio/src/main/scala/llm4zio/providers/HttpClient.scala`

**Step 1: Copy AIModels to ProviderModels**

```bash
cp src/main/scala/models/AIModels.scala llm4zio/src/main/scala/llm4zio/core/ProviderModels.scala
```

**Step 2: Update package and imports**

Modify `llm4zio/src/main/scala/llm4zio/core/ProviderModels.scala`:

- Change `package models` to `package llm4zio.core`
- Keep all the provider-specific request/response types (ChatCompletionRequest, AnthropicRequest, GeminiGenerateContentRequest, etc.)

**Step 3: Copy HttpAIClient to HttpClient**

```bash
cp src/main/scala/core/HttpAIClient.scala llm4zio/src/main/scala/llm4zio/providers/HttpClient.scala
```

**Step 4: Update package and rename**

Modify `llm4zio/src/main/scala/llm4zio/providers/HttpClient.scala`:

- Change `package core` to `package llm4zio.providers`
- Change `trait HttpAIClient` to `trait HttpClient`
- Change `object HttpAIClient` to `object HttpClient`
- Update imports to use `llm4zio.core.*`

**Step 5: Copy ResponseParser**

```bash
cp src/main/scala/core/ResponseParser.scala llm4zio/src/main/scala/llm4zio/providers/ResponseParser.scala
```

**Step 6: Update package**

Modify `llm4zio/src/main/scala/llm4zio/providers/ResponseParser.scala`:

- Change `package core` to `package llm4zio.providers`
- Update imports to use `llm4zio.core.*`

**Step 7: Verify compilation**

Run: `sbt llm4zio/compile`
Expected: SUCCESS

**Step 8: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/ProviderModels.scala
git add llm4zio/src/main/scala/llm4zio/providers/HttpClient.scala
git add llm4zio/src/main/scala/llm4zio/providers/ResponseParser.scala
git commit -m "feat: add provider models and HTTP client"
```

---

## Task 7: Migrate Gemini CLI Provider

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`
- Create: `llm4zio/src/main/scala/llm4zio/providers/GeminiCliExecutor.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

**Step 1: Copy Gemini CLI files**

```bash
cp src/main/scala/core/GeminiCliAIService.scala llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala
if [ -f src/main/scala/core/GeminiService.scala ]; then
  cp src/main/scala/core/GeminiService.scala llm4zio/src/main/scala/llm4zio/providers/GeminiCliExecutor.scala
fi
```

**Step 2: Update package and rename**

Modify `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`:

- Change `package core` to `package llm4zio.providers`
- Rename `GeminiCliAIService` to `GeminiCliProvider`
- Update imports: `import llm4zio.core.*`
- Change to implement `LlmService` trait
- Convert `AIResponse` to `LlmResponse`
- Add stub implementations for new methods (executeStream, executeWithHistory, executeWithTools)

**Step 3: Update GeminiCliExecutor if it exists**

If `GeminiCliExecutor.scala` was copied:
- Change `package core` to `package llm4zio.providers`
- Update imports

**Step 4: Copy and update tests**

```bash
if [ -f src/test/scala/core/GeminiCliAIServiceSpec.scala ]; then
  cp src/test/scala/core/GeminiCliAIServiceSpec.scala llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
fi
```

Update test:
- Change `package core` to `package llm4zio.providers`
- Rename test object
- Update imports

**Step 5: Verify compilation and tests**

Run: `sbt llm4zio/compile && sbt llm4zio/test`
Expected: SUCCESS

**Step 6: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiCli*
git add llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat: migrate Gemini CLI provider"
```

---

## Task 8: Migrate Gemini API Provider

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/providers/GeminiApiProvider.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/GeminiApiProviderSpec.scala`

**Step 1: Copy Gemini API service**

```bash
cp src/main/scala/core/GeminiApiAIService.scala llm4zio/src/main/scala/llm4zio/providers/GeminiApiProvider.scala
```

**Step 2: Update package and rename**

Modify `llm4zio/src/main/scala/llm4zio/providers/GeminiApiProvider.scala`:

- Change `package core` to `package llm4zio.providers`
- Rename `GeminiApiAIService` to `GeminiApiProvider`
- Update imports: `import llm4zio.core.*`
- Change to implement `LlmService` trait
- Convert types: `AIResponse` → `LlmResponse`, `AIProviderConfig` → `LlmConfig`, `AIError` → `LlmError`
- Add stub implementations for new methods

**Step 3: Copy and update tests**

```bash
if [ -f src/test/scala/core/GeminiApiAIServiceSpec.scala ]; then
  cp src/test/scala/core/GeminiApiAIServiceSpec.scala llm4zio/src/test/scala/llm4zio/providers/GeminiApiProviderSpec.scala
fi
```

Update test package and imports.

**Step 4: Verify compilation and tests**

Run: `sbt llm4zio/compile && sbt llm4zio/test`
Expected: SUCCESS

**Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiApiProvider.scala
git add llm4zio/src/test/scala/llm4zio/providers/GeminiApiProviderSpec.scala
git commit -m "feat: migrate Gemini API provider"
```

---

## Task 9: Migrate OpenAI Provider

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/providers/OpenAIProvider.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/OpenAIProviderSpec.scala`

**Step 1: Copy OpenAI service**

```bash
cp src/main/scala/core/OpenAICompatAIService.scala llm4zio/src/main/scala/llm4zio/providers/OpenAIProvider.scala
```

**Step 2: Update package and rename**

Modify `llm4zio/src/main/scala/llm4zio/providers/OpenAIProvider.scala`:

- Change `package core` to `package llm4zio.providers`
- Rename `OpenAICompatAIService` to `OpenAIProvider`
- Update imports and type conversions
- Implement `LlmService` trait

**Step 3: Copy and update tests**

```bash
if [ -f src/test/scala/core/OpenAICompatAIServiceSpec.scala ]; then
  cp src/test/scala/core/OpenAICompatAIServiceSpec.scala llm4zio/src/test/scala/llm4zio/providers/OpenAIProviderSpec.scala
fi
```

**Step 4: Verify compilation and tests**

Run: `sbt llm4zio/compile && sbt llm4zio/test`
Expected: SUCCESS

**Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/OpenAIProvider.scala
git add llm4zio/src/test/scala/llm4zio/providers/OpenAIProviderSpec.scala
git commit -m "feat: migrate OpenAI provider"
```

---

## Task 10: Migrate Anthropic Provider

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/providers/AnthropicProvider.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/AnthropicProviderSpec.scala`

**Step 1: Copy Anthropic service**

```bash
cp src/main/scala/core/AnthropicCompatAIService.scala llm4zio/src/main/scala/llm4zio/providers/AnthropicProvider.scala
```

**Step 2: Update package and rename**

Modify `llm4zio/src/main/scala/llm4zio/providers/AnthropicProvider.scala`:

- Change `package core` to `package llm4zio.providers`
- Rename `AnthropicCompatAIService` to `AnthropicProvider`
- Update imports and type conversions
- Implement `LlmService` trait

**Step 3: Copy and update tests**

```bash
if [ -f src/test/scala/core/OpenAICompatAIServiceSpec.scala ]; then
  cp src/test/scala/core/OpenAICompatAIServiceSpec.scala llm4zio/src/test/scala/llm4zio/providers/AnthropicProviderSpec.scala
fi
```

**Step 4: Verify compilation and tests**

Run: `sbt llm4zio/compile && sbt llm4zio/test`
Expected: SUCCESS

**Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/AnthropicProvider.scala
git add llm4zio/src/test/scala/llm4zio/providers/AnthropicProviderSpec.scala
git commit -m "feat: migrate Anthropic provider"
```

---

## Task 11: LlmService Factory Layer

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/core/LlmService.scala`

**Step 1: Add fromConfig factory to LlmService**

Modify `llm4zio/src/main/scala/llm4zio/core/LlmService.scala`:

Add to the `object LlmService` section:

```scala
import llm4zio.providers.*

val fromConfig: ZLayer[LlmConfig & RateLimiter & HttpClient, LlmError, LlmService] =
  ZLayer.fromZIO {
    for
      defaultConfig <- ZIO.service[LlmConfig]
      rateLimiter   <- ZIO.service[RateLimiter]
      httpClient    <- ZIO.service[HttpClient]
    yield new LlmService {
      private def build(config: LlmConfig): LlmService =
        config.provider match
          case LlmProvider.GeminiCli => GeminiCliProvider.make(config, rateLimiter, GeminiCliExecutor.default)
          case LlmProvider.GeminiApi => GeminiApiProvider.make(config, rateLimiter, httpClient)
          case LlmProvider.OpenAI    => OpenAIProvider(config, rateLimiter, httpClient)
          case LlmProvider.Anthropic => AnthropicProvider(config, rateLimiter, httpClient)

      override def execute(prompt: String): IO[LlmError, LlmResponse] =
        build(defaultConfig).execute(prompt)

      override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
        build(defaultConfig).executeStream(prompt)

      override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
        build(defaultConfig).executeWithHistory(messages)

      override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
        build(defaultConfig).executeStreamWithHistory(messages)

      override def executeWithTools(prompt: String, tools: List[Tool]): IO[LlmError, ToolCallResponse] =
        build(defaultConfig).executeWithTools(prompt, tools)

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        build(defaultConfig).executeStructured(prompt, schema)

      override def isAvailable: UIO[Boolean] =
        build(defaultConfig).isAvailable
    }
  }
```

**Step 2: Verify compilation**

Run: `sbt llm4zio/compile`
Expected: SUCCESS

**Step 3: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/LlmService.scala
git commit -m "feat: add LlmService.fromConfig factory layer"
```

---

## Task 12: Tool Calling Infrastructure

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/tools/Tool.scala`
- Create: `llm4zio/src/main/scala/llm4zio/tools/ToolRegistry.scala`
- Test: `llm4zio/src/test/scala/llm4zio/tools/ToolRegistrySpec.scala`

**Step 1: Write failing test for tool registry**

Create `llm4zio/src/test/scala/llm4zio/tools/ToolRegistrySpec.scala`:

```scala
package llm4zio.tools

import zio.*
import zio.test.*
import zio.json.*
import zio.json.ast.Json
import llm4zio.core.*

object ToolRegistrySpec extends ZIOSpecDefault:
  def spec = suite("ToolRegistry")(
    test("register and get tool") {
      val tool = Tool(
        name = "greet",
        description = "Greet a user",
        parameters = Json.Obj(),
        execute = (args: Json) => ZIO.succeed("Hello!")
      )

      for {
        registry <- ToolRegistry.make
        _        <- registry.register(tool)
        retrieved <- registry.get("greet")
      } yield assertTrue(retrieved.name == "greet")
    },
    test("list registered tools") {
      val tool1 = Tool("tool1", "desc1", Json.Obj(), (_: Json) => ZIO.succeed("result1"))
      val tool2 = Tool("tool2", "desc2", Json.Obj(), (_: Json) => ZIO.succeed("result2"))

      for {
        registry <- ToolRegistry.make
        _        <- registry.register(tool1)
        _        <- registry.register(tool2)
        tools    <- registry.list
      } yield assertTrue(tools.toSet == Set("tool1", "tool2"))
    },
    test("execute tool call") {
      val tool = Tool(
        name = "add",
        description = "Add two numbers",
        parameters = Json.Obj(),
        execute = (args: Json) => ZIO.succeed(42)
      )
      val call = ToolCall(id = "1", name = "add", arguments = Json.Obj("a" -> Json.Num(1), "b" -> Json.Num(2)))

      for {
        registry <- ToolRegistry.make
        _        <- registry.register(tool)
        result   <- registry.execute(call)
      } yield assertTrue(result.toolCallId == "1", result.result.isRight)
    },
    test("fail when tool not found") {
      for {
        registry <- ToolRegistry.make
        result   <- registry.get("nonexistent").either
      } yield assertTrue(result.isLeft)
    }
  )
```

**Step 2: Run test to verify it fails**

Run: `sbt llm4zio/test`
Expected: FAIL with "object Tool is not a member of package llm4zio.tools"

**Step 3: Implement Tool types**

Create `llm4zio/src/main/scala/llm4zio/tools/Tool.scala`:

```scala
package llm4zio.tools

import zio.*
import zio.json.*
import zio.json.ast.Json
import llm4zio.core.LlmError

case class Tool[R, E, A](
  name: String,
  description: String,
  parameters: Json,
  execute: Json => ZIO[R, E, A],
)

case class ToolResult(
  toolCallId: String,
  result: Either[String, Json],
) derives JsonCodec

type JsonSchema = Json
```

**Step 4: Implement ToolRegistry**

Create `llm4zio/src/main/scala/llm4zio/tools/ToolRegistry.scala`:

```scala
package llm4zio.tools

import zio.*
import zio.json.*
import zio.json.ast.Json
import llm4zio.core.*

trait ToolRegistry:
  def register[R, E, A](tool: Tool[R, E, A]): UIO[Unit]
  def get(name: String): IO[LlmError, Tool[Any, Any, Any]]
  def list: UIO[List[String]]
  def execute(call: ToolCall): IO[LlmError, ToolResult]

object ToolRegistry:
  def make: UIO[ToolRegistry] =
    for {
      tools <- Ref.make(Map.empty[String, Tool[Any, Any, Any]])
    } yield new ToolRegistry {
      override def register[R, E, A](tool: Tool[R, E, A]): UIO[Unit] =
        tools.update(_ + (tool.name -> tool.asInstanceOf[Tool[Any, Any, Any]]))

      override def get(name: String): IO[LlmError, Tool[Any, Any, Any]] =
        tools.get.flatMap { toolMap =>
          ZIO.fromOption(toolMap.get(name))
            .mapError(_ => LlmError.ToolError(name, s"Tool not found: $name"))
        }

      override def list: UIO[List[String]] =
        tools.get.map(_.keys.toList)

      override def execute(call: ToolCall): IO[LlmError, ToolResult] =
        for {
          tool <- get(call.name)
          result <- tool.execute(call.arguments.fromJson[Json].getOrElse(Json.Obj()))
            .mapBoth(
              err => LlmError.ToolError(call.name, err.toString),
              res => Json.Str(res.toString) // Convert any result to JSON
            )
            .either
        } yield ToolResult(
          toolCallId = call.id,
          result = result.left.map(_.toString)
        )
    }

  val layer: ULayer[ToolRegistry] = ZLayer.fromZIO(make)
```

**Step 5: Update LlmService to use real Tool type**

Modify `llm4zio/src/main/scala/llm4zio/core/LlmService.scala`:

Remove the placeholder `type Tool = Any` and add:

```scala
import llm4zio.tools.Tool
```

**Step 6: Run tests to verify they pass**

Run: `sbt llm4zio/test`
Expected: PASS

**Step 7: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/tools/
git add llm4zio/src/test/scala/llm4zio/tools/
git add llm4zio/src/main/scala/llm4zio/core/LlmService.scala
git commit -m "feat: add tool calling infrastructure"
```

---

## Task 13: Observability - Metrics

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/observability/LlmMetrics.scala`
- Test: `llm4zio/src/test/scala/llm4zio/observability/LlmMetricsSpec.scala`

**Step 1: Write failing test for metrics**

Create `llm4zio/src/test/scala/llm4zio/observability/LlmMetricsSpec.scala`:

```scala
package llm4zio.observability

import zio.*
import zio.test.*
import llm4zio.core.TokenUsage

object LlmMetricsSpec extends ZIOSpecDefault:
  def spec = suite("LlmMetrics")(
    test("record request increments count") {
      for {
        metrics  <- ZIO.service[LlmMetrics]
        _        <- metrics.recordRequest
        _        <- metrics.recordRequest
        snapshot <- metrics.snapshot
      } yield assertTrue(snapshot.requests == 2)
    }.provide(LlmMetrics.layer),
    test("record tokens tracks total") {
      val usage = TokenUsage(prompt = 10, completion = 5, total = 15)
      for {
        metrics  <- ZIO.service[LlmMetrics]
        _        <- metrics.recordTokens(usage)
        _        <- metrics.recordTokens(usage)
        snapshot <- metrics.snapshot
      } yield assertTrue(snapshot.totalTokens == 30)
    }.provide(LlmMetrics.layer),
    test("record latency tracks total") {
      for {
        metrics  <- ZIO.service[LlmMetrics]
        _        <- metrics.recordLatency(100)
        _        <- metrics.recordLatency(200)
        snapshot <- metrics.snapshot
      } yield assertTrue(snapshot.totalLatencyMs == 300)
    }.provide(LlmMetrics.layer),
    test("calculate average latency") {
      for {
        metrics  <- ZIO.service[LlmMetrics]
        _        <- metrics.recordRequest
        _        <- metrics.recordLatency(100)
        _        <- metrics.recordRequest
        _        <- metrics.recordLatency(200)
        snapshot <- metrics.snapshot
      } yield assertTrue(snapshot.avgLatencyMs == 150.0)
    }.provide(LlmMetrics.layer),
    test("record errors increments count") {
      for {
        metrics  <- ZIO.service[LlmMetrics]
        _        <- metrics.recordError
        _        <- metrics.recordError
        snapshot <- metrics.snapshot
      } yield assertTrue(snapshot.errors == 2)
    }.provide(LlmMetrics.layer)
  )
```

**Step 2: Run test to verify it fails**

Run: `sbt llm4zio/test`
Expected: FAIL

**Step 3: Implement LlmMetrics**

Create `llm4zio/src/main/scala/llm4zio/observability/LlmMetrics.scala`:

```scala
package llm4zio.observability

import zio.*
import llm4zio.core.TokenUsage

case class LlmMetrics(
  requestCount: Ref[Long],
  totalTokens: Ref[Long],
  totalLatencyMs: Ref[Long],
  errorCount: Ref[Long],
):
  def recordRequest: UIO[Unit] = requestCount.update(_ + 1)

  def recordTokens(usage: TokenUsage): UIO[Unit] =
    totalTokens.update(_ + usage.total)

  def recordLatency(ms: Long): UIO[Unit] =
    totalLatencyMs.update(_ + ms)

  def recordError: UIO[Unit] = errorCount.update(_ + 1)

  def snapshot: UIO[MetricsSnapshot] =
    for {
      requests <- requestCount.get
      tokens   <- totalTokens.get
      latency  <- totalLatencyMs.get
      errors   <- errorCount.get
    } yield MetricsSnapshot(requests, tokens, latency, errors)

case class MetricsSnapshot(
  requests: Long,
  totalTokens: Long,
  totalLatencyMs: Long,
  errors: Long,
):
  def avgLatencyMs: Double =
    if (requests == 0) 0.0 else totalLatencyMs.toDouble / requests

object LlmMetrics:
  val layer: ULayer[LlmMetrics] = ZLayer.fromZIO {
    for {
      requestCount   <- Ref.make(0L)
      totalTokens    <- Ref.make(0L)
      totalLatencyMs <- Ref.make(0L)
      errorCount     <- Ref.make(0L)
    } yield LlmMetrics(requestCount, totalTokens, totalLatencyMs, errorCount)
  }
```

**Step 4: Run tests to verify they pass**

Run: `sbt llm4zio/test`
Expected: PASS

**Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/observability/LlmMetrics.scala
git add llm4zio/src/test/scala/llm4zio/observability/LlmMetricsSpec.scala
git commit -m "feat: add LLM metrics tracking"
```

---

## Task 14: Observability - Logging Aspect

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/observability/LlmLogger.scala`

**Step 1: Implement logging wrapper**

Create `llm4zio/src/main/scala/llm4zio/observability/LlmLogger.scala`:

```scala
package llm4zio.observability

import zio.*
import zio.logging.*
import llm4zio.core.*

object LlmLogger:
  // Aspect that adds logging to LlmService operations
  def logged(service: LlmService): LlmService = new LlmService:
    override def execute(prompt: String): IO[LlmError, LlmResponse] =
      for {
        _      <- ZIO.logInfo("LLM request") @@ LogAnnotation("prompt_length", prompt.length.toString)
        start  <- Clock.nanoTime
        result <- service.execute(prompt)
        end    <- Clock.nanoTime
        duration = (end - start) / 1_000_000 // ms
        _ <- ZIO.logInfo("LLM response") @@
             LogAnnotation("duration_ms", duration.toString) @@
             LogAnnotation("output_length", result.content.length.toString) @@
             LogAnnotation("tokens", result.usage.map(_.total.toString).getOrElse("unknown"))
      } yield result

    override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
      ZStream.logInfo("LLM stream request") *>
        service.executeStream(prompt).tap { chunk =>
          if chunk.finishReason.isDefined then
            ZIO.logInfo("LLM stream complete") @@
              LogAnnotation("finish_reason", chunk.finishReason.get)
          else ZIO.unit
        }

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      for {
        _ <- ZIO.logInfo("LLM history request") @@
             LogAnnotation("message_count", messages.length.toString)
        result <- service.executeWithHistory(messages)
        _ <- ZIO.logInfo("LLM history response") @@
             LogAnnotation("output_length", result.content.length.toString)
      } yield result

    override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
      ZStream.logInfo("LLM stream history request") @@
        LogAnnotation("message_count", messages.length.toString) *>
        service.executeStreamWithHistory(messages)

    override def executeWithTools(prompt: String, tools: List[Tool]): IO[LlmError, ToolCallResponse] =
      for {
        _ <- ZIO.logInfo("LLM tool request") @@
             LogAnnotation("tool_count", tools.length.toString)
        result <- service.executeWithTools(prompt, tools)
        _ <- ZIO.logInfo("LLM tool response") @@
             LogAnnotation("tool_calls", result.toolCalls.length.toString)
      } yield result

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      for {
        _      <- ZIO.logInfo("LLM structured request")
        result <- service.executeStructured(prompt, schema)
        _      <- ZIO.logInfo("LLM structured response")
      } yield result

    override def isAvailable: UIO[Boolean] =
      service.isAvailable
```

**Step 2: Verify compilation**

Run: `sbt llm4zio/compile`
Expected: SUCCESS

**Step 3: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/observability/LlmLogger.scala
git commit -m "feat: add LLM logging aspect"
```

---

## Task 15: Update Root Project - Agents Package

**Files:**
- Modify: `src/main/scala/agents/*.scala`

**Step 1: Update imports in agent files**

Run this command to update all agent files:

```bash
find src/main/scala/agents -name "*.scala" -exec sed -i '' 's/import core\.AIService/import llm4zio.core.LlmService/g' {} \;
find src/main/scala/agents -name "*.scala" -exec sed -i '' 's/import core\.\*/import llm4zio.core.\*/g' {} \;
find src/main/scala/agents -name "*.scala" -exec sed -i '' 's/import models\.AIProvider/import llm4zio.core.LlmProvider/g' {} \;
find src/main/scala/agents -name "*.scala" -exec sed -i '' 's/import models\.AIResponse/import llm4zio.core.LlmResponse/g' {} \;
find src/main/scala/agents -name "*.scala" -exec sed -i '' 's/import models\.AIError/import llm4zio.core.LlmError/g' {} \;
find src/main/scala/agents -name "*.scala" -exec sed -i '' 's/AIService/LlmService/g' {} \;
find src/main/scala/agents -name "*.scala" -exec sed -i '' 's/AIResponse/LlmResponse/g' {} \;
find src/main/scala/agents -name "*.scala" -exec sed -i '' 's/AIError/LlmError/g' {} \;
```

**Step 2: Manually review and fix any remaining issues**

Check each agent file and fix:
- Type conversions
- Method calls that may have changed
- Any AIService-specific logic

**Step 3: Verify compilation**

Run: `sbt compile`
Expected: SUCCESS (or specific errors to fix)

**Step 4: Fix compilation errors if any**

Address any compilation errors one by one.

**Step 5: Commit**

```bash
git add src/main/scala/agents/
git commit -m "refactor: update agents to use llm4zio"
```

---

## Task 16: Update Root Project - Other Packages

**Files:**
- Modify: `src/main/scala/orchestration/*.scala`
- Modify: `src/main/scala/prompts/*.scala`
- Modify: `src/main/scala/web/*.scala`
- Modify: `src/main/scala/di/*.scala`
- Modify: `src/main/scala/Main.scala`

**Step 1: Update orchestration package**

```bash
find src/main/scala/orchestration -name "*.scala" -exec sed -i '' 's/import core\.AIService/import llm4zio.core.LlmService/g' {} \;
find src/main/scala/orchestration -name "*.scala" -exec sed -i '' 's/AIService/LlmService/g' {} \;
find src/main/scala/orchestration -name "*.scala" -exec sed -i '' 's/AIResponse/LlmResponse/g' {} \;
find src/main/scala/orchestration -name "*.scala" -exec sed -i '' 's/AIError/LlmError/g' {} \;
```

**Step 2: Update prompts package**

```bash
find src/main/scala/prompts -name "*.scala" -exec sed -i '' 's/import core\.AIService/import llm4zio.core.LlmService/g' {} \;
find src/main/scala/prompts -name "*.scala" -exec sed -i '' 's/AIService/LlmService/g' {} \;
```

**Step 3: Update web package**

```bash
find src/main/scala/web -name "*.scala" -exec sed -i '' 's/import core\.AIService/import llm4zio.core.LlmService/g' {} \;
find src/main/scala/web -name "*.scala" -exec sed -i '' 's/AIService/LlmService/g' {} \;
```

**Step 4: Update dependency injection**

```bash
find src/main/scala/di -name "*.scala" -exec sed -i '' 's/import core\.AIService/import llm4zio.core.LlmService/g' {} \;
find src/main/scala/di -name "*.scala" -exec sed -i '' 's/import models\.AIProviderConfig/import llm4zio.core.LlmConfig/g' {} \;
find src/main/scala/di -name "*.scala" -exec sed -i '' 's/AIService/LlmService/g' {} \;
find src/main/scala/di -name "*.scala" -exec sed -i '' 's/AIProviderConfig/LlmConfig/g' {} \;
```

**Step 5: Update Main.scala**

Manually update `src/main/scala/Main.scala` to import from llm4zio packages.

**Step 6: Verify compilation**

Run: `sbt compile`
Expected: SUCCESS

**Step 7: Commit**

```bash
git add src/main/scala/orchestration/ src/main/scala/prompts/ src/main/scala/web/ src/main/scala/di/ src/main/scala/Main.scala
git commit -m "refactor: update all packages to use llm4zio"
```

---

## Task 17: Update Configuration Models

**Files:**
- Modify: `src/main/scala/config/*.scala`

**Step 1: Update config files to use LlmConfig**

```bash
find src/main/scala/config -name "*.scala" -exec sed -i '' 's/import models\.AIProviderConfig/import llm4zio.core.LlmConfig/g' {} \;
find src/main/scala/config -name "*.scala" -exec sed -i '' 's/import models\.AIProvider/import llm4zio.core.LlmProvider/g' {} \;
find src/main/scala/config -name "*.scala" -exec sed -i '' 's/AIProviderConfig/LlmConfig/g' {} \;
find src/main/scala/config -name "*.scala" -exec sed -i '' 's/AIProvider/LlmProvider/g' {} \;
```

**Step 2: Verify compilation**

Run: `sbt compile`
Expected: SUCCESS

**Step 3: Commit**

```bash
git add src/main/scala/config/
git commit -m "refactor: update config to use llm4zio types"
```

---

## Task 18: Update Tests

**Files:**
- Modify: `src/test/scala/**/*.scala`

**Step 1: Update all test imports**

```bash
find src/test/scala -name "*.scala" -exec sed -i '' 's/import core\.AIService/import llm4zio.core.LlmService/g' {} \;
find src/test/scala -name "*.scala" -exec sed -i '' 's/import models\.AIResponse/import llm4zio.core.LlmResponse/g' {} \;
find src/test/scala -name "*.scala" -exec sed -i '' 's/import models\.AIError/import llm4zio.core.LlmError/g' {} \;
find src/test/scala -name "*.scala" -exec sed -i '' 's/AIService/LlmService/g' {} \;
find src/test/scala -name "*.scala" -exec sed -i '' 's/AIResponse/LlmResponse/g' {} \;
find src/test/scala -name "*.scala" -exec sed -i '' 's/AIError/LlmError/g' {} \;
```

**Step 2: Run tests and fix failures**

Run: `sbt test`
Expected: PASS (or identify specific failures to fix)

**Step 3: Fix any test failures**

Address test failures one by one.

**Step 4: Commit**

```bash
git add src/test/
git commit -m "test: update tests to use llm4zio types"
```

---

## Task 19: Delete Old Core Files

**Files:**
- Delete: `src/main/scala/core/AIService.scala`
- Delete: `src/main/scala/core/*AIService.scala`
- Delete: `src/main/scala/core/HttpAIClient.scala`
- Delete: `src/main/scala/core/RateLimiter.scala`
- Delete: `src/main/scala/core/ResponseParser.scala`
- Delete: `src/main/scala/core/RetryPolicy.scala`
- Delete: `src/main/scala/models/AIModels.scala`
- Delete: `src/main/scala/models/ProviderModels.scala`

**Step 1: Verify llm4zio module has all functionality**

Run: `sbt llm4zio/compile && sbt llm4zio/test`
Expected: SUCCESS

**Step 2: Verify root project compiles without old files**

Run: `sbt compile`
Expected: SUCCESS

**Step 3: Delete old AI service files**

```bash
rm src/main/scala/core/AIService.scala
rm src/main/scala/core/GeminiCliAIService.scala
rm src/main/scala/core/GeminiApiAIService.scala
rm src/main/scala/core/OpenAICompatAIService.scala
rm src/main/scala/core/AnthropicCompatAIService.scala
rm src/main/scala/core/HttpAIClient.scala
rm src/main/scala/core/RateLimiter.scala
rm src/main/scala/core/ResponseParser.scala
rm src/main/scala/core/RetryPolicy.scala
rm src/main/scala/core/GeminiService.scala
```

**Step 4: Delete old model files**

```bash
rm src/main/scala/models/AIModels.scala
rm src/main/scala/models/ProviderModels.scala
```

**Step 5: Delete old test files**

```bash
rm -f src/test/scala/core/GeminiCliAIServiceSpec.scala
rm -f src/test/scala/core/GeminiApiAIServiceSpec.scala
rm -f src/test/scala/core/OpenAICompatAIServiceSpec.scala
```

**Step 6: Verify everything still compiles and tests pass**

Run: `sbt compile && sbt test`
Expected: SUCCESS

**Step 7: Commit**

```bash
git add -A
git commit -m "refactor: remove old AIService and provider files"
```

---

## Task 20: Format and Verify

**Files:**
- All modified files

**Step 1: Run formatter**

Run: `sbt fmt`
Expected: All files formatted

**Step 2: Run style checker**

Run: `sbt check`
Expected: PASS

**Step 3: Run all tests**

Run: `sbt test`
Expected: PASS

**Step 4: Run integration tests**

Run: `sbt it:test`
Expected: PASS

**Step 5: Verify build**

Run: `sbt clean compile test`
Expected: SUCCESS

**Step 6: Commit formatting changes**

```bash
git add -A
git commit -m "style: format code with scalafmt and scalafix"
```

---

## Task 21: Update Documentation

**Files:**
- Create: `llm4zio/README.md`
- Modify: `README.md` (root)

**Step 1: Create llm4zio README**

Create `llm4zio/README.md`:

```markdown
# llm4zio

ZIO-native LLM framework for building AI-powered applications.

## Features

- **Pure ZIO**: Full ZIO effects and ZLayer composition
- **Streaming**: Real-time token streaming via ZStream
- **Conversation**: Multi-turn conversations with history
- **Tool Calling**: Semi-automated tool execution framework
- **Observability**: Built-in metrics and structured logging
- **Type Safety**: Scala 3 enums, opaque types, derives

## Supported Providers

- Gemini (CLI & API)
- OpenAI
- Anthropic

## Usage

```scala
import llm4zio.core.*
import zio.*

val program = for {
  response <- LlmService.execute("Hello, world!")
  _ <- Console.printLine(response.content)
} yield ()

// Provide dependencies
program.provide(
  LlmService.fromConfig,
  ZLayer.succeed(LlmConfig(
    provider = LlmProvider.GeminiCli,
    model = "gemini-2.5-flash"
  )),
  RateLimiter.layer,
  HttpClient.layer
)
```

## Architecture

- `core/` - LlmService trait, models, errors, utilities
- `providers/` - Provider implementations (Gemini, OpenAI, Anthropic)
- `tools/` - Tool calling infrastructure
- `observability/` - Metrics and logging
- `agents/` - Agent framework (future)

## Testing

```bash
sbt llm4zio/test
```
```

**Step 2: Update root README**

Add to `README.md` in the root:

```markdown
## Module Structure

- **llm4zio** - ZIO-native LLM framework
- **root** - Legacy modernization agent (depends on llm4zio)
```

**Step 3: Commit**

```bash
git add llm4zio/README.md README.md
git commit -m "docs: add llm4zio README and update root README"
```

---

## Final Verification

**Step 1: Full clean build**

Run: `sbt clean compile test`
Expected: SUCCESS

**Step 2: Check coverage (if applicable)**

Run: `sbt coverage test coverageReport`
Expected: Coverage report generated

**Step 3: Verify checklist**

- [x] `sbt compile` succeeds
- [x] `sbt test` passes
- [x] `sbt fmt` applied
- [x] `sbt check` passes
- [x] All existing agent functionality works with new LlmService

**Step 4: Final commit if needed**

```bash
git add -A
git commit -m "feat: complete llm4zio module migration"
```

---

## Summary

This plan implements the llm4zio module structure with:

1. ✅ Clean module structure with proper package organization
2. ✅ Core models and error types with JSON codecs
3. ✅ Enhanced LlmService trait with streaming, conversation, and tools
4. ✅ Migrated all provider implementations
5. ✅ Tool calling infrastructure with registry
6. ✅ Observability with metrics and logging
7. ✅ Big bang migration updating all imports
8. ✅ Removed old AIService code
9. ✅ All tests passing
10. ✅ Proper documentation

**Next Steps:**
- Implement streaming for HTTP providers (SSE parsing)
- Add structured output support to providers
- Enhance tool calling with automatic execution loops
- Add RAG support (embeddings, vector search)
