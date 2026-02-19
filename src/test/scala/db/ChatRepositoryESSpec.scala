package db

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import models.*
import store.*

object ChatRepositoryESSpec extends ZIOSpecDefault:

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("chat-repository-es-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files
            .walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(path =>
              val _ = Files.deleteIfExists(path)
            )
      }.ignore
    )(use)

  private def layerFor(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, ChatRepository] =
    (ZLayer.succeed(
      StoreConfig(
        configStorePath = path.resolve("config-store").toString,
        dataStorePath = path.resolve("data-store").toString,
      )
    ) >>> DataStoreModule.live) >>> ChatRepositoryES.live

  private def layerForWithConversations(
    path: Path
  ): ZLayer[Any, EclipseStoreError | GigaMapError, ChatRepository & DataStoreModule.ConversationsStore] =
    ZLayer.make[ChatRepository & DataStoreModule.ConversationsStore](
      ZLayer.succeed(
        StoreConfig(
          configStorePath = path.resolve("config-store").toString,
          dataStorePath = path.resolve("data-store").toString,
        )
      ),
      DataStoreModule.live,
      ChatRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ChatRepositoryESSpec")(
      test("createConversation/addMessage/getMessages and hydration round-trip") {
        withTempDir { dir =>
          val createdAt = Instant.parse("2026-02-19T13:00:00Z")
          val updatedAt = Instant.parse("2026-02-19T13:00:00Z")

          val program =
            for
              repo            <- ZIO.service[ChatRepository]
              conversationId  <- repo.createConversation(
                                   ChatConversation(
                                     title = "ES chat",
                                     runId = Some("42"),
                                     description = Some("conversation test"),
                                     createdAt = createdAt,
                                     updatedAt = updatedAt,
                                     createdBy = Some("spec"),
                                   )
                                 )
              messageCreatedAt = Instant.parse("2026-02-19T13:00:01Z")
              messageUpdatedAt = Instant.parse("2026-02-19T13:00:01Z")
              _               <- repo.addMessage(
                                   ConversationEntry(
                                     conversationId = conversationId.toString,
                                     sender = "tester",
                                     senderType = SenderType.User,
                                     content = "hello",
                                     messageType = MessageType.Text,
                                     metadata = Some("{}"),
                                     createdAt = messageCreatedAt,
                                     updatedAt = messageUpdatedAt,
                                   )
                                 )
              _               <- repo.addMessage(
                                   ConversationEntry(
                                     conversationId = conversationId.toString,
                                     sender = "assistant",
                                     senderType = SenderType.Assistant,
                                     content = "world",
                                     messageType = MessageType.Status,
                                     metadata = None,
                                     createdAt = Instant.parse("2026-02-19T13:00:02Z"),
                                     updatedAt = Instant.parse("2026-02-19T13:00:02Z"),
                                   )
                                 )
              messages        <- repo.getMessages(conversationId)
              hydrated        <- repo.getConversation(conversationId)
            yield assertTrue(
              messages.length == 2,
              messages.head.content == "hello",
              messages(1).content == "world",
              hydrated.exists(_.messages.length == 2),
              hydrated.flatMap(_.runId).contains("42"),
            )

          program.provideLayer(layerFor(dir))
        }
      },
      test("upsert/get session context round-trip") {
        withTempDir { dir =>
          val updatedAt   = Instant.parse("2026-02-19T13:10:00Z")
          val contextJson = "{\"conversationId\":123,\"runId\":999,\"state\":\"ok\"}"

          val program =
            for
              repo           <- ZIO.service[ChatRepository]
              _              <- repo.upsertSessionContext(
                                  channelName = "telegram",
                                  sessionKey = "chat-42",
                                  contextJson = contextJson,
                                  updatedAt = updatedAt,
                                )
              loaded         <- repo.getSessionContext("telegram", "chat-42")
              byConversation <- repo.getSessionContextByConversation(123L)
              byRun          <- repo.getSessionContextByTaskRunId(999L)
            yield assertTrue(
              loaded.contains(contextJson),
              byConversation.exists(_.sessionKey == "chat-42"),
              byRun.exists(_.channelName == "telegram"),
            )

          program.provideLayer(layerFor(dir))
        }
      },
      test("listConversations tolerates legacy null Option fields") {
        withTempDir { dir =>
          val legacyNullLong: Option[Long] = None
          val createdAt                    = Instant.parse("2026-02-19T13:20:00Z")
          val updatedAt                    = Instant.parse("2026-02-19T13:21:00Z")

          val program =
            for
              conversations <- ZIO.service[DataStoreModule.ConversationsStore]
              repo          <- ZIO.service[ChatRepository]
              _             <- conversations.map.put(
                                 101L,
                                 ConversationRow(
                                   id = 101L,
                                   title = "legacy",
                                   description = None,
                                   channelName = Some("web"),
                                   status = "active",
                                   createdAt = createdAt,
                                   updatedAt = updatedAt,
                                   runId = legacyNullLong,
                                   createdBy = None,
                                 ),
                               )
              listed        <- repo.listConversations(0, 10)
            yield assertTrue(
              listed.exists(_.id.contains("101")),
              listed.find(_.id.contains("101")).flatMap(_.runId).isEmpty,
            )

          program.provideLayer(layerForWithConversations(dir))
        }
      },
      test("listConversations tolerates malformed legacy Some(null) runId") {
        withTempDir { dir =>
          // Simulate legacy malformed data by using empty string representation
          val malformedRunId: Option[Long] = None
          val createdAt                    = Instant.parse("2026-02-19T13:22:00Z")
          val updatedAt                    = Instant.parse("2026-02-19T13:23:00Z")

          val program =
            for
              conversations <- ZIO.service[DataStoreModule.ConversationsStore]
              repo          <- ZIO.service[ChatRepository]
              _             <- conversations.map.put(
                                 102L,
                                 ConversationRow(
                                   id = 102L,
                                   title = "legacy-malformed",
                                   description = None,
                                   channelName = Some("web"),
                                   status = "active",
                                   createdAt = createdAt,
                                   updatedAt = updatedAt,
                                   runId = malformedRunId,
                                   createdBy = None,
                                 ),
                               )
              listed        <- repo.listConversations(0, 10)
            yield assertTrue(
              listed.exists(_.id.contains("102")),
              listed.find(_.id.contains("102")).nonEmpty,
            )

          program.provideLayer(layerForWithConversations(dir))
        }
      },
    )
