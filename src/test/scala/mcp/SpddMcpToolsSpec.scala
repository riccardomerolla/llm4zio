package mcp

import zio.*
import zio.json.ast.Json
import zio.test.*

import canvas.entity.*
import llm4zio.tools.{ Tool, ToolExecutionError }
import prompts.PromptLoader
import shared.errors.PersistenceError
import shared.ids.Ids.{ CanvasId, TaskRunId }

object SpddMcpToolsSpec extends ZIOSpecDefault:

  // ── In-memory repository (event-sourced replay) ──────────────────────────

  private def inMemoryCanvasRepo: UIO[ReasonsCanvasRepository] =
    Ref.make(Map.empty[CanvasId, List[CanvasEvent]]).map { ref =>
      new ReasonsCanvasRepository:
        override def append(event: CanvasEvent): IO[PersistenceError, Unit] =
          ref.update(m => m.updated(event.canvasId, m.getOrElse(event.canvasId, Nil) :+ event))

        override def get(id: CanvasId): IO[PersistenceError, ReasonsCanvas] =
          ref.get.flatMap { m =>
            m.get(id) match
              case None | Some(Nil) =>
                ZIO.fail(PersistenceError.NotFound("canvas", id.value))
              case Some(events)     =>
                ZIO
                  .fromEither(ReasonsCanvas.fromEvents(events))
                  .mapError(err => PersistenceError.SerializationFailed(s"canvas:${id.value}", err))
          }

        override def history(id: CanvasId): IO[PersistenceError, List[CanvasEvent]] =
          ref.get.map(_.getOrElse(id, Nil))

        override def list: IO[PersistenceError, List[ReasonsCanvas]] =
          ref.get.flatMap { m =>
            ZIO
              .foreach(m.values.toList) { events =>
                ZIO
                  .fromEither(ReasonsCanvas.fromEvents(events))
                  .mapError(err => PersistenceError.SerializationFailed("canvas:list", err))
              }
              .map(_.sortBy(_.updatedAt)(using Ordering[java.time.Instant].reverse))
          }
    }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def tool(name: String, tools: SpddMcpTools): Tool =
    tools.all.find(_.name == name).getOrElse(throw new AssertionError(s"tool $name not registered"))

  private def jstr(j: Json, key: String): String = j match
    case Json.Obj(fields) =>
      fields.toMap.get(key) match
        case Some(Json.Str(v)) => v
        case _                 => throw new AssertionError(s"missing string field $key")
    case _                => throw new AssertionError("not an object")

  private def jobj(j: Json, key: String): Json.Obj = j match
    case Json.Obj(fields) =>
      fields.toMap.get(key) match
        case Some(o: Json.Obj) => o
        case _                 => throw new AssertionError(s"missing object field $key")
    case _                => throw new AssertionError("not an object")

  private def author(kind: String = "agent", id: String = "spdd-architect", name: String = "SPDD Architect")
    : Seq[(String, Json)] =
    Seq(
      "authorKind"        -> Json.Str(kind),
      "authorId"          -> Json.Str(id),
      "authorDisplayName" -> Json.Str(name),
    )

  private def obj(fields: (String, Json)*): Json.Obj = Json.Obj(fields *)

  private def arr(json: Json): Chunk[Json] = json match
    case Json.Obj(fs) =>
      fs.find(_._1 == "canvases").map(_._2) match
        case Some(Json.Arr(values)) => values
        case _                      => throw new AssertionError("missing canvases array")
    case _            => throw new AssertionError("not an object")

  private def sectionsJson(opsContent: String = "O: op-001 calculate"): Json.Obj =
    Json.Obj(
      "requirements" -> Json.Str("R: charge correctly"),
      "entities"     -> Json.Str("E: Plan, UsageEvent"),
      "approach"     -> Json.Str("A: pure pricing function"),
      "structure"    -> Json.Str("S: billing-domain"),
      "operations"   -> Json.Str(opsContent),
      "norms"        -> Json.Str("N: BigDecimal HALF_UP"),
      "safeguards"   -> Json.Str("SG-1 idempotency"),
    )

  private def createCanvas(tools: SpddMcpTools): IO[ToolExecutionError, String] =
    val args = obj(
      (Seq[(String, Json)](
        "projectId" -> Json.Str("proj-1"),
        "title"     -> Json.Str("Multi-plan billing"),
        "sections"  -> sectionsJson(),
      ) ++ author()) *
    )
    tool("spdd_canvas_create", tools).execute(args).map(jstr(_, "canvasId"))

  // ── Spec ─────────────────────────────────────────────────────────────────

  def spec: Spec[TestEnvironment, Any] =
    suite("SpddMcpToolsSpec")(
      test("exposes exactly the 8 SPDD tools") {
        for
          repo  <- inMemoryCanvasRepo
          loader <- ZIO.service[PromptLoader]
          tools = SpddMcpTools(loader, repo)
        yield assertTrue(
          tools.all.map(_.name).toSet == Set(
            "spdd_render_prompt",
            "spdd_canvas_create",
            "spdd_canvas_get",
            "spdd_canvas_list",
            "spdd_canvas_update_sections",
            "spdd_canvas_approve",
            "spdd_canvas_mark_stale",
            "spdd_canvas_link_run",
          )
        )
      },
      test("spdd_render_prompt loads and interpolates a template") {
        for
          repo   <- inMemoryCanvasRepo
          loader <- ZIO.service[PromptLoader]
          tools   = SpddMcpTools(loader, repo)
          args    = Json.Obj(
                      "name"    -> Json.Str("spdd-api-test"),
                      "context" -> Json.Obj("canvas" -> Json.Str("__SENTINEL__")),
                    )
          result <- tool("spdd_render_prompt", tools).execute(args)
        yield assertTrue(
          jstr(result, "name") == "spdd-api-test",
          jstr(result, "rendered").contains("__SENTINEL__"),
          !jstr(result, "rendered").contains("{{"),
        )
      },
      test("spdd_render_prompt surfaces missing-prompt as ExecutionFailed") {
        for
          repo   <- inMemoryCanvasRepo
          loader <- ZIO.service[PromptLoader]
          tools   = SpddMcpTools(loader, repo)
          args    = Json.Obj("name" -> Json.Str("nope-no-such-template"), "context" -> Json.Obj())
          result <- tool("spdd_render_prompt", tools).execute(args).either
        yield assertTrue(
          result match
            case Left(ToolExecutionError.ExecutionFailed(msg)) => msg.contains("nope-no-such-template")
            case _                                             => false
        )
      },
      test("spdd_canvas_create persists Created event and returns Draft@1") {
        for
          repo      <- inMemoryCanvasRepo
          loader    <- ZIO.service[PromptLoader]
          tools      = SpddMcpTools(loader, repo)
          canvasId  <- createCanvas(tools)
          history   <- repo.history(CanvasId(canvasId))
          canvas    <- repo.get(CanvasId(canvasId))
        yield assertTrue(
          history.size == 1,
          history.head.isInstanceOf[CanvasEvent.Created],
          canvas.status == CanvasStatus.Draft,
          canvas.version == 1,
          canvas.sections.operations.content == "O: op-001 calculate",
        )
      },
      test("spdd_canvas_get returns the rendered canvas with all 7 sections") {
        for
          repo     <- inMemoryCanvasRepo
          loader   <- ZIO.service[PromptLoader]
          tools     = SpddMcpTools(loader, repo)
          canvasId <- createCanvas(tools)
          result   <- tool("spdd_canvas_get", tools).execute(Json.Obj("canvasId" -> Json.Str(canvasId)))
        yield assertTrue(
          jstr(result, "canvasId") == canvasId,
          jstr(result, "status") == "Draft",
          jstr(jobj(result, "sections"), "requirements") == "R: charge correctly",
          jstr(jobj(result, "sections"), "safeguards") == "SG-1 idempotency",
        )
      },
      test("spdd_canvas_list returns canvases, optionally filtered by projectId") {
        for
          repo     <- inMemoryCanvasRepo
          loader   <- ZIO.service[PromptLoader]
          tools     = SpddMcpTools(loader, repo)
          _        <- createCanvas(tools)
          all      <- tool("spdd_canvas_list", tools).execute(Json.Obj())
          filtered <- tool("spdd_canvas_list", tools).execute(Json.Obj("projectId" -> Json.Str("proj-1")))
          empty    <- tool("spdd_canvas_list", tools).execute(Json.Obj("projectId" -> Json.Str("nope")))
        yield assertTrue(
          arr(all).size == 1,
          arr(filtered).size == 1,
          arr(empty).isEmpty,
        )
      },
      test("spdd_canvas_update_sections bumps version and lists which sections changed") {
        for
          repo     <- inMemoryCanvasRepo
          loader   <- ZIO.service[PromptLoader]
          tools     = SpddMcpTools(loader, repo)
          canvasId <- createCanvas(tools)
          args      = obj(
                        (Seq[(String, Json)](
                          "canvasId"  -> Json.Str(canvasId),
                          "rationale" -> Json.Str("review feedback: reword Operations"),
                          "updates"   -> Json.Arr(
                            Chunk(
                              Json.Obj(
                                "sectionId" -> Json.Str("operations"),
                                "content"   -> Json.Str("O: op-001 + op-002"),
                              ),
                              Json.Obj(
                                "sectionId" -> Json.Str("norms"),
                                "content"   -> Json.Str("N: BigDecimal HALF_UP @ 4dp"),
                              ),
                            )
                          ),
                        ) ++ author()) *
                      )
          result   <- tool("spdd_canvas_update_sections", tools).execute(args)
          canvas   <- repo.get(CanvasId(canvasId))
        yield assertTrue(
          jstr(result, "status") == "Draft",
          canvas.version == 2,
          canvas.sections.operations.content == "O: op-001 + op-002",
          canvas.sections.norms.content == "N: BigDecimal HALF_UP @ 4dp",
          canvas.sections.requirements.content == "R: charge correctly",
        )
      },
      test("spdd_canvas_update_sections rejects empty updates array") {
        for
          repo     <- inMemoryCanvasRepo
          loader   <- ZIO.service[PromptLoader]
          tools     = SpddMcpTools(loader, repo)
          canvasId <- createCanvas(tools)
          args      = obj(
                        (Seq[(String, Json)](
                          "canvasId" -> Json.Str(canvasId),
                          "updates"  -> Json.Arr(Chunk.empty),
                        ) ++ author()) *
                      )
          result   <- tool("spdd_canvas_update_sections", tools).execute(args).either
        yield assertTrue(
          result match
            case Left(ToolExecutionError.InvalidParameters(msg)) => msg.contains("at least one")
            case _                                               => false
        )
      },
      test("spdd_canvas_approve transitions Draft -> Approved") {
        for
          repo     <- inMemoryCanvasRepo
          loader   <- ZIO.service[PromptLoader]
          tools     = SpddMcpTools(loader, repo)
          canvasId <- createCanvas(tools)
          args      = obj(
                        (Seq[(String, Json)]("canvasId" -> Json.Str(canvasId)) ++ author("human", "alice", "Alice")) *
                      )
          result   <- tool("spdd_canvas_approve", tools).execute(args)
          canvas   <- repo.get(CanvasId(canvasId))
        yield assertTrue(
          jstr(result, "status") == "Approved",
          canvas.status == CanvasStatus.Approved,
        )
      },
      test("section update after approval knocks the canvas back to InReview (golden rule)") {
        for
          repo     <- inMemoryCanvasRepo
          loader   <- ZIO.service[PromptLoader]
          tools     = SpddMcpTools(loader, repo)
          canvasId <- createCanvas(tools)
          _        <- tool("spdd_canvas_approve", tools)
                        .execute(obj((Seq[(String, Json)]("canvasId" -> Json.Str(canvasId)) ++ author()) *))
          updateArgs = obj(
                         (Seq[(String, Json)](
                           "canvasId" -> Json.Str(canvasId),
                           "updates"  -> Json.Arr(
                             Chunk(
                               Json.Obj(
                                 "sectionId" -> Json.Str("approach"),
                                 "content"   -> Json.Str("A: switch to Strategy pattern"),
                               )
                             )
                           ),
                         ) ++ author()) *
                       )
          result   <- tool("spdd_canvas_update_sections", tools).execute(updateArgs)
        yield assertTrue(jstr(result, "status") == "InReview")
      },
      test("spdd_canvas_mark_stale flips status to Stale and records the reason") {
        for
          repo     <- inMemoryCanvasRepo
          loader   <- ZIO.service[PromptLoader]
          tools     = SpddMcpTools(loader, repo)
          canvasId <- createCanvas(tools)
          args      = obj(
                        (Seq[(String, Json)](
                          "canvasId" -> Json.Str(canvasId),
                          "reason"   -> Json.Str("downstream code refactored; canvas Operations no longer match"),
                        ) ++ author()) *
                      )
          result   <- tool("spdd_canvas_mark_stale", tools).execute(args)
          canvas   <- repo.get(CanvasId(canvasId))
        yield assertTrue(
          jstr(result, "status") == "Stale",
          canvas.status == CanvasStatus.Stale,
        )
      },
      test("spdd_canvas_link_run records taskRunId and dedupes on repeat") {
        for
          repo     <- inMemoryCanvasRepo
          loader   <- ZIO.service[PromptLoader]
          tools     = SpddMcpTools(loader, repo)
          canvasId <- createCanvas(tools)
          _        <- tool("spdd_canvas_link_run", tools)
                        .execute(Json.Obj("canvasId" -> Json.Str(canvasId), "taskRunId" -> Json.Str("run-1")))
          _        <- tool("spdd_canvas_link_run", tools)
                        .execute(Json.Obj("canvasId" -> Json.Str(canvasId), "taskRunId" -> Json.Str("run-1")))
          _        <- tool("spdd_canvas_link_run", tools)
                        .execute(Json.Obj("canvasId" -> Json.Str(canvasId), "taskRunId" -> Json.Str("run-2")))
          canvas   <- repo.get(CanvasId(canvasId))
        yield assertTrue(
          canvas.linkedTaskRunIds == List(TaskRunId("run-1"), TaskRunId("run-2"))
        )
      },
      test("spdd_canvas_get on missing canvas returns ExecutionFailed") {
        for
          repo   <- inMemoryCanvasRepo
          loader <- ZIO.service[PromptLoader]
          tools   = SpddMcpTools(loader, repo)
          result <- tool("spdd_canvas_get", tools)
                      .execute(Json.Obj("canvasId" -> Json.Str("nope")))
                      .either
        yield assertTrue(
          result match
            case Left(ToolExecutionError.ExecutionFailed(msg)) => msg.contains("nope")
            case _                                             => false
        )
      },
    ).provide(PromptLoader.live)
