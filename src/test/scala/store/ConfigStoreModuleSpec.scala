package store

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.store.*

object ConfigStoreModuleSpec extends ZIOSpecDefault:

  private type ConfigEnv = ConfigStoreModule.ConfigStoreService

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("config-store-module-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files
            .walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach { path =>
              val _ = Files.deleteIfExists(path)
            }
      }.ignore
    )(use)

  private def layerFor(dataDir: Path): ZLayer[Any, EclipseStoreError, ConfigEnv] =
    ZLayer.succeed(
      StoreConfig(
        configStorePath = dataDir.resolve("config-store").toString,
        dataStorePath = dataDir.resolve("data-store").toString,
      )
    ) >>> ConfigStoreModule.live

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ConfigStoreModuleSpec")(
      test("settings map supports put/get round-trip") {
        withTempDir { dir =>
          (for
            config <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            _      <- config.store.store("setting:timezone", "UTC")
            loaded <- config.store.fetch[String, String]("setting:timezone")
          yield assertTrue(loaded.contains("UTC"))).provideLayer(layerFor(dir))
        }
      },
      test("settings key is visible via raw key scan") {
        withTempDir { dir =>
          (for
            config <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            _      <- config.store.store("setting:restart-check", "ok")
            keys   <- config.rawStore.streamKeys[String].filter(_.startsWith("setting:")).runCollect
            loaded <- config.store.fetch[String, String]("setting:restart-check")
          yield assertTrue(
            keys.contains("setting:restart-check"),
            loaded.contains("ok"),
          )).provideLayer(layerFor(dir))
        }
      },
      test("customAgents map supports put/get round-trip") {
        withTempDir { dir =>
          val row = CustomAgentRow(
            id = "agent-1",
            name = "reviewer",
            displayName = "Code Reviewer",
            description = Some("Reviews code changes"),
            systemPrompt = "Review this code.",
            tagsJson = Some("[\"scala\",\"review\"]"),
            enabled = true,
            createdAt = Instant.parse("2026-02-19T10:00:00Z"),
            updatedAt = Instant.parse("2026-02-19T10:00:00Z"),
          )
          (for
            config <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            _      <- config.store.store("agent:agent-1", row)
            loaded <- config.store.fetch[String, CustomAgentRow]("agent:agent-1")
          yield assertTrue(loaded.contains(row))).provideLayer(layerFor(dir))
        }
      },
    )
