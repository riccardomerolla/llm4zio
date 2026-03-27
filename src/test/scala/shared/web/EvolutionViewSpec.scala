package shared.web

import java.time.Instant

import zio.test.*

import _root_.config.entity.WorkflowDefinition
import evolution.entity.*
import shared.ids.Ids.{ EvolutionProposalId, ProjectId }

object EvolutionViewSpec extends ZIOSpecDefault:

  private val proposal = EvolutionProposal(
    id = EvolutionProposalId("proposal-1"),
    projectId = ProjectId("project-1"),
    title = "Add governance trigger",
    rationale = "Improve ADE automation",
    target = EvolutionTarget.WorkflowDefinitionTarget(
      projectId = ProjectId("project-1"),
      workflow = WorkflowDefinition(
        id = Some("wf-1"),
        name = "Evolution Workflow",
        description = Some("Track changes"),
        steps = List("chat"),
        isBuiltin = false,
      ),
    ),
    template = Some(EvolutionTemplateKind.AddDaemonAgent),
    proposer = EvolutionAuditRecord("planner", "summary", Instant.parse("2026-03-27T08:00:00Z")),
    status = EvolutionProposalStatus.Applied,
    decisionId = None,
    createdAt = Instant.parse("2026-03-27T08:00:00Z"),
    updatedAt = Instant.parse("2026-03-27T09:00:00Z"),
  )

  def spec: Spec[Any, Nothing] =
    suite("EvolutionViewSpec")(
      test("page renders evolution proposals and status summary") {
        val html = EvolutionView.page(List(proposal))
        assertTrue(
          html.contains("Evolution"),
          html.contains("Applied"),
          html.contains("Add governance trigger"),
          html.contains("Workflow"),
          html.contains("project-1"),
        )
      }
    )
