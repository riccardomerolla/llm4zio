# llm4zio

[![Maven Central](https://img.shields.io/maven-central/v/io.github.riccardomerolla/llm4zio.svg)](https://mvnrepository.com/artifact/io.github.riccardomerolla/llm4zio)
[![Test Coverage](https://img.shields.io/badge/coverage-417+-green)]()

A purely functional, ZIO-native LLM integration library for Scala 3. 

Built from the ground up for **Effect-Oriented Programming**, `llm4zio` provides type-safe, resource-safe, and composable abstractions for interacting with Large Language Models (OpenAI, Anthropic, Gemini, and local providers).

## ⚡ Why llm4zio?

- **ZIO Native**: Everything is a `ZIO[R, E, A]`. No hidden side effects, no thrown exceptions.
- **Typed Errors**: Exhaustive pattern matching on API failures, rate limits, and context length issues via `LLMError`.
- **Streaming First**: Backpressured token streaming using `ZStream`.
- **Resource Safe**: Connection pooling and HTTP client lifecycles managed automatically via `Scope` and `ZLayer`.
- **Resilient**: Built-in support for exponential backoff, circuit breakers, and token-bucket rate limiting.

## 📦 Installation

```scala
libraryDependencies += "io.github.riccardomerolla" %% "llm4zio" % "1.0.3"
```

## 🚀 Quick Start

```scala
import zio._
import zio.stream._
import io.github.riccardomerolla.llm4zio._

val program: ZIO[LLMService, LLMError, Unit] = for {
  // 1. Simple Completion
  response <- ZIO.serviceWithZIO[LLMService](_.complete(
    ChatRequest(
      messages = List(ChatMessage.user("What is ZIO?")),
      model = "gpt-4o"
    )
  ))
  _ <- Console.printLine(s"Response: ${response.content}")

  // 2. Streaming with Backpressure
  _ <- ZStream.serviceWithStream[LLMService](_.stream(
    ChatRequest(
      messages = List(ChatMessage.user("Write a Scala 3 macro.")),
      model = "gpt-4o"
    )
  )).foreach(chunk => Console.print(chunk))
} yield ()

// Provide the layer and run
program.provide(
  LLMService.live(LLMConfig(
    provider = "openai",
    apiKey = sys.env.get("OPENAI_API_KEY"),
    model = "gpt-4o"
  ))
)
```

## 🛡️ Typed Error Handling

Never catch `Throwable` again. Handle specific LLM failures exhaustively:

```scala
def robustCall(req: ChatRequest): ZIO[LLMService, Nothing, String] =
  ZIO.serviceWithZIO[LLMService](_.complete(req)).map(_.content).catchAll {
    case LLMError.RateLimited(retryAfter) =>
      ZIO.logWarning(s"Rate limited. Retrying in $retryAfter") *>
      ZIO.sleep(retryAfter) *> robustCall(req)
    case LLMError.ContextLengthExceeded =>
      ZIO.succeed("Prompt too long. Please truncate.")
    case LLMError.ApiError(msg, code) =>
      ZIO.logError(s"API Error $code: $msg") *> ZIO.succeed("Fallback response")
    case e: LLMError =>
      ZIO.logError(s"Unexpected LLM error: $e") *> ZIO.succeed("Error")
  }
```

## 🧪 Testing

`llm4zio` provides `TestLLMService` for deterministic, fast unit testing without hitting real APIs:

```scala
import zio.test._
import io.github.riccardomerolla.llm4zio.test._

object MyAgentSpec extends ZIOSpecDefault {
  def spec = suite("MyAgent")(
    test("handles responses") {
      for {
        _ <- TestLLMService.setResponse("Mocked LLM response")
        res <- MyAgent.run("Hello")
      } yield assertTrue(res == "Mocked LLM response")
    }
  ).provide(TestLLMService.mock)
}
```

## 🔗 Ecosystem

`llm4zio` is the foundational library for the broader LLM4ZIO ecosystem:

- **llm4zio-gateway**: A production-grade HTTP proxy for unified LLM access. Features intelligent routing, SSE streaming, account-level rate limiting, semantic caching, and cost tracking.
- **llm-agents-orchestrator**: A comprehensive DAG-based workflow engine for building, scheduling, and monitoring complex multi-agent systems.

## 🗺️ Phase 3 Roadmap: openclaw Patterns

We are currently implementing advanced orchestration patterns inspired by [openclaw](https://openclaw.ai):

- **Orchestrator Control Plane** (Centralized coordination)
- **Run-Based Workspace Isolation** (Safe parallel execution)
- **Enhanced Agent Registry** (Dynamic capability routing)
- **Workflow Progress Streaming** (Real-time UX via SSE)
- **Dynamic Workflow Composition** (DAG execution)

*See [ADR-0001](docs/adr/0001-adopt-openclaw-patterns.md) for architectural details.*

## 📚 Documentation

- [API Reference](docs/api-reference.md)
- [Provider Setup (OpenAI, Anthropic, Gemini, Local)](docs/providers.md)
- [Streaming Guide](docs/streaming.md)
- [Resilience & Rate Limiting](docs/resilience.md)

## 🤝 Contributing

We welcome contributions! Please ensure your code follows Scala 3 idioms, uses `ZLayer` for DI, and maintains strict typed error channels (`ZIO[R, E, A]`). See [AGENTS.md](AGENTS.md) for our ZIO coding standards.
