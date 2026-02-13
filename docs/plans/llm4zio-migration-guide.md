# llm4zio Migration Guide

This guide shows how to migrate existing code from the old `AIService` to the new `llm4zio` framework.

## Migration Strategies

### Strategy 1: Use Bridge Layer (Zero Changes)

The AIServiceBridge allows existing code to work without any changes:

```scala
// Existing code continues to work
val layer = AIService.fromConfig  // Uses bridge internally

// Agents use AIService as before
def analyze(file: CobolFile): ZIO[AIService, AnalysisError, CobolAnalysis] =
  for
    prompt   <- buildPrompt(file)
    response <- AIService.execute(prompt)
                  .mapError(e => AnalysisError.AIFailed(file.name, e.message))
    // ... rest of code
  yield analysis
```

**Pros:**
- Zero code changes
- Gradual migration path
- Safe rollback

**Cons:**
- Still using old error types (AIError)
- Missing new features (streaming, tool calling)
- Extra layer of indirection

### Strategy 2: Use AIService.fromLlmService (Minimal Changes)

Migrate DI layer to use llm4zio while keeping AIService interface:

```scala
// In ApplicationDI.scala - replace old fromConfig
val aiServiceLayer: ZLayer[AIProviderConfig, Nothing, AIService] =
  ZLayer.make[AIService](
    // Create LlmConfig from AIProviderConfig
    ZLayer.fromFunction((config: AIProviderConfig) =>
      AIServiceBridge.toLlmConfig(config)
    ),
    // Provide llm4zio dependencies
    llm4zio.providers.HttpClient.live,
    ZLayer.succeed(llm4zio.providers.GeminiCliExecutor.default),
    zio.http.ZClient.default,
    // Build LlmService
    llm4zio.core.LlmService.fromConfig,
    // Wrap in bridge
    AIServiceBridge.layer
  )
```

**Pros:**
- Agents unchanged
- Using llm4zio under the hood
- Easy to test migration

**Cons:**
- Still limited to AIService interface
- Can't use new llm4zio features directly

### Strategy 3: Migrate to LlmService (Full Migration)

Update agents to use LlmService directly:

```scala
// Update agent signature
trait CobolAnalyzerAgent:
  def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis]

object CobolAnalyzerAgent:
  val live: ZLayer[llm4zio.core.LlmService & ResponseParser & FileService & MigrationConfig, Nothing, CobolAnalyzerAgent] =
    ZLayer.fromFunction {
      (
        llmService: llm4zio.core.LlmService,  // Changed from AIService
        responseParser: ResponseParser,
        fileService: FileService,
        config: MigrationConfig,
      ) =>
        new CobolAnalyzerAgent {
          override def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis] =
            for
              content <- fileService.readFile(cobolFile.path)
                           .mapError(fe => AnalysisError.FileReadFailed(cobolFile.path, fe.message))
              prompt   = buildPrompt(cobolFile, content)
              schema   = buildJsonSchema()

              // Use LlmService directly with new error types
              response <- llmService.executeStructured[CobolAnalysis](prompt, schema)
                            .mapError(e => AnalysisError.AIFailed(cobolFile.name, e.toString))

              analysis = response  // Already parsed!
            yield analysis
        }
    }
```

**Pros:**
- Access to all llm4zio features (streaming, tools, metrics)
- Better error types (LlmError)
- Type-safe structured responses
- Future-proof architecture

**Cons:**
- Requires updating agent signatures
- Need to update DI wiring
- More work upfront

## Step-by-Step Migration (Strategy 3)

### Step 1: Update Agent Trait

```scala
// Before
trait CobolAnalyzerAgent:
  def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis]

// After (same - no changes to interface)
trait CobolAnalyzerAgent:
  def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis]
```

### Step 2: Update Agent Implementation

```scala
// Before
object CobolAnalyzerAgent:
  val live: ZLayer[AIService & ResponseParser & FileService & MigrationConfig, Nothing, CobolAnalyzerAgent] =
    ZLayer.fromFunction {
      (aiService: AIService, ...) =>
        new CobolAnalyzerAgent {
          override def analyze(file: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis] =
            for
              prompt   <- buildPrompt(file)
              response <- aiService.executeStructured(prompt, schema)
                            .mapError(e => AnalysisError.AIFailed(file.name, e.message))
              parsed   <- responseParser.parse[CobolAnalysis](response)
                            .mapError(e => AnalysisError.ParseFailed(file.name, e.message))
            yield parsed
        }
    }

// After
object CobolAnalyzerAgent:
  val live: ZLayer[llm4zio.core.LlmService & ResponseParser & FileService & MigrationConfig, Nothing, CobolAnalyzerAgent] =
    ZLayer.fromFunction {
      (llmService: llm4zio.core.LlmService, ...) =>  // Changed type
        new CobolAnalyzerAgent {
          override def analyze(file: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis] =
            for
              prompt   <- buildPrompt(file)
              // LlmService.executeStructured returns the parsed type directly!
              analysis <- llmService.executeStructured[CobolAnalysis](prompt, schema)
                            .mapError(e => AnalysisError.AIFailed(file.name, e.toString))
            yield analysis
            // No need for separate parsing step!
        }
    }
```

### Step 3: Update DI Layer

```scala
// In ApplicationDI.scala

// Before
def commonLayers(config: MigrationConfig, dbPath: java.nio.file.Path): ZLayer[Any, Nothing, CommonServices] =
  ZLayer.make[CommonServices](
    // ...
    AIService.fromConfig.mapError(e => new RuntimeException(e.message)).orDie,
    CobolAnalyzerAgent.live,
    // ...
  )

// After
def commonLayers(config: MigrationConfig, dbPath: java.nio.file.Path): ZLayer[Any, Nothing, CommonServices] =
  ZLayer.make[CommonServices](
    // ...
    // Build LlmConfig from MigrationConfig
    ZLayer.fromFunction((cfg: MigrationConfig) =>
      AIServiceBridge.toLlmConfig(cfg.resolvedProviderConfig)
    ),
    // Provide llm4zio dependencies
    llm4zio.providers.HttpClient.live,
    ZLayer.succeed(llm4zio.providers.GeminiCliExecutor.default),
    httpClientLayer(config).orDie,  // Reuse existing ZClient
    // Build LlmService
    llm4zio.core.LlmService.fromConfig,
    // Agents now depend on LlmService
    CobolAnalyzerAgent.live,
    // ...
  )
```

### Step 4: Update Error Handling

```scala
// llm4zio uses specific error types
case llm4zio.core.LlmError.ProviderError(msg, cause) =>
  AnalysisError.AIFailed(file.name, msg)

case llm4zio.core.LlmError.RateLimitError(retryAfter) =>
  AnalysisError.AIFailed(file.name, s"Rate limited, retry after $retryAfter")

case llm4zio.core.LlmError.AuthenticationError(msg) =>
  AnalysisError.AIFailed(file.name, s"Auth failed: $msg")

case llm4zio.core.LlmError.ParseError(msg, raw) =>
  AnalysisError.ParseFailed(file.name, msg)
```

## New Features Available After Migration

### Streaming Responses

```scala
// Stream LLM responses for real-time feedback
def analyzeStreaming(file: CobolFile): ZStream[Any, AnalysisError, String] =
  llmService.executeStream(prompt)
    .map(_.delta)
    .mapError(e => AnalysisError.AIFailed(file.name, e.toString))
```

### Tool Calling

```scala
// Define tools the LLM can call
val readFileTool = Tool(
  name = "read_file",
  description = "Read contents of a COBOL file",
  parameters = Json.Obj(...),
  execute = (json: Json) => fileService.readFile(...)
)

// Use tools in analysis
llmService.executeWithTools(prompt, List(readFileTool))
```

### Metrics & Observability

```scala
// Wrap service with metrics
val serviceWithMetrics = llmService @@ LlmLogger.aspect("cobol-analyzer")

// Access metrics
for
  metrics  <- LlmMetrics.snapshot
  _        <- Logger.info(s"Total requests: ${metrics.requestCount}")
  _        <- Logger.info(s"Total tokens: ${metrics.totalTokens}")
  _        <- Logger.info(s"Avg latency: ${metrics.avgLatencyMs}ms")
yield ()
```

### Conversation History

```scala
// Multi-turn conversations
val messages = List(
  Message(MessageRole.System, "You are a COBOL expert"),
  Message(MessageRole.User, "Analyze this program"),
  Message(MessageRole.Assistant, "I see this is a batch processing program"),
  Message(MessageRole.User, "What are the key data structures?")
)

llmService.executeWithHistory(messages)
```

## Testing Migration

### Test Strategy

1. **Keep old tests passing** - Don't break existing tests during migration
2. **Add llm4zio-specific tests** - Test new features separately
3. **Integration tests** - Verify end-to-end with real LLM providers

### Example Test

```scala
object CobolAnalyzerAgentMigrationSpec extends ZIOSpecDefault:
  // Mock LlmService for testing
  class MockLlmService extends llm4zio.core.LlmService:
    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      // Return mock analysis
      ZIO.succeed(mockAnalysis.asInstanceOf[A])

    // ... implement other methods

  def spec = suite("CobolAnalyzerAgent with llm4zio")(
    test("should analyze COBOL file using LlmService") {
      val mockService = new MockLlmService()
      val agent = CobolAnalyzerAgent.live.build.provide(
        ZLayer.succeed(mockService),
        // ... other dependencies
      )

      for
        result <- agent.analyze(testFile)
      yield assertTrue(
        result.file.name == testFile.name,
        result.complexity.linesOfCode > 0
      )
    }
  )
```

## Migration Checklist

For each agent:

- [ ] Update agent implementation to use `llm4zio.core.LlmService`
- [ ] Replace `AIService.execute` with `llmService.execute`
- [ ] Replace `AIService.executeStructured` with `llmService.executeStructured`
- [ ] Update error handling from `AIError` to `LlmError`
- [ ] Remove ResponseParser if using executeStructured (returns parsed type)
- [ ] Update DI layer to provide LlmService instead of AIService
- [ ] Update tests to use mock LlmService
- [ ] Verify all tests pass
- [ ] Test end-to-end with real LLM provider

## Rollback Plan

If issues arise during migration:

1. **Keep bridge active** - Old code still works via AIServiceBridge
2. **Git revert specific commits** - Roll back individual agent migrations
3. **Feature flag** - Toggle between old and new implementations
4. **Gradual rollout** - Migrate one agent at a time, monitor each

## Common Issues

### Issue: Type inference fails with executeStructured

**Problem:**
```scala
llmService.executeStructured(prompt, schema)  // Can't infer type A
```

**Solution:**
```scala
llmService.executeStructured[CobolAnalysis](prompt, schema)  // Explicit type
```

### Issue: Rate limiting behavior different

**Problem:** Old RateLimiter is separate, new one is built into providers

**Solution:** llm4zio providers don't have rate limiting yet. Either:
- Keep using old AIService.fromConfig (has RateLimiter)
- Or add rate limiting aspect to LlmService

### Issue: Missing response metadata

**Problem:** Old AIResponse has metadata map, need to extract from LlmResponse

**Solution:**
```scala
val response: llm4zio.core.LlmResponse = ...
val tokens = response.usage.map(_.total).getOrElse(0)
val provider = response.metadata.get("provider")
```

## Next Steps

1. Start with **Strategy 1** (bridge) - verify everything works
2. Pick one simple agent for **Strategy 3** (full migration) as proof of concept
3. Document learnings and update this guide
4. Roll out to remaining agents incrementally
5. Monitor metrics and error rates
6. Once all agents migrated, remove bridge (Strategy 3 Phase 3)

## Questions?

See [llm4zio README](../llm4zio/README.md) for API reference and examples.
