package db

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import models.{ ActivityEvent, ActivityEventType }
import store.{ DataStoreModule, StoreConfig }

object ActivityRepositoryESSpec extends ZIOSpecDefault:

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("activity-repository-es-spec")).orDie
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

  private def layerFor(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, ActivityRepository] =
    (ZLayer.succeed(
      StoreConfig(
        configStorePath = path.resolve("config-store").toString,
        dataStorePath = path.resolve("data-store").toString,
      )
    ) >>> DataStoreModule.live) >>> ActivityRepositoryES.live

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ActivityRepositoryESSpec")(
      test("createEvent and listEvents filter by eventType and since") {
        withTempDir { dir =>
          val t0 = Instant.parse("2026-02-19T16:00:00Z")
          val t1 = Instant.parse("2026-02-19T16:01:00Z")
          val t2 = Instant.parse("2026-02-19T16:02:00Z")

          val program =
            for
              repository <- ZIO.service[ActivityRepository]
              _          <- repository.createEvent(
                              ActivityEvent(
                                eventType = ActivityEventType.RunStarted,
                                source = "orchestrator",
                                summary = "run started",
                                createdAt = t0,
                              )
                            )
              _          <- repository.createEvent(
                              ActivityEvent(
                                eventType = ActivityEventType.RunCompleted,
                                source = "orchestrator",
                                summary = "run completed",
                                createdAt = t1,
                              )
                            )
              _          <- repository.createEvent(
                              ActivityEvent(
                                eventType = ActivityEventType.RunCompleted,
                                source = "orchestrator",
                                summary = "run completed again",
                                createdAt = t2,
                              )
                            )
              filtered   <- repository.listEvents(
                              eventType = Some(ActivityEventType.RunCompleted),
                              since = Some(t1),
                              limit = 10,
                            )
            yield assertTrue(
              filtered.length == 2,
              filtered.forall(_.eventType == ActivityEventType.RunCompleted),
              filtered.forall(event => !event.createdAt.isBefore(t1)),
              filtered.map(_.summary) == List("run completed again", "run completed"),
            )

          program.provideLayer(layerFor(dir))
        }
      },
      test("createEvent persists all ActivityEventType enum values") {
        withTempDir { dir =>
          val base = Instant.parse("2026-02-19T17:00:00Z")

          val program =
            for
              repository   <- ZIO.service[ActivityRepository]
              _            <- ZIO.foreachDiscard(ActivityEventType.values.zipWithIndex) { (eventType, index) =>
                                repository.createEvent(
                                  ActivityEvent(
                                    eventType = eventType,
                                    source = "settings",
                                    summary = s"event-$index",
                                    createdAt = base.plusSeconds(index.toLong),
                                  )
                                )
                              }
              loaded       <- repository.listEvents(limit = 100)
              loadedTypes   = loaded.map(_.eventType).toSet
              expectedTypes = ActivityEventType.values.toSet
            yield assertTrue(loadedTypes == expectedTypes)

          program.provideLayer(layerFor(dir))
        }
      },
    )
