package conversation.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids
import shared.store.{ DataStoreModule, EventStore, StoreConfig }

object ConversationRepositoryESSpec extends ZIOSpecDefault:

  private type Env =
    DataStoreModule.DataStoreService & EventStore[Ids.ConversationId, ConversationEvent] & ConversationRepository

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("conversation-repo-es-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(path =>
            val _ = Files.deleteIfExists(path)
          )
      }.ignore
    )(use)

  private def layerFor(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, Env] =
    ZLayer.make[Env](
      ZLayer.succeed(StoreConfig(path.resolve("config").toString, path.resolve("data").toString)),
      DataStoreModule.live,
      ConversationEventStoreES.live,
      ConversationRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ConversationRepositoryESSpec")(
      test("append/replay/snapshot for conversation events") {
        withTempDir { path =>
          val id     = Ids.ConversationId("conv-1")
          val now    = Instant.parse("2026-02-23T14:00:00Z")
          val msg    = Message(
            id = Ids.MessageId("msg-1"),
            sender = "user",
            senderType = SenderType.User(),
            content = "hello",
            messageType = MessageType.Text(),
            createdAt = now.plusSeconds(1),
          )
          val events = List[ConversationEvent](
            ConversationEvent.Created(
              id,
              ChannelInfo.Web("web-session"),
              "Title",
              "Desc",
              Some(Ids.TaskRunId("run-1")),
              Some("system"),
              now,
            ),
            ConversationEvent.MessageSent(id, msg, now.plusSeconds(1)),
            ConversationEvent.ChannelChanged(id, ChannelInfo.Telegram("telegram"), now.plusSeconds(2)),
            ConversationEvent.Closed(id, now.plusSeconds(3), now.plusSeconds(3)),
          )
          (for
            repo <- ZIO.service[ConversationRepository]
            _    <- ZIO.foreachDiscard(events)(repo.append)
            got  <- repo.get(id)
          yield assertTrue(
            got.messages.nonEmpty,
            got.channel == ChannelInfo.Telegram("telegram"),
            got.state == ConversationState.Closed(now, now.plusSeconds(3)),
          )).provideLayer(layerFor(path))
        }
      }
    ) @@ TestAspect.sequential
