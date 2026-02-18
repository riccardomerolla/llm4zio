package agents

import java.time.Instant
import java.util.UUID

import zio.*
import zio.test.*

import db.*
import llm4zio.agents.*
import llm4zio.core.{ Message, MessageRole }

object Llm4zioAdaptersSpec extends ZIOSpecDefault:

  private def chatRepoLayer(dbName: String): ZLayer[Any, PersistenceError, ChatRepository] =
    ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")) >>>
      Database.live >>>
      ChatRepository.live

  private val fixedNow = Instant.parse("2026-02-13T12:00:00Z")

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Llm4zioAdapters")(
    test("maps existing AgentRegistry built-ins to llm4zio metadata") {
      val mapped = Llm4zioAgentAdapters.builtInAsLlm4zioAgents

      assertTrue(
        mapped.length == AgentRegistry.builtInAgents.length,
        mapped.forall(_.metadata.capabilities.nonEmpty),
        mapped.exists(_.metadata.name == "code-agent"),
      )
    },
    test("routes existing agent metadata by capability") {
      val agents = Llm4zioAgentAdapters.builtInAsLlm4zioAgents

      for
        selected <- AgentRouter.route("code", agents)
      yield assertTrue(
        selected.metadata.name == "code-agent"
      )
    },
    test("persists and retrieves conversation memory via ChatRepository") {
      val layer = chatRepoLayer(s"chat-memory-${UUID.randomUUID()}")

      (for
        repository <- ZIO.service[ChatRepository]
        store       = ChatRepositoryMemoryStore(repository, now = ZIO.succeed(fixedNow))
        _          <- store.appendEntry(
                        MemoryEntry(
                          threadId = "integration-thread",
                          message = Message(MessageRole.User, "hello from persistence"),
                          recordedAt = fixedNow,
                        )
                      )
        loaded     <- store.loadThread("integration-thread")
        found      <- store.searchEntries("hello", limit = 10)
      yield assertTrue(
        loaded.exists(_.history.exists(_.content.contains("hello"))),
        found.exists(_.message.content.contains("hello")),
      )).provideLayer(layer)
    },
  ) @@ TestAspect.sequential
