package config.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.json.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids
import store.{ ConfigStoreModule, CustomAgentRow, StoreConfig, WorkflowRow }

object ConfigRepositoryESSpec extends ZIOSpecDefault:

  final private case class LegacyStoredSetting(value: String, updatedAt: Instant) derives JsonCodec

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("config-repo-es-spec")).orDie
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

  private def layerFor(path: Path)
    : ZLayer[Any, EclipseStoreError | GigaMapError, ConfigRepository & ConfigStoreModule.ConfigStoreService] =
    ZLayer.make[ConfigRepository & ConfigStoreModule.ConfigStoreService](
      ZLayer.succeed(StoreConfig(path.resolve("config").toString, path.resolve("data").toString)),
      ConfigStoreModule.live,
      ConfigRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ConfigRepositoryESSpec")(
      test("direct-state setting/workflow/agent CRUD") {
        withTempDir { path =>
          val now = Instant.parse("2026-02-23T15:00:00Z")
          (for
            repository <- ZIO.service[ConfigRepository]
            _          <- repository.putSetting(Setting("ai.enabled", SettingValue.Flag(true), now))
            setting    <- repository.getSetting("ai.enabled")
            _          <- repository.saveWorkflow(
                            Workflow(
                              id = Ids.WorkflowId("wf-1"),
                              name = "Chat",
                              description = "chat workflow",
                              steps = List("chat"),
                              isBuiltin = true,
                              createdAt = now,
                              updatedAt = now,
                            )
                          )
            workflows  <- repository.listWorkflows
            _          <- repository.saveAgent(
                            CustomAgent(
                              id = Ids.AgentId("agent-1"),
                              name = "custom-agent",
                              displayName = "Custom",
                              description = "desc",
                              systemPrompt = "prompt",
                              tags = List("ops", "chat"),
                              enabled = true,
                              createdAt = now,
                              updatedAt = now,
                            )
                          )
            agents     <- repository.listAgents
          yield assertTrue(
            setting.value == SettingValue.Flag(true),
            workflows.map(_.id) == List(Ids.WorkflowId("wf-1")),
            agents.map(_.id) == List(Ids.AgentId("agent-1")),
          )).provideLayer(layerFor(path))
        }
      },
      test("legacy settings/workflow/agent rows migrate to direct-state objects") {
        withTempDir { path =>
          val now            = Instant.parse("2026-02-23T15:05:00Z")
          val legacySetting  = LegacyStoredSetting("gemini-2.5", now).toJson
          val legacyWorkflow = WorkflowRow(
            id = "legacy-wf-1",
            name = "Legacy Wf",
            description = Some("legacy description"),
            stepsJson = "[\"chat\"]",
            isBuiltin = false,
            createdAt = now,
            updatedAt = now.plusSeconds(5),
          )
          val legacyAgent    = CustomAgentRow(
            id = "legacy-agent-1",
            name = "legacy-agent",
            displayName = "Legacy Agent",
            description = Some("legacy custom agent"),
            systemPrompt = "you are legacy",
            tagsJson = Some("[\"legacy\",\"custom\"]"),
            enabled = true,
            createdAt = now,
            updatedAt = now.plusSeconds(10),
          )

          (for
            store      <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            repository <- ZIO.service[ConfigRepository]
            _          <- store.store.store("setting:ai.model", legacySetting)
            _          <- store.store.store("workflow:legacy-wf-1", legacyWorkflow)
            _          <- store.store.store("agent:legacy-agent-1", legacyAgent)
            count      <- ConfigMigration.migrateLegacyRows
            setting    <- repository.getSetting("ai.model")
            workflows  <- repository.listWorkflows
            agents     <- repository.listAgents
          yield assertTrue(
            count == 3,
            setting.value == SettingValue.Text("gemini-2.5"),
            workflows.exists(_.id == Ids.WorkflowId("legacy-wf-1")),
            agents.exists(_.id == Ids.AgentId("legacy-agent-1")),
          )).provideLayer(layerFor(path))
        }
      },
    ) @@ TestAspect.sequential
