package shared.web

import java.time.Instant

import zio.*
import zio.test.*

import daemon.boundary.DaemonsView
import daemon.entity.*
import shared.ids.Ids.{ DaemonAgentSpecId, ProjectId }

object DaemonsViewSpec extends ZIOSpecDefault:

  private val statuses = List(
    DaemonAgentStatus(
      spec = DaemonAgentSpec(
        id = DaemonAgentSpecId("project-1__test-guardian"),
        daemonKey = DaemonAgentSpec.TestGuardianKey,
        projectId = ProjectId("project-1"),
        name = "Test Guardian",
        purpose = "Watch CI failures",
        trigger = DaemonTriggerCondition.ScheduledWithEvent(30.minutes, "test-guardian"),
        workspaceIds = List("ws-1"),
        agentName = "code-agent",
        prompt = "Inspect failures",
        limits = DaemonExecutionLimits(maxIssuesPerRun = 2),
        builtIn = true,
        governed = true,
      ),
      enabled = true,
      runtime = DaemonAgentRuntime(
        health = DaemonHealth.Healthy,
        lifecycle = DaemonLifecycle.Running,
        completedAt = Some(Instant.parse("2026-03-26T12:00:00Z")),
        issuesCreated = 3,
        lastSummary = Some("Created 1 issue"),
      ),
    )
  )

  def spec: Spec[Any, Nothing] =
    suite("DaemonsViewSpec")(
      test("page renders daemon cards and management actions") {
        val html = DaemonsView.page(statuses)
        assertTrue(
          html.contains("Settings"),
          html.contains("Test Guardian"),
          html.contains("/settings/daemons/project-1__test-guardian/start"),
          html.contains("Issues Created"),
          html.contains("Governed"),
        )
      }
    )
