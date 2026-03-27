package plan.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import issues.entity.{ AgentIssue, IssueState }
import plan.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ IssueId, PlanId, SpecificationId }
import shared.testfixtures.*
import specification.entity.*

object PlansControllerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T10:00:00Z")

  final private class StubPlanRepository(ref: Ref[Map[PlanId, List[PlanEvent]]]) extends PlanRepository:
    override def append(event: PlanEvent): IO[PersistenceError, Unit] =
      ref.update(current => current.updated(event.planId, current.getOrElse(event.planId, Nil) :+ event))

    override def get(id: PlanId): IO[PersistenceError, Plan] =
      ref.get.flatMap { current =>
        current.get(id) match
          case Some(events) =>
            ZIO.fromEither(Plan.fromEvents(events))
              .mapError(err => PersistenceError.SerializationFailed(s"plan:${id.value}", err))
          case None         => ZIO.fail(PersistenceError.NotFound("plan", id.value))
      }

    override def history(id: PlanId): IO[PersistenceError, List[PlanEvent]] =
      ref.get.map(_.getOrElse(id, Nil))

    override def list: IO[PersistenceError, List[Plan]] =
      ref.get.flatMap(current => ZIO.foreach(current.keys.toList)(get))

  final private class StubSpecificationRepository(specification: Specification) extends SpecificationRepository:
    override def append(event: SpecificationEvent): IO[PersistenceError, Unit]                                        = ZIO.unit
    override def get(id: SpecificationId): IO[PersistenceError, Specification]                                        = ZIO.succeed(specification)
    override def history(id: SpecificationId): IO[PersistenceError, List[SpecificationEvent]]                         = ZIO.succeed(Nil)
    override def list: IO[PersistenceError, List[Specification]]                                                      = ZIO.succeed(List(specification))
    override def diff(id: SpecificationId, fromVersion: Int, toVersion: Int): IO[PersistenceError, SpecificationDiff] =
      ZIO.fail(PersistenceError.QueryFailed("spec_diff", "unused"))

  private val planId          = PlanId("plan-1")
  private val specificationId = SpecificationId("spec-1")

  private val seededEvents = List[PlanEvent](
    PlanEvent.Created(
      planId = planId,
      conversationId = 42L,
      workspaceId = Some("ws-1"),
      specificationId = Some(specificationId),
      summary = "Planner plan",
      rationale = "Break it down",
      drafts = List(PlanTaskDraft("issue-1", "Model", "Create the model")),
      occurredAt = now.minusSeconds(60),
    ),
    PlanEvent.TasksCreated(
      planId = planId,
      issueIds = List(IssueId("issue-1")),
      occurredAt = now,
    ),
  )

  private val specification = Specification(
    id = specificationId,
    title = "Planner spec",
    content = "content",
    status = SpecificationStatus.Approved,
    version = 1,
    revisions = Nil,
    linkedIssueIds = List(IssueId("issue-1")),
    linkedPlanRef = Some("plan:plan-1"),
    author = SpecificationAuthor(SpecificationAuthorKind.Agent, "planner", "Planner Agent"),
    reviewComments = Nil,
    createdAt = now.minusSeconds(60),
    updatedAt = now,
  )

  private val linkedIssue = AgentIssue(
    id = IssueId("issue-1"),
    runId = None,
    conversationId = None,
    title = "Implement planner",
    description = "Build it",
    issueType = "task",
    priority = "high",
    requiredCapabilities = List("scala"),
    state = IssueState.Backlog(now),
    tags = List("plan:plan-1"),
    contextPath = "",
    sourceFolder = "",
    workspaceId = Some("ws-1"),
    externalRef = Some("spec:spec-1"),
    externalUrl = Some("/specifications/spec-1"),
  )

  private def makeRoutes(ref: Ref[Map[PlanId, List[PlanEvent]]]): Routes[Any, Response] =
    PlansController.routes(
      new StubPlanRepository(ref),
      new StubSpecificationRepository(specification),
      new StubIssueRepository(List(linkedIssue)),
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("PlansControllerSpec")(
      test("GET /plans renders the list page") {
        for
          ref   <- Ref.make(Map(planId -> seededEvents))
          routes = makeRoutes(ref)
          resp  <- routes.runZIO(Request.get(URL(Path.decode("/plans"))))
          body  <- resp.body.asString
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("Planner plan"),
          body.contains("/plans/plan-1"),
        )
      },
      test("GET /plans/:id renders detail page with linked issue and spec") {
        for
          ref   <- Ref.make(Map(planId -> seededEvents))
          routes = makeRoutes(ref)
          resp  <- routes.runZIO(Request.get(URL(Path.decode("/plans/plan-1"))))
          body  <- resp.body.asString
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("Open linked specification"),
          body.contains("Implement planner"),
          body.contains("Traceability"),
        )
      },
    )
