package llm4zio.core

import zio.*
import zio.test.*
import zio.stream.ZStream
import zio.json.*

import llm4zio.tools.*
import zio.json.ast.Json

import java.time.Instant

object ContextManagementSpec extends ZIOSpecDefault:

  private def msg(
    id: String,
    role: PromptRole,
    content: String,
    tokens: Int,
    ts: Long,
    important: Boolean = false,
  ): ConversationMessage =
    ConversationMessage(
      id = id,
      role = role,
      content = content,
      timestamp = Instant.ofEpochMilli(ts),
      tokens = tokens,
      important = important,
    )

  private final class ClarificationLlmService extends LlmService:
    private val counter = new java.util.concurrent.atomic.AtomicInteger(0)

    override def execute(prompt: String): IO[LlmError, LlmResponse] =
      ZIO.attempt(counter.getAndIncrement()).orDie.flatMap { call =>
        if call == 0 then ZIO.succeed(LlmResponse("not-json"))
        else ZIO.succeed(LlmResponse("{\"value\": 42}"))
      }

    override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.fromZIO(execute(prompt).map(r => LlmChunk(r.content, finishReason = Some("stop"))))

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      execute(messages.map(_.content).mkString("\n"))

    override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.fromZIO(executeWithHistory(messages).map(r => LlmChunk(r.content, finishReason = Some("stop"))))

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.succeed(ToolCallResponse(Some("done"), Nil, "stop"))

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.InvalidRequestError("not used"))

    override def isAvailable: UIO[Boolean] = ZIO.succeed(true)

  private final class ToolLoopLlmService extends LlmService:
    private val counter = new java.util.concurrent.atomic.AtomicInteger(0)

    override def execute(prompt: String): IO[LlmError, LlmResponse] = ZIO.succeed(LlmResponse(prompt))

    override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.fromZIO(execute(prompt).map(r => LlmChunk(r.content, finishReason = Some("stop"))))

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] = execute(messages.map(_.content).mkString("\n"))

    override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.fromZIO(executeWithHistory(messages).map(r => LlmChunk(r.content, finishReason = Some("stop"))))

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.attempt(counter.getAndIncrement()).orDie.map { call =>
        if call == 0 then
          ToolCallResponse(
            content = Some("need tool"),
            toolCalls = List(ToolCall("t1", "echo", "{\"value\":\"ok\"}")),
            finishReason = "tool_calls",
          )
        else ToolCallResponse(content = Some("final"), toolCalls = Nil, finishReason = "stop")
      }

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.InvalidRequestError("not used"))

    override def isAvailable: UIO[Boolean] = ZIO.succeed(true)

  def spec = suite("ContextManagement")(
    test("token counter returns provider-specific estimates") {
      val counter = TokenCounter.default
      val text = "A" * 100

      val openAi = counter.countText(LlmProvider.OpenAI, text)
      val anthropic = counter.countText(LlmProvider.Anthropic, text)
      val ollama = counter.countText(LlmProvider.Ollama, text)

      assertTrue(openAi > 0, anthropic > 0, ollama > 0, anthropic >= openAi)
    },
    test("drop oldest fifo keeps most recent within token budget") {
      val messages = Vector(
        msg("1", PromptRole.User, "a", 20, 1),
        msg("2", PromptRole.User, "b", 20, 2),
        msg("3", PromptRole.User, "c", 20, 3),
      )

      for
        window <- ContextManagement.applyWindow(
                    messages,
                    LlmProvider.OpenAI,
                    ContextLimits(maxTokens = 40),
                    ContextTrimmingStrategy.DropOldestFifo,
                  )
      yield assertTrue(
        window.messages.map(_.id) == Vector("2", "3"),
        window.trimmed,
      )
    },
    test("sliding window preserves system message") {
      val messages = Vector(
        msg("s", PromptRole.System, "system", 10, 1, important = true),
        msg("1", PromptRole.User, "a", 20, 2),
        msg("2", PromptRole.Assistant, "b", 20, 3),
      )

      for
        window <- ContextManagement.applyWindow(
                    messages,
                    LlmProvider.OpenAI,
                    ContextLimits(maxTokens = 30),
                    ContextTrimmingStrategy.SlidingWindow,
                  )
      yield assertTrue(
        window.messages.exists(_.role == PromptRole.System),
        window.totalTokens <= 30,
      )
    },
    test("priority-based trimming preserves important and tool messages") {
      val messages = Vector(
        msg("1", PromptRole.User, "old", 20, 1),
        msg("2", PromptRole.Tool, "tool", 15, 2),
        msg("3", PromptRole.Assistant, "important", 15, 3, important = true),
      )

      for
        window <- ContextManagement.applyWindow(
                    messages,
                    LlmProvider.OpenAI,
                    ContextLimits(maxTokens = 30),
                    ContextTrimmingStrategy.PriorityBased,
                  )
      yield assertTrue(
        window.messages.exists(_.role == PromptRole.Tool),
        window.messages.exists(_.important),
      )
    },
    test("summarize strategy replaces older history with summary") {
      val messages = Vector(
        msg("1", PromptRole.User, "old1", 20, 1),
        msg("2", PromptRole.Assistant, "old2", 20, 2),
        msg("3", PromptRole.User, "recent", 20, 3),
      )

      for
        window <- ContextManagement.applyWindow(
                    messages,
                    LlmProvider.OpenAI,
                    ContextLimits(maxTokens = 35),
                    ContextTrimmingStrategy.SummarizeOldMessages(summaryTargetTokens = 10),
                  )
      yield assertTrue(
        window.messages.exists(_.metadata.get("summary").contains("true")),
        window.totalTokens <= 35,
      )
    },
    test("clarification flow retries parse failures") {
      val service = new ClarificationLlmService

      for
        value <- MultiTurnInteractions.executeWithClarification[Int](
                   initialPrompt = "Return JSON",
                   llmService = service,
                   parse = content =>
                     content.fromJson[Map[String, Int]].left.map(identity).flatMap { map =>
                       map.get("value").toRight("Missing value field")
                     },
                 )
      yield assertTrue(value == 42)
    },
    test("tool conversation manager tracks tool loop and state") {
      val echoTool = Tool(
        name = "echo",
        description = "echo",
        parameters = Json.Obj("type" -> Json.Str("object")),
        execute = args => ZIO.succeed(args),
      )

      for
        registry <- ToolRegistry.make
        _ <- registry.register(echoTool)
        now <- Clock.instant
        thread = ConversationThread.create("thread-1", now)
        result <- ToolConversationManager.run(
                    prompt = "do stuff",
                    thread = thread,
                    llmService = new ToolLoopLlmService,
                    toolRegistry = registry,
                    tools = List(echoTool),
                    maxIterations = 4,
                  )
      yield assertTrue(
        result.response.content == "final",
        result.thread.state == ConversationState.Completed,
        result.thread.messages.exists(_.role == PromptRole.Tool),
      )
    },
  )
