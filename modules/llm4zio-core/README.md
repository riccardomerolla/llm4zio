# llm4zio

A ZIO-native framework for working with Large Language Model (LLM) providers.

## Overview

`llm4zio` provides a unified, type-safe interface for interacting with multiple LLM providers (Gemini, OpenAI, Anthropic, OpenCode) with support for:

- **Streaming responses** via ZIO Streams
- **Conversation history** management
- **Tool calling** with semi-automated execution
- **Structured output** with JSON schema validation
- **Rate limiting** with token bucket algorithm
- **Retry policies** with exponential backoff
- **Observability** via metrics and structured logging

## Architecture

```
llm4zio/
├── core/                    # Core abstractions and models
│   ├── Models.scala        # Domain models (Message, LlmResponse, LlmChunk, etc.)
│   ├── Errors.scala        # Error hierarchy (LlmError)
│   ├── LlmService.scala    # Main service trait
│   ├── RateLimiter.scala   # Token bucket rate limiting
│   └── RetryPolicy.scala   # Exponential backoff retry logic
├── tools/                   # Tool calling infrastructure
│   ├── Tool.scala          # Tool definition and execution
│   └── ToolRegistry.scala  # Tool registration and management
├── observability/           # Metrics and logging
│   ├── LlmMetrics.scala    # Request/token/latency/error tracking
│   └── LlmLogger.scala     # Structured logging aspect
└── providers/               # Provider implementations (WIP)
    └── HttpClient.scala    # HTTP client for provider APIs
```

## Core Concepts

### LlmService

The main service trait providing the unified API:

```scala
trait LlmService:
  // Basic execution
  def execute(prompt: String): IO[LlmError, LlmResponse]

  // Streaming responses
  def executeStream(prompt: String): Stream[LlmError, LlmChunk]

  // Conversation with history
  def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse]
  def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]

  // Tool calling
  def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse]

  // Structured output
  def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]

  // Health check
  def isAvailable: UIO[Boolean]
```

### Domain Models

```scala
// Supported providers
enum LlmProvider:
  case GeminiCli, GeminiApi, OpenAI, Anthropic, OpenCode

// Message role in conversation
enum MessageRole:
  case System, User, Assistant, Tool

// Configuration
case class LlmConfig(
  provider: LlmProvider,
  model: String,
  baseUrl: Option[String] = None,
  apiKey: Option[String] = None,
  temperature: Double = 0.7,
  maxTokens: Option[Int] = None,
  timeout: Duration = 30.seconds
)

// Response types
case class Message(role: MessageRole, content: String)
case class LlmResponse(content: String, usage: Option[TokenUsage], metadata: Map[String, String])
case class LlmChunk(delta: String, finishReason: Option[String], usage: Option[TokenUsage])
```

### Error Handling

Comprehensive error hierarchy for different failure modes:

```scala
sealed trait LlmError extends Throwable

object LlmError:
  case class ProviderError(message: String, cause: Option[Throwable] = None)
  case class RateLimitError(retryAfter: Option[Duration] = None)
  case class AuthenticationError(message: String)
  case class InvalidRequestError(message: String)
  case class TimeoutError(duration: Duration)
  case class ParseError(message: String, raw: String)
  case class ToolError(toolName: String, message: String)
  case class ConfigError(message: String)
```

## Tool Calling

Define and execute tools with type-safe execution:

```scala
import llm4zio.tools.*

// Define a tool
val weatherTool = Tool[Any, LlmError, String](
  name = "get_weather",
  description = "Get current weather for a location",
  parameters = Json.Obj("location" -> Json.Str("string")),
  execute = (args: Json) =>
    ZIO.succeed("Sunny, 72°F")
)

// Register and use
for {
  registry <- ToolRegistry.make
  _        <- registry.register(weatherTool)
  result   <- LlmService.executeWithTools(
    "What's the weather in San Francisco?",
    List(weatherTool)
  )
} yield result
```

## Observability

### Metrics Tracking

```scala
import llm4zio.observability.LlmMetrics

for {
  metrics  <- ZIO.service[LlmMetrics]
  _        <- metrics.recordRequest
  _        <- metrics.recordTokens(TokenUsage(10, 5, 15))
  _        <- metrics.recordLatency(150)
  snapshot <- metrics.snapshot
  _        <- Console.printLine(s"Avg latency: ${snapshot.avgLatencyMs}ms")
} yield ()
```

### Structured Logging

```scala
import llm4zio.observability.LlmLogger

// Wrap service with logging
val service: LlmService = ... // your service implementation
val logged = LlmLogger.logged(service)

// All operations now log with metadata
logged.execute("Hello")
// Logs: LLM request (prompt_length=5)
// Logs: LLM response (duration_ms=123, output_length=42, tokens=100)
```

## Rate Limiting

Token bucket algorithm with configurable rates:

```scala
import llm4zio.core.{RateLimiter, RateLimiterConfig}

val config = RateLimiterConfig(
  requestsPerSecond = 10.0,
  burstSize = 20,
  timeout = 5.seconds
)

for {
  limiter <- RateLimiter.make(config)
  _       <- limiter.acquire  // Blocks if rate limit exceeded
  result  <- service.execute("prompt")
} yield result
```

## Retry Policies

Exponential backoff with jitter for transient failures:

```scala
import llm4zio.core.{RetryPolicy, RetryConfig}

val config = RetryConfig(
  maxRetries = 3,
  baseDelay = 1.second,
  maxDelay = 30.seconds,
  factor = 2.0,
  jitter = 0.1
)

val policy = RetryPolicy.fromConfig(config)

service.execute("prompt")
  .retry(policy.schedule)
```

## Testing

All core components are fully tested with ZIO Test:

```bash
# Run all llm4zio tests
sbt llm4zio/test

# Run specific test suite
sbt "llm4zio/testOnly llm4zio.core.ModelsSpec"
```

Current test coverage:
- 35+ tests passing
- Core models: 7 tests
- LlmService: 5 tests
- RateLimiter: 4 tests
- ToolRegistry: 4 tests
- LlmMetrics: 5 tests
- OpenCodeProvider: 15+ tests

## Dependencies

```scala
libraryDependencies ++= Seq(
  "dev.zio" %% "zio"         % "2.1.24",
  "dev.zio" %% "zio-streams" % "2.1.24",
  "dev.zio" %% "zio-json"    % "0.9.0",
  "dev.zio" %% "zio-http"    % "3.8.1",
  "dev.zio" %% "zio-logging" % "2.4.0",
  "dev.zio" %% "zio-test"    % "2.1.24" % Test
)
```

## Development Status

**Current Progress: 9/21 tasks (43%)**

 ✅ Completed:
- Module structure and build configuration
- Core models and error types
- Rate limiting and retry policies
- LlmService trait with ZIO accessors
- HTTP client for provider communication
- Tool calling infrastructure
- Observability (metrics + logging)
- OpenCode provider implementation

🚧 In Progress:
- Provider implementations (Gemini, OpenAI, Anthropic)
- LlmService factory layer
- Root project migration

📋 Planned:
- Provider-specific request/response models
- Streaming support for all providers
- Complete tool calling integration

## Design Decisions

1. **Pure ZIO**: No Cats dependencies, full ZIO integration
2. **Scala 3**: Enums, derives clauses, improved type inference
3. **Type Safety**: Proper error types, generic Tool definition
4. **Streaming First**: ZStream as core primitive for all providers
5. **Semi-Automated Tools**: Tool execution with path to full automation
6. **Single Module**: Simplified structure over multi-module complexity

## Contributing

This is an internal module for the ZIO Legacy Modernization Agent project. See the main project documentation for contribution guidelines.

## Related Documentation

- [Design Document](../docs/plans/2026-02-13-llm4zio-design.md)
- [Implementation Plan](../docs/plans/2026-02-13-llm4zio-implementation.md)
- [Progress Tracking](../docs/plans/2026-02-13-llm4zio-progress.md)
