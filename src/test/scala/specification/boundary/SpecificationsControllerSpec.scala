package specification.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import issues.entity.{ AgentIssue, IssueState }
import shared.errors.PersistenceError
import shared.ids.Ids.{ IssueId, SpecificationId }
import shared.testfixtures.*
import specification.entity.*

object SpecificationsControllerSpec extends ZIOSpecDefault:

  private val now    = Instant.parse("2026-03-26T10:00:00Z")
  private val author = SpecificationAuthor(SpecificationAuthorKind.Agent, "planner", "Planner Agent")

  final private class StubSpecificationRepository(ref: Ref[Map[SpecificationId, List[SpecificationEvent]]])
    extends SpecificationRepository:
    override def append(event: SpecificationEvent): IO[PersistenceError, Unit] =
      ref.update(current =>
        current.updated(event.specificationId, current.getOrElse(event.specificationId, Nil) :+ event)
      )

    override def get(id: SpecificationId): IO[PersistenceError, Specification] =
      ref.get.flatMap { current =>
        current.get(id) match
          case Some(events) =>
            ZIO
              .fromEither(Specification.fromEvents(events))
              .mapError(err => PersistenceError.SerializationFailed(s"specification:${id.value}", err))
          case None         =>
            ZIO.fail(PersistenceError.NotFound("specification", id.value))
      }

    override def history(id: SpecificationId): IO[PersistenceError, List[SpecificationEvent]] =
      ref.get.map(_.getOrElse(id, Nil))

    override def list: IO[PersistenceError, List[Specification]] =
      ref.get.flatMap(current => ZIO.foreach(current.keys.toList)(get))

    override def diff(id: SpecificationId, fromVersion: Int, toVersion: Int): IO[PersistenceError, SpecificationDiff] =
      get(id).flatMap(spec =>
        ZIO
          .fromEither(Specification.diff(spec, fromVersion, toVersion))
          .mapError(err => PersistenceError.QueryFailed("specification_diff", err))
      )

  private val specificationId = SpecificationId("spec-1")

  private val seededEvents = List[SpecificationEvent](
    SpecificationEvent.Created(
      specificationId = specificationId,
      title = "Planner spec",
      content = "before",
      author = author,
      status = SpecificationStatus.Draft,
      linkedPlanRef = Some("planner:42"),
      occurredAt = now.minusSeconds(60),
    ),
    SpecificationEvent.Revised(
      specificationId = specificationId,
      version = 2,
      title = "Planner spec",
      beforeContent = "before",
      afterContent = "after",
      author = author,
      status = SpecificationStatus.InRefinement,
      linkedPlanRef = Some("planner:42"),
      occurredAt = now,
    ),
    SpecificationEvent.IssuesLinked(
      specificationId = specificationId,
      issueIds = List(IssueId("issue-1")),
      occurredAt = now,
    ),
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
    tags = List("spec:spec-1", "plan:planner:42"),
    contextPath = "",
    sourceFolder = "",
    workspaceId = Some("ws-1"),
    externalRef = Some("spec:spec-1"),
    externalUrl = Some("/specifications/spec-1"),
  )

  private def makeRoutes(
    specRef: Ref[Map[SpecificationId, List[SpecificationEvent]]],
    issues: List[AgentIssue] = List(linkedIssue),
  ): Routes[Any, Response] =
    SpecificationsController.make(
      new StubSpecificationRepository(specRef),
      new StubIssueRepository(issues),
    ).routes

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SpecificationsControllerSpec")(
      test("GET /specifications renders the list page") {
        for
          specRef <- Ref.make(Map(specificationId -> seededEvents))
          routes   = makeRoutes(specRef)
          resp    <- routes.runZIO(Request.get(URL(Path.decode("/specifications"))))
          body    <- resp.body.asString
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("Planner spec"),
          body.contains("planner:42"),
        )
      },
      test("GET /specifications/:id renders detail and default diff") {
        for
          specRef <- Ref.make(Map(specificationId -> seededEvents))
          routes   = makeRoutes(specRef)
          resp    <- routes.runZIO(Request.get(URL(Path.decode("/specifications/spec-1"))))
          body    <- resp.body.asString
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("Linked issues"),
          body.contains("Implement planner"),
          body.contains("Comparing version 1 to 2"),
        )
      },
      test("POST /specifications/:id/revise appends a revision and redirects") {
        for
          specRef <- Ref.make(Map(specificationId -> seededEvents))
          routes   = makeRoutes(specRef)
          req      = Request(
                       method = Method.POST,
                       url = URL(Path.decode("/specifications/spec-1/revise")),
                       body = Body.fromString("title=Planner+spec&content=updated+content"),
                     )
          resp    <- routes.runZIO(req)
          repo     = new StubSpecificationRepository(specRef)
          spec    <- repo.get(specificationId)
        yield assertTrue(
          resp.status == Status.SeeOther,
          spec.version == 3,
          spec.content == "updated content",
          spec.status == SpecificationStatus.InRefinement,
        )
      },
      test("POST /specifications/:id/approve marks the specification approved") {
        for
          specRef <- Ref.make(Map(specificationId -> seededEvents))
          routes   = makeRoutes(specRef)
          resp    <- routes.runZIO(
                       Request(method = Method.POST, url = URL(Path.decode("/specifications/spec-1/approve")))
                     )
          repo     = new StubSpecificationRepository(specRef)
          spec    <- repo.get(specificationId)
        yield assertTrue(
          resp.status == Status.SeeOther,
          spec.status == SpecificationStatus.Approved,
        )
      },
    )
