package knowledge.entity

import java.time.Instant

import zio.test.*

import shared.ids.Ids.{ DecisionLogId, IssueId }

object DecisionLogSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T12:00:00Z")

  def spec: Spec[Any, Nothing] =
    suite("DecisionLogSpec")(
      test("fromEvents rebuilds latest revision and keeps version history") {
        val logId  = DecisionLogId("decision-log-1")
        val events = List[DecisionLogEvent](
          DecisionLogEvent.Created(
            decisionLogId = logId,
            title = "Adopt event sourcing",
            context = "Need durable audit history",
            optionsConsidered = List(DecisionOption("CRUD", "Simpler updates")),
            decisionTaken = "Use event sourcing",
            rationale = "Keeps change history explicit",
            consequences = List("Extra repository complexity"),
            decisionDate = now,
            decisionMaker = DecisionMaker(DecisionMakerKind.Agent, "architect"),
            workspaceId = Some("ws-1"),
            issueIds = List(IssueId("issue-1")),
            designConstraints = List("Must keep replay deterministic"),
            occurredAt = now,
          ),
          DecisionLogEvent.Revised(
            decisionLogId = logId,
            version = 2,
            title = "Adopt event sourcing for knowledge",
            context = "Need durable audit history and revisions",
            optionsConsidered = List(
              DecisionOption("CRUD", "Simpler updates"),
              DecisionOption("Event sourcing", "Supports revisions", selected = true),
            ),
            decisionTaken = "Use event sourcing for structured knowledge records",
            rationale = "Supports revisions and history",
            consequences = List("More persistence code"),
            decisionDate = now.plusSeconds(30),
            decisionMaker = DecisionMaker(DecisionMakerKind.Agent, "architect"),
            workspaceId = Some("ws-1"),
            issueIds = List(IssueId("issue-1")),
            designConstraints = List("Must keep replay deterministic"),
            lessonsLearned = List("Repository snapshots keep reads fast"),
            occurredAt = now.plusSeconds(30),
          ),
        )

        val rebuilt = DecisionLog.fromEvents(events)

        assertTrue(
          rebuilt.exists(_.version == 2),
          rebuilt.exists(_.title == "Adopt event sourcing for knowledge"),
          rebuilt.exists(_.versions.map(_.version) == List(1, 2)),
          rebuilt.exists(_.lessonsLearned == List("Repository snapshots keep reads fast")),
        )
      }
    )
