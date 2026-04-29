package canvas.control

import java.time.Instant

import zio.*
import zio.test.*

import canvas.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ CanvasId, ProjectId }

object CanvasSimilarityIndexSpec extends ZIOSpecDefault:

  private val now    = Instant.parse("2026-04-29T14:00:00Z")
  private val author = CanvasAuthor(CanvasAuthorKind.Human, "alice", "Alice")

  private def section(content: String): CanvasSection = CanvasSection(content, author, now)

  private def canvas(
    id: String,
    title: String,
    operations: String,
    status: CanvasStatus = CanvasStatus.Approved,
    projectId: String = "proj-1",
    requirements: String = "billing requirements",
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
        requirements = section(requirements),
        entities = section("Plan UsageEvent UsageLedger"),
        approach = section("pure pricing function strategy"),
        structure = section("billing-domain BCE module"),
        operations = section(operations),
        norms = section("BigDecimal HALF_UP"),
        safeguards = section("idempotency"),
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

  def spec: Spec[Any, Any] =
    suite("CanvasSimilarityIndex")(
      test("tokenize lowercases, splits on non-alpha, drops short tokens and stop-words") {
        val tokens = CanvasSimilarityIndex.tokenize("Multi-Plan Billing for the Standard customer")
        assertTrue(tokens == Set("multi", "plan", "billing", "standard", "customer"))
      },
      test("jaccard returns 0 on empty union and 1 on identical sets") {
        assertTrue(
          CanvasSimilarityIndex.jaccard(Set.empty, Set.empty) == 0.0,
          CanvasSimilarityIndex.jaccard(Set("a", "b"), Set("a", "b")) == 1.0,
        )
      },
      test("findSimilar returns approved Canvases with positive Jaccard score, sorted by score desc") {
        val billing      = canvas("c-billing", "Multi-plan billing", "calculate quota overage")
        val unrelated    = canvas("c-other", "Telegram channel", "deliver message")
        val sortedByScore =
          for
            index <- ZIO.service[CanvasSimilarityIndex]
            hits  <- index.findSimilar("multi-plan billing quota overage")
          yield hits

        sortedByScore.provide(ZLayer.succeed(stubRepo(List(billing, unrelated))), CanvasSimilarityIndex.live).map { hits =>
          assertTrue(
            hits.size == 2,
            hits.head.canvasId == CanvasId("c-billing"),
            hits.head.score > hits(1).score,
            hits.last.canvasId == CanvasId("c-other"),
            hits.head.matchedTerms.contains("billing"),
          )
        }
      },
      test("findSimilar excludes Draft, Stale, and Superseded Canvases") {
        val approved   = canvas("c-1", "Multi-plan billing", "calculate quota overage")
        val draft      = canvas("c-2", "Multi-plan billing draft", "calculate quota overage", CanvasStatus.Draft)
        val stale      = canvas("c-3", "Multi-plan billing stale", "calculate quota overage", CanvasStatus.Stale)
        val superseded =
          canvas("c-4", "Multi-plan billing superseded", "calculate quota overage", CanvasStatus.Superseded)

        val program =
          for
            index <- ZIO.service[CanvasSimilarityIndex]
            hits  <- index.findSimilar("multi-plan billing")
          yield hits

        program
          .provide(
            ZLayer.succeed(stubRepo(List(approved, draft, stale, superseded))),
            CanvasSimilarityIndex.live,
          )
          .map(hits => assertTrue(hits.size == 1, hits.head.canvasId == CanvasId("c-1")))
      },
      test("findSimilar respects the projectId filter") {
        val billing     = canvas("c-1", "Multi-plan billing", "calculate quota overage", projectId = "proj-1")
        val billingProj2 = canvas("c-2", "Multi-plan billing v2", "calculate quota overage", projectId = "proj-2")

        val program =
          for
            index    <- ZIO.service[CanvasSimilarityIndex]
            hits     <- index.findSimilar("multi-plan billing", projectId = Some(ProjectId("proj-2")))
          yield hits

        program
          .provide(
            ZLayer.succeed(stubRepo(List(billing, billingProj2))),
            CanvasSimilarityIndex.live,
          )
          .map(hits => assertTrue(hits.size == 1, hits.head.canvasId == CanvasId("c-2")))
      },
      test("findSimilar honours the limit") {
        val a = canvas("c-1", "Multi-plan billing alpha", "calculate quota overage")
        val b = canvas("c-2", "Multi-plan billing beta", "calculate quota overage")
        val c = canvas("c-3", "Multi-plan billing gamma", "calculate quota overage")

        val program =
          for
            index <- ZIO.service[CanvasSimilarityIndex]
            hits  <- index.findSimilar("multi-plan billing quota", limit = 2)
          yield hits

        program
          .provide(
            ZLayer.succeed(stubRepo(List(a, b, c))),
            CanvasSimilarityIndex.live,
          )
          .map(hits => assertTrue(hits.size == 2))
      },
      test("findSimilar on an empty/all-stop-word query returns Nil") {
        val any = canvas("c-1", "Multi-plan billing", "calculate quota overage")

        val program =
          for
            index <- ZIO.service[CanvasSimilarityIndex]
            empty <- index.findSimilar("")
            stop  <- index.findSimilar("the and for")
          yield (empty, stop)

        program
          .provide(
            ZLayer.succeed(stubRepo(List(any))),
            CanvasSimilarityIndex.live,
          )
          .map { case (empty, stop) =>
            assertTrue(empty.isEmpty, stop.isEmpty)
          }
      },
    )
