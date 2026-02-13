# llm4zio Migration - Lessons Learned

## Overview

This document captures lessons learned from implementing Strategy 3 (Full LlmService Migration) with ExampleLlmAgent as a proof-of-concept.

## Key Insights

### 1. Service Access Pattern

**Challenge**: ZLayer.build returns `ZEnvironment[T]`, not `T` directly.

**Wrong**:
```scala
ExampleLlmAgent.live.build
  .provide(layers...)
  .flatMap(agent => agent.analyze(file))  // agent is ZEnvironment[ExampleLlmAgent]
```

**Correct**:
```scala
ZIO.serviceWithZIO[ExampleLlmAgent](_.analyze(file))
  .provide(
    ExampleLlmAgent.live,
    layers...
  )
```

**Streaming variant**:
```scala
ZStream.serviceWithStream[ExampleLlmAgent](_.analyzeStreaming(file))
  .provide(layers...)
```

### 2. Type Imports

**Important**: Import types from correct modules:
- `ToolCallResponse` is in `llm4zio.core`, **not** `llm4zio.tools`
- `JsonSchema` is in `llm4zio.tools`

```scala
import llm4zio.core.{LlmService, LlmError, LlmResponse, LlmChunk, Message, TokenUsage, ToolCallResponse}
import llm4zio.tools.{AnyTool, JsonSchema}
```

### 3. Model Types

The domain models use specific type names:
- `Variable` (not `CobolVariable`)
- `Procedure` (not `CobolProcedure`)
- `Statement` (not `CobolStatement`)
- `FileType.Program` (enum, not string "COBOL")
- `java.time.Instant` (not `String`)

Always check `src/main/scala/models/AnalysisModels.scala` and `DiscoveryModels.scala` for exact types.

### 4. FileService Interface Evolution

MockFileService must implement all methods:
```scala
trait FileService:
  def readFile(path: Path): ZIO[Any, FileError, String]
  def writeFile(path: Path, content: String): ZIO[Any, FileError, Unit]
  def writeFileAtomic(path: Path, content: String): ZIO[Any, FileError, Unit]
  def listFiles(directory: Path, extensions: Set[String]): ZStream[Any, FileError, Path]  // ZStream, not List!
  def copyDirectory(src: Path, dest: Path): ZIO[Any, FileError, Unit]
  def ensureDirectory(path: Path): ZIO[Any, FileError, Unit]
  def deleteRecursive(path: Path): ZIO[Any, FileError, Unit]
  def exists(path: Path): ZIO[Any, FileError, Boolean]
  def getFileSize(path: Path): ZIO[Any, FileError, Long]
  def countLines(path: Path): ZIO[Any, FileError, Long]
```

Note: `listFiles` returns `ZStream`, not `List`.

### 5. Error Testing

When testing error scenarios, check `cause.isFailure` as fallback:
```scala
case Exit.Failure(cause) =>
  val failureErrors = cause.failures.collect {
    case err: AnalysisError.AIFailed => err
  }
  val hasFailure = failureErrors.nonEmpty && /* check conditions */
  val hasDefectOrFailure = hasFailure || cause.isFailure
  assertTrue(hasDefectOrFailure)
```

Some errors might be in defects rather than failures channel.

### 6. Type-Safe Structured Responses

**Major benefit**: `executeStructured[T]` returns `T` directly:

```scala
// Old way with AIService
for
  response <- aiService.executeStructured(prompt, schema)  // AIResponse (string)
  parsed   <- parser.parse[CobolAnalysis](response)        // Manual parsing
yield parsed

// New way with LlmService
for
  analysis <- llmService.executeStructured[CobolAnalysis](prompt, schema)  // CobolAnalysis directly!
yield analysis
```

No separate ResponseParser needed!

### 7. Error Conversion Pattern

LlmError has specific variants that should map to domain errors:
```scala
private def convertError(fileName: String)(error: LlmError): AnalysisError =
  error match
    case LlmError.ProviderError(message, cause) =>
      AnalysisError.AIFailed(fileName, s"Provider error: $message...")
    case LlmError.RateLimitError(retryAfter) =>
      AnalysisError.AIFailed(fileName, s"Rate limited...")
    case LlmError.ParseError(message, raw) =>
      AnalysisError.ParseFailed(fileName, s"$message\nRaw: ${raw.take(200)}")
    // ... other variants
```

### 8. Mock Testing Pattern

MockLlmService should implement all LlmService methods:
```scala
class MockLlmService(
  structuredResponse: CobolAnalysis,
  streamingChunks: List[String] = List("Analyzing", "...", "Done"),
  shouldFail: Boolean = false
) extends LlmService:

  override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    if shouldFail then
      ZIO.fail(LlmError.ParseError("Mock parse error", "invalid json"))
    else
      ZIO.succeed(structuredResponse.asInstanceOf[A])  // Type assertion for mock

  // ... other methods
```

### 9. Streaming Implementation

Streaming is straightforward with ZStream:
```scala
override def analyzeStreaming(file: CobolFile): ZStream[Any, AnalysisError, String] =
  ZStream.unwrap {
    for
      content <- fileService.readFile(file.path)
                   .mapError(fe => AnalysisError.FileReadFailed(file.path, fe.message))
      prompt   = buildPrompt(file, content)
    yield
      llmService
        .executeStream(prompt)
        .map(_.delta)  // Extract text from LlmChunk
        .mapError(convertError(file.name))
  }
```

### 10. Conversation History

Multi-turn conversations use `Message` list:
```scala
val messages = List(
  Message(MessageRole.System, "You are an expert COBOL analyzer."),
  Message(MessageRole.User, "Previous context..."),
  Message(MessageRole.Assistant, "Previous response..."),
  Message(MessageRole.User, "Current request...")
)

llmService.executeWithHistory(messages)
```

Note: `executeWithHistory` returns `LlmResponse` (not the parsed type), so you need to parse manually:
```scala
for
  response <- llmService.executeWithHistory(messages)
  analysis <- ZIO.fromEither(response.content.fromJson[CobolAnalysis])
                .mapError(err => AnalysisError.ParseFailed(fileName, err))
yield analysis
```

## Migration Checklist

When migrating an agent to Strategy 3:

- [ ] Update ZLayer dependencies from `AIService` to `LlmService`
- [ ] Remove `ResponseParser` dependency
- [ ] Use `executeStructured[T]` instead of `execute` + `parse`
- [ ] Add error conversion function for `LlmError` → domain error
- [ ] Update test mocks to extend `LlmService`
- [ ] Use `ZIO.serviceWithZIO` for accessing agent in tests
- [ ] Use `ZStream.serviceWithStream` for streaming tests
- [ ] Verify all model types match the domain (e.g., `Variable` not `CobolVariable`)
- [ ] Verify imports (e.g., `ToolCallResponse` from `llm4zio.core`)

## Benefits Realized

1. **Type Safety**: `executeStructured[T]` eliminates separate parsing step
2. **Simpler DI**: One less dependency (no ResponseParser)
3. **Better Errors**: Specific `LlmError` variants for precise error handling
4. **New Features**: Access to streaming, tools, conversation history
5. **Cleaner Tests**: Simpler mocking (one service instead of two)

## Next Steps

1. Migrate a real agent (e.g., CobolAnalyzerAgent) using Strategy 3
2. Compare performance and code complexity with AIService version
3. Document any additional patterns discovered during real migration
4. Create migration PR template with checklist

## References

- [llm4zio Migration Guide](./llm4zio-migration-guide.md)
- [ExampleLlmAgent.scala](../../src/main/scala/agents/ExampleLlmAgent.scala)
- [ExampleLlmAgentSpec.scala](../../src/test/scala/agents/ExampleLlmAgentSpec.scala)
