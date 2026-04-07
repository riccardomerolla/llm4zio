package config.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

// config.entity types are in scope via package
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
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
      test("adapter exposes consolidated settings/workflow/agent CRUD") {
        withTempDir { path =>
          val now = Instant.parse("2026-02-23T15:00:00Z")
          (for
            repository <- ZIO.service[ConfigRepository]
            _          <- repository.upsertSetting("ai.enabled", "true")
            setting    <- repository.getSetting("ai.enabled")
            workflowId <- repository.createWorkflow(
                            WorkflowRow(
                              id = None,
                              name = "Chat",
                              description = Some("chat workflow"),
                              steps = """["chat"]""",
                              isBuiltin = true,
                              createdAt = now,
                              updatedAt = now,
                            )
                          )
            workflows  <- repository.listWorkflows
            agentId    <- repository.createCustomAgent(
                            CustomAgentRow(
                              id = None,
                              name = "custom-agent",
                              displayName = "Custom",
                              description = Some("desc"),
                              systemPrompt = "prompt",
                              tags = Some("""["ops","chat"]"""),
                              enabled = true,
                              createdAt = now,
                              updatedAt = now,
                            )
                          )
            agents     <- repository.listCustomAgents
          yield assertTrue(
            setting.exists(_.value == "true"),
            workflows.map(_.id).contains(Some(workflowId)),
            agents.map(_.id).contains(Some(agentId)),
          )).provideLayer(layerFor(path))
        }
      }
    ) @@ TestAspect.sequential
