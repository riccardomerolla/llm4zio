# llm4zio: ZIO-Native LLM Integration Library

**Scala 3 + ZIO 2.x Effect-Oriented Programming**

**Version:** 1.0.0
**Author:** Riccardo Merolla
**Status:** ✅ Production Ready
**Build:** [![Test Coverage](https://img.shields.io/badge/coverage-417+-green)]()

---

## 🎯 Overview

**llm4zio** is a high-performance, effect-oriented library for integrating Large Language Models (LLMs) with ZIO-powered Scala applications. Inspired by [llm4s](https://github.com/openai/llm4s), it provides type-safe, composable abstractions for LLM interactions built natively on ZIO 2.x.

At its core, llm4zio enables:

- **Multi-provider LLM support** (OpenAI, Anthropic, Gemini, local LM Studio, and custom backends)
- **Type-safe prompting** with validated schemas and structured outputs  
- **Resource-safe operations** with automatic cleanup and error recovery
- **Streaming responses** with backpressure handling via ZIO Streams
- **Concurrent request management** with rate limiting and retry strategies
- **Observability hooks** for monitoring, tracing, and cost tracking

### 🔗 Part of the Ecosystem

llm4zio is the **foundation library** for **llm-agents-orchestrator**, a comprehensive multi-purpose agents system featuring:

- **Service Connectors** — Integrate external APIs, databases, and business systems
- **Agent Management Console** — Web-based UI for defining, managing, and monitoring AI agents
- **Prompt Engineering Studio** — Version-controlled prompt templates with variable interpolation
- **Workflow Orchestration** — DAG-based workflows for complex agent interactions
- **Task Runner & Scheduler** — Execute tasks on-demand or via cron/interval schedules
- **Audit & Compliance** — Full request/response logging, version history, and policy enforcement

### 🏗️ Design Philosophy

llm4zio follows **Effect-Oriented Programming (EOP)** principles:

- Effects describe *what* should happen, separated from *execution* and *timing*
- All side effects (API calls, I/O, network) are wrapped in composable ZIO effects
- Typed error channels enable exhaustive error handling at compile time
- Resource safety is guaranteed through `ZIO.acquireRelease` and scoped lifetimes
- Concurrency and parallelism are built on safe, structured ZIO combinators

---

## 📋 Table of Contents

1. [Core Features](#-core-features)
2. [Quick Start](#-quick-start)
3. [Architecture](#-architecture)
4. [API Overview](#-api-overview)
5. [Advanced Usage](#-advanced-usage)
6. [Integrations](#-integrations)
7. [Testing](#-testing)
8. [Documentation](#-documentation)
9. [Contributing](#-contributing)

---

## ✨ Core Features

### 🚀 Multi-Provider Support

- **OpenAI** (GPT-4, GPT-4o, GPT-3.5-turbo)
- **Anthropic** (Claude 3, Claude Instant)
- **Google Gemini** (CLI and API modes)
- **OpenAI-compatible** (LM Studio, Ollama, vLLM, local deployments)
- **Custom backends** (extensible provider abstraction)

### 📦 Type-Safe Abstractions

```scala
// Typed prompts with compile-time validation
case class ChatRequest(
  messages: List[ChatMessage],
  model: String,
  temperature: Double = 0.7,
  maxTokens: Option[Int] = None
)

// Structured outputs with schema validation
case class LLMResponse[T](
  content: T,
  tokenUsage: TokenUsage,
  metadata: ResponseMetadata
)
```

### 🔄 Streaming & Async Support

- Full streaming support for long-running completions
- Backpressure handling with ZIO Streams
- Async callbacks and Future interop
- Graceful cancellation and timeout handling

### 🛡️ Reliability & Resilience

- **Retry strategies** with exponential backoff and jitter
- **Rate limiting** with token bucket and sliding window algorithms
- **Circuit breakers** for API fault tolerance
- **Timeout management** with configurable deadlines
- **Structured error handling** with typed error channels

### 📊 Observability

- Request/response logging with configurable detail levels
- Token usage tracking and cost estimation
- Execution time metrics and latency monitoring
- Custom hooks for integrating tracing systems (Jaeger, Datadog)

---

## 🚀 Quick Start

### Prerequisites

- Scala 3.3+ and sbt 1.9+
- Java 17+
- At least one LLM provider configured (OpenAI, Anthropic, Gemini, or local)

### Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "com.example" %% "llm4zio" % "1.0.0"
```

### Basic Usage

```scala
import zio._
import com.example.llm4zio._

// Define your LLM configuration
val config = LLMConfig(
  provider = "openai",
  model = "gpt-4o",
  apiKey = System.getenv("OPENAI_API_KEY"),
  temperature = 0.7,
  maxTokens = 2048
)

// Create an effect that uses the LLM
def askLLM(question: String): ZIO[LLMService, LLMError, String] =
  ZIO.serviceWithZIO[LLMService] { service =>
    service.complete(
      ChatRequest(
        messages = List(ChatMessage.user(question)),
        model = config.model
      )
    ).map(_.content)
  }

// Run it
def program: ZIO[LLMService, LLMError, Unit] =
  for {
    answer <- askLLM("What is effect-oriented programming?")
    _ <- Console.printLine(answer)
  } yield ()

// Execute with proper layer provision
program.provide(LLMService.live(config))
```

### Configuration

Create `application.conf`:

```hocon
llm {
  provider = "openai"           # openai | anthropic | gemini | openai-compat | lm-studio
  model = "gpt-4o"
  api-key = ${?OPENAI_API_KEY}
  base-url = null               # Optional: for OpenAI-compatible APIs
  temperature = 0.7
  max-tokens = 2048
  timeout = 60s
  max-retries = 3
  
  # Rate limiting (tokens per minute)
  rate-limit = 90000
  
  # Streaming configuration
  streaming = true
  chunk-size = 1024
  
  # Observability
  log-requests = true
  log-responses = false         # Don't log full responses for privacy
  track-cost = true
}
```

---

## 🏗️ Architecture
### Design Principles

**Layered Architecture:**

```
┌─────────────────────────────────────────────┐
│  User Applications & Agents Orchestrator    │
├─────────────────────────────────────────────┤
│       Public API Layer (Services)           │
│  LLMService, StreamingService, CostTracker  │
├─────────────────────────────────────────────┤
│    Provider Abstraction Layer (ZIO)         │
│  OpenAI, Anthropic, Gemini, Custom Backends │
├─────────────────────────────────────────────┤
│     Core Abstractions & Utilities           │
│  ChatRequest/Response, Token Management,    │
│  Retry Strategies, Rate Limiting            │
├─────────────────────────────────────────────┤
│       External API Integration              │
│     HTTP, Streaming, Error Handling         │
└─────────────────────────────────────────────┘
```

**Effect Composition:**

Every operation in llm4zio is a pure effect:

```scala
// Composable, testable, cancelable
def complete(req: ChatRequest): ZIO[Any, LLMError, ChatResponse]

// Retry with exponential backoff
def completeWithRetry(req: ChatRequest, policy: Schedule[Any, LLMError, Unit])
  : ZIO[Any, LLMError, ChatResponse]

// Concurrent requests with rate limiting
def batchComplete(reqs: List[ChatRequest])
  : ZIO[LLMService, LLMError, List[ChatResponse]]

// Streaming support with backpressure
def stream(req: ChatRequest)
  : ZIO[Any, LLMError, ZStream[Any, LLMError, String]]
```

### Core Components

**1. Provider-Agnostic API**
- Single interface for all LLM backends
- Type-safe configuration per provider
- Unified error handling

**2. Request/Response Models**
- `ChatMessage`: Typed conversation messages (system, user, assistant)
- `ChatRequest`: Configurable request parameters
- `ChatResponse`: Rich response with metadata
- `StreamChunk`: For incremental streaming

**3. Resilience Patterns**
- Automatic retry with exponential backoff
- Circuit breaker pattern for API failures
- Rate limiting with token bucket algorithm
- Timeout management with deadline propagation

**4. Resource Management**
- HTTP client lifecycle management
- Connection pooling with cleanup guarantees
- Graceful shutdown of ongoing requests

---

## 📡 API Overview

### Complete Chat Interaction

```scala
import zio._
import com.example.llm4zio._

def askQuestion(question: String): ZIO[LLMService, LLMError, String] =
  ZIO.serviceWithZIO[LLMService] { service =>
    val request = ChatRequest(
      messages = List(
        ChatMessage.system("You are a helpful assistant"),
        ChatMessage.user(question)
      ),
      model = "gpt-4o",
      temperature = 0.3,
      maxTokens = Some(500)
    )
    
    service.complete(request).map(_.content)
  }
```

### Streaming Responses

```scala
def streamQuestion(question: String): ZStream[LLMService, LLMError, String] =
  ZStream.serviceWithZIO[LLMService] { service =>
    service.stream(ChatRequest(
      messages = List(ChatMessage.user(question)),
      model = "gpt-4o"
    ))
  }

// Usage
val program = streamQuestion("Explain quantum computing")
  .foreach(chunk => Console.print(chunk))
```

### Batch Processing

```scala
def processBatch(questions: List[String]): ZIO[LLMService, LLMError, List[String]] =
  ZIO.serviceWithZIO[LLMService] { service =>
    val requests = questions.map(q => 
      ChatRequest(
        messages = List(ChatMessage.user(q)),
        model = "gpt-4o"
      )
    )
    
    // Process in parallel with rate limiting
    ZIO.foreachPar(requests)(service.complete(_))
      .withParallelism(4)
      .map(_.map(_.content))
  }
```

### Error Handling

```scala
def robustQuestion(question: String): ZIO[LLMService, Nothing, String] =
  askQuestion(question).catchAll {
    case LLMError.RateLimited(retryAfter) =>
      ZIO.logWarning(s"Rate limited, retrying after $retryAfter") *>
      ZIO.sleep(retryAfter) *>
      askQuestion(question)
      
    case LLMError.ContextLengthExceeded =>
      ZIO.succeed("Question too long, please be more concise")
      
    case LLMError.ApiError(msg, statusCode) =>
      ZIO.logError(s"API error [$statusCode]: $msg") *>
      ZIO.succeed("Service temporarily unavailable")
      
    case other =>
      ZIO.logError(s"Unexpected error: $other") *>
      ZIO.succeed("An unexpected error occurred")
  }
```

---

## 🔧 Advanced Usage

### Custom Provider Implementation

```scala
trait LLMProvider:
  def complete(req: ChatRequest): ZIO[Any, LLMError, ChatResponse]
  def stream(req: ChatRequest): ZStream[Any, LLMError, String]

case class CustomProviderLive(config: ProviderConfig) extends LLMProvider:
  def complete(req: ChatRequest): ZIO[Any, LLMError, ChatResponse] = ???
  
  def stream(req: ChatRequest): ZStream[Any, LLMError, String] = ???

object CustomProvider:
  val layer: ZLayer[ProviderConfig, Nothing, LLMProvider] =
    ZLayer.fromFunction(CustomProviderLive.apply)
```

### Token Counting & Cost Estimation

```scala
def estimateCost(request: ChatRequest): ZIO[LLMService & CostTracker, LLMError, Cost] =
  ZIO.serviceWithZIO[LLMService] { service =>
    ZIO.serviceWithZIO[CostTracker] { tracker =>
      for {
        estimatedTokens <- service.estimateTokens(request)
        cost <- tracker.calculateCost(
          model = request.model,
          inputTokens = estimatedTokens.input,
          outputTokens = estimatedTokens.estimated
        )
      } yield cost
    }
  }
```

### Observability & Logging

```scala
val config = LLMConfig(
  provider = "openai",
  model = "gpt-4o",
  observability = ObservabilityConfig(
    logRequests = true,
    logResponses = true,
    logTokenUsage = true,
    tracingEnabled = true,
    customHooks = List(
      RequestHook { req => 
        ZIO.logInfo(s"Sending request with ${req.messages.length} messages")
      },
      ResponseHook { resp =>
        ZIO.logInfo(s"Received response using ${resp.tokenUsage.totalTokens} tokens")
      }
    )
  )
)
```

---

## 🔌 Integrations

### With llm-agents-orchestrator

llm4zio is the foundation for the agents orchestrator system. Use it within agent definitions:

```scala
// Define an AI-powered agent
object ResearchAgent:
  def research(topic: String): ZIO[LLMService, LLMError, ResearchResult] =
    ZIO.serviceWithZIO[LLMService] { service =>
      for {
        // Query LLM for information
        info <- service.complete(
          ChatRequest(
            messages = List(
              ChatMessage.system("You are a research assistant"),
              ChatMessage.user(s"Research this topic: $topic")
            ),
            model = "gpt-4o"
          )
        ).map(_.content)
        
        // Process and structure results
        result <- parseResearchResult(info)
      } yield result
    }
```

### With Workflow Engines

```scala
// Use in workflow definitions
def workflowStep(context: WorkflowContext): ZIO[LLMService, LLMError, WorkflowResult] =
  for {
    llmInput <- prepareInput(context)
    llmOutput <- askQuestion(llmInput)  // Uses llm4zio
    result <- processOutput(llmOutput)
  } yield result
```

### With External Services

```scala
case class EnrichedResponse(
  llmContent: String,
  dbData: String,
  apiResult: String
)

def enrichResponse(question: String): ZIO[LLMService & DatabaseService & HttpClient, Error, EnrichedResponse] =
  for {
    llmAnswer <- askQuestion(question)      // llm4zio
    dbData <- queryDatabase(question)       // external service
    apiResult <- callExternalAPI(question)  // external service
  } yield EnrichedResponse(llmAnswer, dbData, apiResult)
```

---

## 🧪 Testing

llm4zio provides test utilities for deterministic testing:

```scala
import zio.test._
import com.example.llm4zio.test._

object LLMServiceSpec extends ZIOSpecDefault:
  def spec = suite("LLMService")(
    test("should complete chat request successfully") {
      val request = ChatRequest(
        messages = List(ChatMessage.user("Hello")),
        model = "test-model"
      )
      
      for {
        response <- ZIO.serviceWithZIO[LLMService](_.complete(request))
      } yield assertTrue(response.content.nonEmpty)
    },
    
    test("should handle rate limiting with retry") {
      val request = ChatRequest(
        messages = List(ChatMessage.user("Hello")),
        model = "test-model"
      )
      
      // Use test mock that simulates rate limiting then success
      for {
        response <- ZIO.serviceWithZIO[LLMService](
          _.complete(request)
            .retry(Schedule.limitedRetries(1))
        )
      } yield assertTrue(response.content.nonEmpty)
    }
  ).provide(
    TestLLMService.mock,  // Mock implementation
    TestClock.default
  )
```

---

## 📚 Documentation

For comprehensive documentation, see:

- **[API Reference](docs/api-reference.md)** - Detailed API documentation
- **[Provider Configuration](docs/providers.md)** - Setup for each LLM provider
- **[Error Handling Guide](docs/error-handling.md)** - Error types and recovery strategies
- **[Architecture Decision Records](docs/adr/)** - Design decisions and rationale
- **[Examples](examples/)** - Complete working examples

Key guides:
- [OpenAI Integration](docs/providers/openai.md)
- [Anthropic Integration](docs/providers/anthropic.md)
- [Local LM Studio Setup](docs/providers/lm-studio.md)
- [Custom Provider Development](docs/providers/custom.md)
- [Streaming Guide](docs/streaming.md)
- [Rate Limiting & Resilience](docs/resilience.md)

---

## 🤝 Contributing

Contributions are welcome! Please ensure:

1. Code follows Scala 3 idioms and ZIO best practices
2. All effects are properly typed with `R`, `E`, `A`
3. Comprehensive tests using ZIO Test cover new functionality
4. Documentation is updated for public API changes
5. Error handling is explicit and typed

Key files:
- [/AGENTS.md](/AGENTS.md) - Detailed ZIO & Scala 3 guidelines
- [/copilot-instructions.md](/.github/copilot-instructions.md) - AI assistant guidelines

---

## 📊 Project Structure

```
llm4zio/
├── docs/
│   ├── adr/                    # Architecture Decision Records
│   ├── providers/              # LLM Provider setup guides
│   ├── api-reference.md        # API documentation
│   ├── streaming.md            # Streaming guide
│   ├── resilience.md           # Retry & rate limiting
│   └── error-handling.md       # Error types & recovery
├── examples/                   # Complete working examples
│   ├── simple-chat.scala
│   ├── streaming-response.scala
│   ├── batch-processing.scala
│   └── custom-provider.scala
├── src/
│   ├── main/scala/
│   │   ├── com/example/llm4zio/
│   │   │   ├── api/            # Public API (ChatRequest, ChatResponse, etc.)
│   │   │   ├── providers/      # LLM provider implementations
│   │   │   ├── core/           # Core abstractions (retry, rate limit, errors)
│   │   │   ├── observability/  # Logging, tracing, cost tracking
│   │   │   └── config/         # Configuration management
│   │   └── resources/
│   │       └── application.conf
│   └── test/scala/
│       └── com/example/llm4zio/
│           ├── api/            # API tests
│           ├── providers/      # Provider tests
│           ├── integration/    # Integration tests
│           └── mock/           # Test doubles & mocks
├── build.sbt
├── AGENTS.md
├── README.md
└── LICENSE
```

---

## 🚀 Build & Test

```bash
# Build
sbt compile

# Test
sbt test

# Test with coverage
sbt coverage test coverageReport

# Publish locally
sbt publishLocal

# Run examples
sbt "runMain examples.SimpleChatExample"
sbt "runMain examples.StreamingExample"
sbt "runMain examples.BatchProcessingExample"
```

---

## 📜 License

[Specify your license here]

## 🔗 Related Projects

- **[llm4zio](https://github.com/riccardomerolla/llm4zio)** — Multi-purpose agents system built on llm4zio
- **[llm4s](https://github.com/openai/llm4s)** — Inspiration for the design
- **[ZIO](https://zio.dev/)** — Effect-oriented programming for Scala

## 📧 Support

For issues, questions, or feature requests, please [open a GitHub issue](https://github.com/riccardomerolla/llm4zio/issues).
