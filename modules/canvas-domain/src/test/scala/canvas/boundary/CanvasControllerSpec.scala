package canvas.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import canvas.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ CanvasId, ProjectId }

object CanvasControllerSpec extends ZIOSpecDefault:

  private val now    = Instant.parse("2026-04-29T15:00:00Z")
  private val author = CanvasAuthor(CanvasAuthorKind.Human, "alice", "Alice")

  private def section(content: String): CanvasSection = CanvasSection(content, author, now)

  private def buildCanvas(
    id: String,
    title: String,
    projectId: String = "proj-1",
    status: CanvasStatus = CanvasStatus.Draft,
  ): ReasonsCanvas =
    ReasonsCanvas(
      id = CanvasId(id),
      projectId = ProjectId(projectId),
      storyIssueId = None,
      analysisId = None,
      specificationId = None,
      normProfileId = None,
      safeguardProfileId = None,
      title = title,
      sections = ReasonsSections(
        requirements = section("R: requirements text"),
        entities = section("E: Plan, UsageEvent"),
        approach = section("A: pure pricing function"),
        structure = section("S: billing-domain"),
        operations = section("O: op-001 calculate"),
        norms = section("N: BigDecimal HALF_UP"),
        safeguards = section("SG-1 idempotency"),
      ),
      status = status,
      version = 1,
      revisions = Nil,
      linkedTaskRunIds = Nil,
      author = author,
      createdAt = now,
      updatedAt = now,
    )

  private def stubRepo(canvases: List[ReasonsCanvas]): ReasonsCanvasRepository =
    new ReasonsCanvasRepository:
      override def append(event: CanvasEvent): IO[PersistenceError, Unit] = ZIO.unit
      override def get(id: CanvasId): IO[PersistenceError, ReasonsCanvas] =
        ZIO.fromOption(canvases.find(_.id == id)).orElseFail(PersistenceError.NotFound("canvas", id.value))
      override def history(id: CanvasId): IO[PersistenceError, List[CanvasEvent]] = ZIO.succeed(Nil)
      override def list: IO[PersistenceError, List[ReasonsCanvas]]                = ZIO.succeed(canvases)

  private def runReq(routes: Routes[Any, Response], req: Request): ZIO[Scope, Nothing, Response] =
    routes.runZIO(req)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("CanvasControllerSpec")(
      test("GET /canvases returns 200 with HTML containing canvas titles") {
        val controller = CanvasController.make(stubRepo(List(buildCanvas("c-1", "Multi-plan billing"))))
        for
          response <- runReq(controller.routes, Request.get(URL.decode("/canvases").toOption.get))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          response.headers.get(Header.ContentType).exists(_.mediaType.fullType.startsWith("text/html")),
          body.contains("Multi-plan billing"),
          body.contains("REASONS Canvases"),
        )
      },
      test("GET /canvases?projectId=other returns 200 with empty state") {
        val controller = CanvasController.make(stubRepo(List(buildCanvas("c-1", "Multi-plan billing"))))
        for
          response <- runReq(
                        controller.routes,
                        Request.get(URL.decode("/canvases?projectId=other").toOption.get),
                      )
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("No canvases match"),
          !body.contains("Multi-plan billing"),
        )
      },
      test("GET /canvases/:id returns 200 with HTML containing all 7 section labels") {
        val controller = CanvasController.make(stubRepo(List(buildCanvas("c-1", "Multi-plan billing"))))
        for
          response <- runReq(controller.routes, Request.get(URL.decode("/canvases/c-1").toOption.get))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("R — Requirements"),
          body.contains("E — Entities"),
          body.contains("A — Approach"),
          body.contains("S — Structure"),
          body.contains("O — Operations"),
          body.contains("N — Norms"),
          body.contains("S — Safeguards"),
          body.contains("Multi-plan billing"),
          body.contains("Revision history"),
        )
      },
      test("GET /canvases/:id with unknown id returns 404") {
        val controller = CanvasController.make(stubRepo(Nil))
        for
          response <- runReq(controller.routes, Request.get(URL.decode("/canvases/missing").toOption.get))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.NotFound,
          body.contains("missing"),
        )
      },
    )
