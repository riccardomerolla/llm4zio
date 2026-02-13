package llm4zio.agents

import zio.*
import zio.test.*
import zio.json.ast.Json
import llm4zio.core.{ Message, MessageRole }

object AgentFrameworkSpec extends ZIOSpecDefault:
  private val analyzerAgent = new Agent:
    override val metadata: AgentMetadata = AgentMetadata(
      name = "analyzer",
      capabilities = Set("analysis", "cobol"),
      version = "1.2.0",
      description = "Performs analysis",
      priority = 10,
    )

    override def execute(input: String, context: AgentContext): IO[AgentError, AgentResult] =
      ZIO.succeed(AgentResult(agent = metadata.name, content = s"analysis:$input"))

  private val validatorAgent = new Agent:
    override val metadata: AgentMetadata = AgentMetadata(
      name = "validator",
      capabilities = Set("validation"),
      version = "1.0.0",
      description = "Validates outputs",
      priority = 5,
    )

    override def execute(input: String, context: AgentContext): IO[AgentError, AgentResult] =
      ZIO.succeed(AgentResult(agent = metadata.name, content = s"validated:$input"))

  private val delegatorAgent = new Agent:
    override val metadata: AgentMetadata = AgentMetadata(
      name = "delegator",
      capabilities = Set("analysis"),
      version = "1.0.0",
      description = "Delegates work",
      priority = 1,
    )

    override def execute(input: String, context: AgentContext): IO[AgentError, AgentResult] =
      ZIO.succeed(
        AgentResult(
          agent = metadata.name,
          content = "handoff",
          handoff = Some(AgentHandoff("validator", "Needs validation", Json.Obj("source" -> Json.Str("delegator")))),
        )
      )

  private val rejectingStore = new PersistentMemoryStore:
    override def upsertThread(thread: ConversationThread): IO[MemoryError, Unit] = ZIO.unit
    override def loadThread(threadId: String): IO[MemoryError, Option[ConversationThread]] = ZIO.succeed(None)
    override def appendEntry(entry: MemoryEntry): IO[MemoryError, Unit] = ZIO.unit
    override def searchEntries(query: String, limit: Int): IO[MemoryError, List[MemoryEntry]] = ZIO.succeed(Nil)

  def spec = suite("AgentFramework")(
    test("routes by capability and resolves conflicts by priority") {
      for
        selected <- AgentRouter.route("analysis", List(delegatorAgent, analyzerAgent), ConflictResolution.HighestPriority)
      yield assertTrue(selected.metadata.name == "analyzer")
    },
    test("fails on capability conflict when strategy is FailOnConflict") {
      for
        result <- AgentRouter.route("analysis", List(delegatorAgent, analyzerAgent), ConflictResolution.FailOnConflict).either
      yield assertTrue(result.isLeft)
    },
    test("runs handoff between agents") {
      for
        result <- AgentCoordinator.executeWithHandoff(
                    input = "input",
                    initialAgent = delegatorAgent,
                    context = AgentContext.empty("thread-1"),
                    agents = List(delegatorAgent, validatorAgent),
                  )
      yield assertTrue(
        result.agent == "validator",
        result.content.contains("validated:"),
      )
    },
    test("runs agents in parallel and aggregates output") {
      for
        result <- AgentCoordinator.executeParallel(
                    input = "x",
                    context = AgentContext.empty("thread-2"),
                    agents = List(analyzerAgent, validatorAgent),
                  )
      yield assertTrue(
        result.agent == "parallel-coordinator",
        result.content.contains("[analyzer]"),
        result.content.contains("[validator]"),
      )
    },
    test("trims context while preserving system message") {
      val context = AgentContext.empty("thread-3").copy(
        constraints = AgentConstraints(maxContextMessages = 2, maxEstimatedTokens = 100),
        history = Vector(
          Message(MessageRole.System, "system"),
          Message(MessageRole.User, "a"),
          Message(MessageRole.Assistant, "b"),
        ),
      )

      val trimmed = context.trim(ContextTrimStrategy.KeepSystemAndLatest)
      assertTrue(
        trimmed.history.exists(_.role == MessageRole.System),
        trimmed.history.length <= 2,
      )
    },
    test("supports in-memory history and fork") {
      for
        memory <- Memory.inMemory
        _ <- memory.append("t1", Message(MessageRole.User, "hello"))
        forked <- memory.fork("t1", "t2")
        messages <- memory.read("t2")
      yield assertTrue(
        forked.parentThreadId.contains("t1"),
        messages.nonEmpty,
        messages.head.content == "hello",
      )
    },
    test("persistent memory delegates to store") {
      for
        inMemory <- Memory.inMemory
        memory = PersistentMemory(inMemory, rejectingStore)
        _ <- memory.append("thread-p", Message(MessageRole.User, "persist me"))
        history <- memory.read("thread-p")
        entries <- memory.search("persist", limit = 10)
      yield assertTrue(
        history.exists(_.content == "persist me"),
        entries.nonEmpty,
      )
    },
  )
