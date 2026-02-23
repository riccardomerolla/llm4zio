package db

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.store.{ ConfigStoreModule, StoreConfig }

object ConfigRepositoryESSpec extends ZIOSpecDefault:

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("config-repository-es-spec")).orDie
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

  private def layerFor(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, ConfigRepository] =
    (ZLayer.succeed(
      StoreConfig(
        configStorePath = path.resolve("config-store").toString,
        dataStorePath = path.resolve("data-store").toString,
      )
    ) >>> ConfigStoreModule.live) >>> ConfigRepositoryES.live

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ConfigRepositoryESSpec")(
      test("settings upsert/get/delete flow") {
        withTempDir { dir =>
          val program =
            for
              repository <- ZIO.service[ConfigRepository]
              _          <- repository.upsertSetting("ai.model", "gemini-2.5-flash")
              _          <- repository.upsertSettings(
                              Map(
                                "ai.timeout"      -> "120",
                                "gateway.verbose" -> "true",
                              )
                            )
              one        <- repository.getSetting("ai.model")
              prefixed   <- repository.getSettingsByPrefix("ai.")
              _          <- repository.deleteSetting("ai.model")
              deleted    <- repository.getSetting("ai.model")
              _          <- repository.deleteSettingsByPrefix("ai.")
              after      <- repository.getSettingsByPrefix("ai.")
            yield assertTrue(
              one.exists(_.value == "gemini-2.5-flash"),
              prefixed.map(_.key).toSet == Set("ai.model", "ai.timeout"),
              deleted.isEmpty,
              after.isEmpty,
            )

          program.provideLayer(layerFor(dir))
        }
      },
      test("workflow create/get/update/list/delete round-trip") {
        withTempDir { dir =>
          val now     = Instant.parse("2026-02-19T15:10:00Z")
          val program =
            for
              repository <- ZIO.service[ConfigRepository]
              id         <- repository.createWorkflow(
                              WorkflowRow(
                                id = None,
                                name = "Code Review",
                                description = Some("review workflow"),
                                steps = "[\"analysis\",\"report\"]",
                                isBuiltin = false,
                                createdAt = now,
                                updatedAt = now,
                              )
                            )
              loaded     <- repository.getWorkflow(id)
              current    <- ZIO
                              .fromOption(loaded)
                              .orElseFail(PersistenceError.QueryFailed("getWorkflow", s"Missing workflow id=$id"))
              _          <- repository.updateWorkflow(
                              current.copy(description = Some("updated"), updatedAt = now.plusSeconds(60L))
                            )
              byName     <- repository.getWorkflowByName("Code Review")
              all        <- repository.listWorkflows
              _          <- repository.deleteWorkflow(id)
              removed    <- repository.getWorkflow(id)
            yield assertTrue(
              loaded.exists(_.name == "Code Review"),
              byName.flatMap(_.description).contains("updated"),
              all.exists(_.id.contains(id)),
              removed.isEmpty,
            )

          program.provideLayer(layerFor(dir))
        }
      },
    )
