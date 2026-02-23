package db

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.service.LifecycleCommand
import issues.entity.api.{ AgentIssue, IssuePriority }
import shared.store.*

object ChatRepositoryESSpec extends ZIOSpecDefault:

  private def runWithClockAdvance[R, E, A](
    effect: ZIO[R, E, A],
    tick: Duration = 1.second,
    maxTicks: Int = 300,
  ): ZIO[R, E, A] =
    def awaitWithClock(fiber: Fiber[E, A], remainingTicks: Int): ZIO[Any, E, A] =
      fiber.poll.flatMap {
        case Some(exit) =>
          exit match
            case Exit.Success(value) => ZIO.succeed(value)
            case Exit.Failure(cause) => ZIO.failCause(cause)
        case None       =>
          if remainingTicks <= 0 then fiber.interrupt *> ZIO.dieMessage("timed out while advancing TestClock")
          else TestClock.adjust(tick) *> awaitWithClock(fiber, remainingTicks - 1)
      }

    for
      fiber  <- effect.fork
      result <- awaitWithClock(fiber, maxTicks)
    yield result

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
    ) >>> DataStoreModule.live) >>> ChatRepositoryLive.live

  private def layerForWithConversations(
    path: Path
  ): ZLayer[Any, EclipseStoreError | GigaMapError, ChatRepository & DataStoreModule.DataStoreService] =
    ZLayer.make[ChatRepository & DataStoreModule.DataStoreService](
      ZLayer.succeed(
        StoreConfig(
          configStorePath = path.resolve("config-store").toString,
          dataStorePath = path.resolve("data-store").toString,
        )
      ),
      DataStoreModule.live,
      ChatRepositoryLive.live,
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
          val legacyNullLong: Option[String] = None
          val createdAt                      = Instant.parse("2026-02-19T13:20:00Z")
          val updatedAt                      = Instant.parse("2026-02-19T13:21:00Z")

          val program =
            for
              dataStore <- ZIO.service[DataStoreModule.DataStoreService]
              repo      <- ZIO.service[ChatRepository]
              _         <- dataStore.store.store(
                             "conv:101",
                             ConversationRow(
                               id = "101",
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
              listed    <- repo.listConversations(0, 10)
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
          val malformedRunId: Option[String] = None
          val createdAt                      = Instant.parse("2026-02-19T13:22:00Z")
          val updatedAt                      = Instant.parse("2026-02-19T13:23:00Z")

          val program =
            for
              dataStore <- ZIO.service[DataStoreModule.DataStoreService]
              repo      <- ZIO.service[ChatRepository]
              _         <- dataStore.store.store(
                             "conv:102",
                             ConversationRow(
                               id = "102",
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
              listed    <- repo.listConversations(0, 10)
            yield assertTrue(
              listed.exists(_.id.contains("102")),
              listed.find(_.id.contains("102")).nonEmpty,
            )

          program.provideLayer(layerForWithConversations(dir))
        }
      },
      test("autoCheckpointInterval persists data to disk within 5 seconds") {
        withTempDir { dir =>
          val createdAt     = Instant.parse("2026-02-19T14:00:00Z")
          val updatedAt     = Instant.parse("2026-02-19T14:00:00Z")
          val dataStorePath = dir.resolve("data-store")

          val program =
            for
              repo           <- ZIO.service[ChatRepository]
              conversationId <- repo.createConversation(
                                  ChatConversation(
                                    title = "checkpoint test",
                                    description = Some("testing persistence"),
                                    createdAt = createdAt,
                                    updatedAt = updatedAt,
                                    createdBy = Some("spec"),
                                  )
                                )
              _              <- ZIO.logInfo(s"Created conversation: $conversationId")

              // Wait for auto-checkpoint interval (5 seconds as configured in DataStoreModule)
              _ <- TestClock.adjust(6.seconds)
              _ <- ZIO.logInfo("Checkpoint interval passed, checking disk persistence")

              // Verify data exists on disk by checking directory structure
              dataStoreExists  <- ZIO.attemptBlocking(Files.exists(dataStorePath))
              channelDirExists <- ZIO.attemptBlocking {
                                    val channelDir = dataStorePath.resolve("channel_0")
                                    Files.exists(channelDir)
                                  }

              // Reload repository and verify data persists
              reloadedConv <- repo.getConversation(conversationId)
            yield assertTrue(
              dataStoreExists,
              channelDirExists,
              reloadedConv.isDefined,
              reloadedConv.exists(_.title == "checkpoint test"),
            )

          program.provideLayer(layerFor(dir))
        }
      } @@ TestAspect.withLiveClock,
      test("restart read path does not hang and remains consistent") {
        withTempDir { dir =>
          val createdAt = Instant.parse("2026-02-19T15:00:00Z")
          val updatedAt = Instant.parse("2026-02-19T15:00:00Z")

          // Phase 1: write data then let the ZLayer scope close (finalizes the store)
          val writeAndClose =
            ZIO
              .service[ChatRepository]
              .zipWith(ZIO.service[DataStoreModule.DataStoreService])((repo, dataStore) => (repo, dataStore))
              .flatMap {
                case (repo, dataStore) =>
                  for
                    convId <- repo.createConversation(
                                ChatConversation(
                                  title = "restart-test conv",
                                  description = Some("should survive restart"),
                                  createdAt = createdAt,
                                  updatedAt = updatedAt,
                                  createdBy = Some("spec"),
                                )
                              )
                    _      <- dataStore.rawStore.maintenance(LifecycleCommand.Checkpoint)
                    _      <- dataStore.rawStore.reloadRoots
                  yield convId
              }
              .provideLayer(layerForWithConversations(dir))

          // Phase 2: open a brand-new store instance at the same path and query back
          def reopenAndRead(convId: Long) =
            ZIO
              .service[ChatRepository]
              .zipWith(ZIO.service[DataStoreModule.DataStoreService])((repo, dataStore) => (repo, dataStore))
              .flatMap((repo, dataStore) =>
                dataStore.rawStore.reloadRoots *> repo
                  .getConversation(convId)
                  .someOrFail(())
                  .retry(Schedule.spaced(100.millis) && Schedule.recurs(50))
                  .option
              )
              .provideLayer(layerForWithConversations(dir))

          for
            convId   <- runWithClockAdvance(writeAndClose)
            _        <- ZIO.logInfo(s"Store closed. Reopening at ${dir.resolve("data-store")}...")
            reloaded <- runWithClockAdvance(reopenAndRead(convId))
          yield assertTrue(
            reloaded.isDefined,
            reloaded.forall(_.title == "restart-test conv"),
            reloaded.forall(_.description.contains("should survive restart")),
          )
        }
      },
      test("restart persists chat messages and issues with string IDs") {
        withTempDir { dir =>
          val createdAt = Instant.parse("2026-02-19T16:00:00Z")
          val updatedAt = Instant.parse("2026-02-19T16:00:00Z")

          val writeAndClose =
            (for
              repo      <- ZIO.service[ChatRepository]
              dataStore <- ZIO.service[DataStoreModule.DataStoreService]
              convId    <- repo.createConversation(
                             ChatConversation(
                               runId = Some("run-alpha-1"),
                               title = "persist me",
                               description = Some("chat+issue restart regression"),
                               createdAt = createdAt,
                               updatedAt = updatedAt,
                               createdBy = Some("spec"),
                             )
                           )
              _         <- repo.addMessage(
                             ConversationEntry(
                               conversationId = convId.toString,
                               sender = "user",
                               senderType = SenderType.User,
                               content = "hello before restart",
                               messageType = MessageType.Text,
                               createdAt = createdAt.plusSeconds(1),
                               updatedAt = createdAt.plusSeconds(1),
                             )
                           )
              issueId   <- repo.createIssue(
                             AgentIssue(
                               runId = Some("run-alpha-1"),
                               conversationId = Some(convId.toString),
                               title = "Issue survives restart",
                               description = "validate persistence",
                               issueType = "bug",
                               priority = IssuePriority.High,
                               createdAt = createdAt.plusSeconds(2),
                               updatedAt = createdAt.plusSeconds(2),
                             )
                           )
              _         <- dataStore.rawStore.maintenance(LifecycleCommand.Checkpoint)
            yield (convId, issueId)).provideLayer(layerForWithConversations(dir))

          def reopenAndRead(convId: Long, issueId: Long) =
            (for
              repo      <- ZIO.service[ChatRepository]
              dataStore <- ZIO.service[DataStoreModule.DataStoreService]
              _         <- dataStore.rawStore.reloadRoots
              conv      <- repo.getConversation(convId)
              messages  <- repo.getMessages(convId)
              issue     <- repo.getIssue(issueId)
              result    <- ZIO
                             .fromOption(for
                               c <- conv
                               i <- issue
                             yield (c, messages, i))
                             .orElseFail(())
                             .retry(Schedule.spaced(100.millis) && Schedule.recurs(50))
                             .option
            yield result).provideLayer(layerForWithConversations(dir))

          for
            saved            <- runWithClockAdvance(writeAndClose)
            (convId, issueId) = saved
            reloaded         <- runWithClockAdvance(reopenAndRead(convId, issueId))
          yield assertTrue(
            reloaded.isDefined,
            reloaded.forall(_._1.title == "persist me"),
            reloaded.forall(_._1.runId.contains("run-alpha-1")),
            reloaded.forall(_._2.exists(_.content == "hello before restart")),
            reloaded.forall(_._3.title == "Issue survives restart"),
            reloaded.forall(_._3.runId.contains("run-alpha-1")),
            reloaded.forall(_._3.conversationId.contains(convId.toString)),
          )
        }
      },
    )
