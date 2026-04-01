package project.entity

import java.time.Instant

import zio.*
import zio.json.*
import zio.test.*

import shared.ids.Ids.ProjectId

object ProjectModelsSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-13T09:00:00Z")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("ProjectModelsSpec")(
      test("Project settings round-trip through JSON") {
        val settings = ProjectSettings(
          defaultAgent = Some("codex"),
          mergePolicy = MergePolicy(requireCi = true, ciCommand = Some("sbt test")),
          analysisSchedule = Some(12.hours),
          promptTemplateDefaults = Map("review" -> "Focus on regressions"),
        )
        assertTrue(settings.toJson.fromJson[ProjectSettings] == Right(settings))
      },
      test("Project round-trips through JSON") {
        val project = Project(
          id = ProjectId("proj-1"),
          name = "Platform",
          description = Some("Shared runtime work"),
          settings = ProjectSettings(defaultAgent = Some("claude")),
          createdAt = now,
          updatedAt = now,
        )
        assertTrue(project.toJson.fromJson[Project] == Right(project))
      },
      test("Project folds create and update events") {
        val later  = now.plusSeconds(30)
        val events = List[ProjectEvent](
          ProjectEvent.ProjectCreated(
            projectId = ProjectId("proj-1"),
            name = "Platform",
            description = Some("Shared runtime work"),
            occurredAt = now,
          ),
          ProjectEvent.ProjectUpdated(
            projectId = ProjectId("proj-1"),
            name = "Platform Core",
            description = Some("Updated"),
            settings = ProjectSettings(
              defaultAgent = Some("codex"),
              mergePolicy = MergePolicy(requireCi = true, ciCommand = Some("sbt test")),
              analysisSchedule = Some(24.hours),
              promptTemplateDefaults = Map("review" -> "Check typed errors"),
            ),
            occurredAt = later,
          ),
        )

        val project = Project.fromEvents(events)

        assertTrue(
          project.exists(_.name == "Platform Core"),
          project.exists(_.settings.defaultAgent.contains("codex")),
          project.exists(_.updatedAt == later),
        )
      },
    )
