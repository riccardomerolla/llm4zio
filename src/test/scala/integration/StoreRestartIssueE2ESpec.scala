package integration

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import db.*
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.service.LifecycleCommand
import models.*
import store.{ DataStoreModule, StoreConfig }

/** End-to-end test verifying that issue data survives a full store restart. Mirrors the working restart tests in
  * ChatRepositoryESSpec.
  */
object StoreRestartIssueE2ESpec extends ZIOSpecDefault:

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
      ZIO.attemptBlocking(Files.createTempDirectory("store-restart-e2e-spec")).orDie
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

  // Exact copy of layerForWithConversations from ChatRepositoryESSpec
  private def layerForWithConversations(
    path: Path,
  ): ZLayer[Any, EclipseStoreError | GigaMapError, ChatRepository & DataStoreModule.DataStoreService] =
    ZLayer.make[ChatRepository & DataStoreModule.DataStoreService](
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
    suite("StoreRestartIssueE2ESpec")(
      // Exact copy of the "restart persists chat messages and issues with string IDs" test
      // from ChatRepositoryESSpec — to verify the same code works from this spec
      test("issue key survives full store restart") {
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
