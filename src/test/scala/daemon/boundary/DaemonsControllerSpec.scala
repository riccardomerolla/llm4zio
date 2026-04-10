package daemon.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import daemon.control.DaemonAgentScheduler
import daemon.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ DaemonAgentSpecId, ProjectId }

object DaemonsControllerSpec extends ZIOSpecDefault:

  private val status = DaemonAgentStatus(
    spec = DaemonAgentSpec(
      id = DaemonAgentSpecId("project-1__test-guardian"),
      daemonKey = DaemonAgentSpec.TestGuardianKey,
      projectId = ProjectId("project-1"),
      name = "Test Guardian",
      purpose = "Watch failing CI",
      trigger = DaemonTriggerCondition.Scheduled(30.minutes),
      workspaceIds = List("ws-1"),
      agentName = "code-agent",
      prompt = "Scan CI",
      limits = DaemonExecutionLimits(),
      builtIn = true,
      governed = true,
    ),
    enabled = true,
    runtime = DaemonAgentRuntime(
      health = DaemonHealth.Healthy,
      startedAt = Some(Instant.parse("2026-03-26T12:00:00Z")),
    ),
  )

  final private class StubScheduler(ref: Ref[List[String]]) extends DaemonAgentScheduler:
    override def list: IO[PersistenceError, List[DaemonAgentStatus]]                                    = ZIO.succeed(List(status))
    override def start(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                               = ref.update(_ :+ s"start:${id.value}")
    override def stop(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                                = ref.update(_ :+ s"stop:${id.value}")
    override def restart(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                             = ref.update(_ :+ s"restart:${id.value}")
    override def setEnabled(id: DaemonAgentSpecId, enabled: Boolean): IO[PersistenceError, Unit]        =
      ref.update(_ :+ s"enabled:${id.value}:$enabled")
    override def trigger(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                             = ZIO.unit
    override def triggerGovernance(projectId: ProjectId, triggerId: String): IO[PersistenceError, Unit] = ZIO.unit

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DaemonsControllerSpec")(
      test("GET /settings/daemons renders the daemon page") {
        for
          ref       <- Ref.make(List.empty[String])
          controller = DaemonsControllerLive(new StubScheduler(ref))
          response  <- controller.routes.runZIO(Request.get(URL(Path.decode("/settings/daemons"))))
          body      <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("Settings"),
          body.contains("Test Guardian"),
        )
      },
      test("POST /settings/daemons/:id/start delegates to the scheduler") {
        for
          ref       <- Ref.make(List.empty[String])
          controller = DaemonsControllerLive(new StubScheduler(ref))
          response  <- controller.routes.runZIO(
                         Request(method = Method.POST, url = URL(Path.decode("/settings/daemons/project-1__test-guardian/start")))
                       )
          calls     <- ref.get
        yield assertTrue(
          response.status == Status.SeeOther,
          calls == List("start:project-1__test-guardian"),
        )
      },
    )
