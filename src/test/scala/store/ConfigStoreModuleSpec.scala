package store

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError

object ConfigStoreModuleSpec extends ZIOSpecDefault:

  private type ConfigEnv = ConfigStoreModule.ConfigStoreService & ConfigStoreModule.SettingsStore &
    ConfigStoreModule.WorkflowsStore & ConfigStoreModule.CustomAgentsStore

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

  private def layerFor(dataDir: Path): ZLayer[Any, EclipseStoreError | GigaMapError, ConfigEnv] =
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
            map    <- ConfigStoreModule.settingsMap
            _      <- map.put("timezone", "UTC")
            loaded <- map.get("timezone")
          yield assertTrue(loaded.contains("UTC"))).provideLayer(layerFor(dir))
        }
      },
      test("workflows map persists row across store restart") {
        withTempDir { dir =>
          val row = WorkflowRow(
            id = "wf-1",
            name = "Default",
            description = Some("Default workflow"),
            stepsJson = "[\"Discovery\",\"Analysis\"]",
            isBuiltin = true,
            createdAt = Instant.parse("2026-02-19T10:00:00Z"),
            updatedAt = Instant.parse("2026-02-19T10:00:00Z"),
          )

          val write =
            (for
              map <- ConfigStoreModule.workflowsMap
              _   <- map.put(WorkflowId("wf-1"), row)
            yield ()).provideLayer(layerFor(dir))

          val read =
            (for
              map    <- ConfigStoreModule.workflowsMap
              loaded <- map.get(WorkflowId("wf-1"))
            yield assertTrue(loaded.contains(row))).provideLayer(layerFor(dir))

          write *> read
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
            map    <- ConfigStoreModule.customAgentsMap
            _      <- map.put(AgentId("agent-1"), row)
            loaded <- map.get(AgentId("agent-1"))
          yield assertTrue(loaded.contains(row))).provideLayer(layerFor(dir))
        }
      },
    )
