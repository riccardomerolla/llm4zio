package agent.entity

import java.nio.file.{ Files, Path }
import java.time.{ Duration, Instant }

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids.AgentId
import shared.store.{ DataStoreModule, DataStoreService, EventStore, StoreConfig }

object AgentRepositoryESSpec extends ZIOSpecDefault:

  private type Env = DataStoreService & EventStore[AgentId, AgentEvent] & AgentRepository

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("agent-repo-es-spec")).orDie
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
      AgentEventStoreES.live,
      AgentRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AgentRepositoryESSpec")(
      test("create/update/disable/delete projection") {
        withTempDir { path =>
          val id  = AgentId("agent-1")
          val now = Instant.parse("2026-03-02T12:00:00Z")
          val a0  = Agent(
            id = id,
            name = "scala-agent",
            description = "Scala coding agent",
            cliTool = "gemini",
            capabilities = List("scala", "testing"),
            defaultModel = Some("gemini-2.5-flash"),
            systemPrompt = Some("You are a Scala expert"),
            maxConcurrentRuns = 2,
            envVars = Map("A" -> "B"),
            timeout = Duration.ofMinutes(30),
            enabled = true,
            createdAt = now,
            updatedAt = now,
          )
          val a1  = a0.copy(description = "Updated desc", updatedAt = now.plusSeconds(5))
          (for
            repo <- ZIO.service[AgentRepository]
            _    <- repo.append(AgentEvent.Created(a0, now))
            _    <- repo.append(AgentEvent.Updated(a1, now.plusSeconds(5)))
            _    <- repo.append(AgentEvent.Disabled(id, Some("maintenance"), now.plusSeconds(6)))
            _    <- repo.append(AgentEvent.Deleted(id, now.plusSeconds(7)))
            got  <- repo.get(id)
          yield assertTrue(
            got.description == "Updated desc",
            !got.enabled,
            got.deletedAt.contains(now.plusSeconds(7)),
          )).provideLayer(layerFor(path))
        }
      }
    ) @@ TestAspect.sequential
