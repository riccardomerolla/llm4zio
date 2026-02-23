package config.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids
import shared.store.{ ConfigStoreModule, StoreConfig }

object ConfigRepositoryESSpec extends ZIOSpecDefault:

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
      }
    ) @@ TestAspect.sequential
