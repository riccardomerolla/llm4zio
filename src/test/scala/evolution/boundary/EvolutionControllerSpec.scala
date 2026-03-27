package evolution.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import _root_.config.entity.WorkflowDefinition
import evolution.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ EvolutionProposalId, ProjectId }

object EvolutionControllerSpec extends ZIOSpecDefault:

  private val proposal = EvolutionProposal(
    id = EvolutionProposalId("proposal-1"),
    projectId = ProjectId("project-1"),
    title = "Add daemon guardrail",
    rationale = "Need better automation",
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
    template = None,
    proposer = EvolutionAuditRecord("planner", "summary", Instant.parse("2026-03-27T08:00:00Z")),
    status = EvolutionProposalStatus.Proposed,
    decisionId = None,
    createdAt = Instant.parse("2026-03-27T08:00:00Z"),
    updatedAt = Instant.parse("2026-03-27T09:00:00Z"),
  )

  private val repository = new EvolutionProposalRepository:
    override def append(event: EvolutionProposalEvent): IO[PersistenceError, Unit]                                   = ZIO.unit
    override def get(id: shared.ids.Ids.EvolutionProposalId): IO[PersistenceError, EvolutionProposal]                =
      ZIO.succeed(proposal)
    override def history(id: shared.ids.Ids.EvolutionProposalId): IO[PersistenceError, List[EvolutionProposalEvent]] =
      ZIO.succeed(Nil)
    override def list(filter: EvolutionProposalFilter): IO[PersistenceError, List[EvolutionProposal]]                =
      ZIO.succeed(List(proposal))

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EvolutionControllerSpec")(
      test("GET /evolution renders the evolution page") {
        for
          response <- EvolutionController.routes(repository).runZIO(Request.get(URL(Path.decode("/evolution"))))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("Evolution"),
          body.contains("Add daemon guardrail"),
        )
      }
    )
