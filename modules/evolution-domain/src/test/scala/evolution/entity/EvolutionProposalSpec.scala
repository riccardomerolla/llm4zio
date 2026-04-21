package evolution.entity

import java.time.Instant

import zio.test.*

import _root_.config.entity.WorkflowDefinition
import shared.ids.Ids.{ DecisionId, EvolutionProposalId, ProjectId }

object EvolutionProposalSpec extends ZIOSpecDefault:

  private val proposalId = EvolutionProposalId("proposal-1")
  private val projectId  = ProjectId("project-1")
  private val now        = Instant.parse("2026-03-26T12:00:00Z")

  def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("EvolutionProposalSpec")(
      test("fromEvents rebuilds approval application and rollback audit trail") {
        val target = EvolutionTarget.WorkflowDefinitionTarget(
          projectId = projectId,
          workflow = WorkflowDefinition(name = "custom-workflow", steps = List("chat", "test"), isBuiltin = false),
        )
        val events = List(
          EvolutionProposalEvent.Proposed(
            proposalId = proposalId,
            projectId = projectId,
            title = "Adjust workflow",
            rationale = "Need explicit test step",
            target = target,
            template = Some(EvolutionTemplateKind.ChangeTestingStrategy),
            proposedBy = "architect",
            summary = "Add a test step",
            decisionId = Some(DecisionId("decision-1")),
            occurredAt = now,
          ),
          EvolutionProposalEvent.Approved(
            proposalId = proposalId,
            projectId = projectId,
            decisionId = DecisionId("decision-1"),
            approvedBy = "reviewer",
            summary = "Approved",
            occurredAt = now.plusSeconds(60),
          ),
          EvolutionProposalEvent.Applied(
            proposalId = proposalId,
            projectId = projectId,
            appliedBy = "operator",
            summary = "Applied",
            baselineSnapshot = None,
            appliedSnapshot = EvolutionTargetSnapshot.WorkflowDefinitionState(projectId, target.workflow),
            occurredAt = now.plusSeconds(120),
          ),
          EvolutionProposalEvent.RolledBack(
            proposalId = proposalId,
            projectId = projectId,
            rolledBackBy = "operator",
            summary = "Rollback",
            rollbackSnapshot = EvolutionTargetSnapshot.WorkflowDefinitionState(projectId, target.workflow),
            occurredAt = now.plusSeconds(180),
          ),
        )

        val rebuilt = EvolutionProposal.fromEvents(events).toOption.get

        assertTrue(
          rebuilt.status == EvolutionProposalStatus.RolledBack,
          rebuilt.decisionId.contains(DecisionId("decision-1")),
          rebuilt.approval.exists(_.actor == "reviewer"),
          rebuilt.application.exists(_.actor == "operator"),
          rebuilt.rollback.exists(_.note == "Rollback"),
        )
      }
    )
