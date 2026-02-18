package gateway.telegram

import zio.*
import zio.test.*

import llm4zio.core.*
import models.*

object IntentParserSpec extends ZIOSpecDefault:

  private val agents = List(
    AgentInfo(
      name = "code-agent",
      displayName = "Code Agent",
      description = "Assists with coding tasks",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("code", "review", "generation"),
      skills = List(
        AgentSkill("code-generation", "Generate and review code", List("Prompt"), List("Patch"))
      ),
    ),
    AgentInfo(
      name = "web-search-agent",
      displayName = "Web Search Agent",
      description = "Searches the web",
      agentType = AgentType.BuiltIn,
      usesAI = false,
      tags = List("search", "web", "research"),
      skills = List(
        AgentSkill("web-search", "Search public documentation", List("SearchQuery"), List("Summary"))
      ),
    ),
  )

  private val unavailableLlm: ULayer[LlmService] =
    ZLayer.succeed(
      new LlmService:
        override def execute(prompt: String): IO[LlmError, LlmResponse] =
          ZIO.fail(LlmError.ConfigError("unavailable"))
        override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] = zio.stream.ZStream.empty
        override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
          ZIO.fail(LlmError.ConfigError("unavailable"))
        override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
          zio.stream.ZStream.empty
        override def executeWithTools(prompt: String, tools: List[llm4zio.tools.AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.fail(LlmError.ConfigError("unavailable"))
        override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: llm4zio.tools.JsonSchema): IO[LlmError, A] =
          ZIO.fail(LlmError.ConfigError("unavailable"))
        override def isAvailable: UIO[Boolean] = ZIO.succeed(false)
    )

  def spec: Spec[TestEnvironment, Any] = suite("IntentParserSpec")(
    test("routes direct analysis intent") {
      for
        decision <- IntentParser.parse(
                      message = "Please review this code and suggest fixes",
                      state = IntentConversationState(),
                      availableAgents = agents,
                    )
      yield assertTrue(
        decision match
          case IntentDecision.Route(agent, _) => agent == "code-agent"
          case _                              => false
      )
    },
    test("returns clarification when no strong intent is present") {
      for
        decision <- IntentParser.parse(
                      message = "hello there",
                      state = IntentConversationState(),
                      availableAgents = agents,
                    )
      yield assertTrue(
        decision match
          case IntentDecision.Clarify(question, options) =>
            question.nonEmpty && options.contains("code-agent")
          case _                                         => false
      )
    },
    test("resolves clarification by numeric selection") {
      for
        decision <- IntentParser.parse(
                      message = "2",
                      state = IntentConversationState(
                        pendingOptions = List("code-agent", "web-search-agent")
                      ),
                      availableAgents = agents,
                    )
      yield assertTrue(decision == IntentDecision.Route("web-search-agent", "selected option 2"))
    },
    test("uses LLM decision when available and confident") {
      val llmLayer = ZLayer.succeed(
        new LlmService:
          override def execute(prompt: String): IO[LlmError, LlmResponse] =
            ZIO.succeed(LlmResponse("""{"agent":"web-search-agent","confidence":0.91}"""))
          override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] = zio.stream.ZStream.empty
          override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] = ZIO.succeed(LlmResponse("ok"))
          override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
            zio.stream.ZStream.empty
          override def executeWithTools(prompt: String, tools: List[llm4zio.tools.AnyTool]): IO[LlmError, ToolCallResponse] =
            ZIO.succeed(ToolCallResponse(None, Nil, "stop"))
          override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: llm4zio.tools.JsonSchema): IO[LlmError, A] =
            ZIO.fail(LlmError.InvalidRequestError("unused"))
          override def isAvailable: UIO[Boolean] = ZIO.succeed(true)
      )

      for
        decision <- IntentParser.parse(
                      message = "find latest docs about zio-http",
                      state = IntentConversationState(),
                      availableAgents = agents,
                    ).provideLayer(llmLayer)
      yield assertTrue(decision == IntentDecision.Route("web-search-agent", "llm confidence=0.91"))
    },
  ).provideSomeLayer[TestEnvironment](unavailableLlm)
