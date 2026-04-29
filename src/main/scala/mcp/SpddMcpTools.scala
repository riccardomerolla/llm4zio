package mcp

import zio.*
import zio.json.*
import zio.json.ast.Json

import canvas.control.CanvasSimilarityIndex
import canvas.entity.*
import llm4zio.tools.{ Tool, ToolExecutionError }
import mcp.GatewayMcpToolSupport.*
import prompts.{ PromptError, PromptLoader }
import shared.errors.PersistenceError
import shared.ids.Ids.{
  AnalysisDocId,
  CanvasId,
  IssueId,
  NormProfileId,
  ProjectId,
  SafeguardProfileId,
  SpecificationId,
  TaskRunId,
}

/** SPDD MCP tools — the gateway-side runtime for Structured-Prompt-Driven Development.
  *
  * Design note: SPDD tools are **persistence verbs + a prompt renderer**. They do NOT call an LLM internally. MCP tools
  * are invoked *by* an LLM (typically Claude), so an embedded LLM call would be a recursive call and would force the
  * gateway to host its own LLM client. Instead, the calling agent uses `spdd_render_prompt` to obtain the system
  * prompt for stage X, runs the prompt itself, then calls one of the persistence tools (`spdd_canvas_create`,
  * `spdd_canvas_update_sections`, etc.) to commit the result.
  */
final class SpddMcpTools(
  promptLoader: PromptLoader,
  canvasRepo: ReasonsCanvasRepository,
  similarityIndex: CanvasSimilarityIndex,
):

  // ── Helpers ─────────────────────────────────────────────────────────────

  private def authorFromCanvasArgs(args: Json): IO[ToolExecutionError, CanvasAuthor] =
    for
      kindRaw     <- fieldStr(args, "authorKind")
      authorId    <- fieldStr(args, "authorId")
      displayName <- fieldStr(args, "authorDisplayName")
      kind        <- ZIO
                       .fromEither(kindRaw.trim.toLowerCase match
                         case "human" => Right(CanvasAuthorKind.Human)
                         case "agent" => Right(CanvasAuthorKind.Agent)
                         case other   => Left(s"Unknown canvas author kind: $other")
                       )
                       .mapError(ToolExecutionError.InvalidParameters.apply)
    yield CanvasAuthor(kind, authorId, displayName)

  private def parseSectionId(raw: String): Either[String, CanvasSectionId] =
    raw.trim.toLowerCase match
      case "requirements" => Right(CanvasSectionId.Requirements)
      case "entities"     => Right(CanvasSectionId.Entities)
      case "approach"     => Right(CanvasSectionId.Approach)
      case "structure"    => Right(CanvasSectionId.Structure)
      case "operations"   => Right(CanvasSectionId.Operations)
      case "norms"        => Right(CanvasSectionId.Norms)
      case "safeguards"   => Right(CanvasSectionId.Safeguards)
      case other          => Left(s"Unknown canvas section: $other")

  private def sectionIdName(id: CanvasSectionId): String = id match
    case CanvasSectionId.Requirements => "Requirements"
    case CanvasSectionId.Entities     => "Entities"
    case CanvasSectionId.Approach     => "Approach"
    case CanvasSectionId.Structure    => "Structure"
    case CanvasSectionId.Operations   => "Operations"
    case CanvasSectionId.Norms        => "Norms"
    case CanvasSectionId.Safeguards   => "Safeguards"

  private def liftPersistence[A](io: IO[PersistenceError, A], label: String): IO[ToolExecutionError, A] =
    io.mapError(err => ToolExecutionError.ExecutionFailed(s"$label: $err"))

  private def liftPrompt(io: IO[PromptError, String], label: String): IO[ToolExecutionError, String] =
    io.mapError(err => ToolExecutionError.ExecutionFailed(s"$label: ${err.message}"))

  private def renderCanvas(canvas: ReasonsCanvas): Json =
    Json.Obj(
      "canvasId"   -> Json.Str(canvas.id.value),
      "projectId"  -> Json.Str(canvas.projectId.value),
      "title"      -> Json.Str(canvas.title),
      "status"     -> Json.Str(canvas.status.toString),
      "version"    -> Json.Num(BigDecimal(canvas.version)),
      "sections"   -> Json.Obj(
        "requirements" -> Json.Str(canvas.sections.requirements.content),
        "entities"     -> Json.Str(canvas.sections.entities.content),
        "approach"     -> Json.Str(canvas.sections.approach.content),
        "structure"    -> Json.Str(canvas.sections.structure.content),
        "operations"   -> Json.Str(canvas.sections.operations.content),
        "norms"        -> Json.Str(canvas.sections.norms.content),
        "safeguards"   -> Json.Str(canvas.sections.safeguards.content),
      ),
      "links"      -> Json.Obj(
        "storyIssueId"       -> canvas.storyIssueId.fold[Json](Json.Null)(id => Json.Str(id.value)),
        "analysisId"         -> canvas.analysisId.fold[Json](Json.Null)(id => Json.Str(id.value)),
        "specificationId"    -> canvas.specificationId.fold[Json](Json.Null)(id => Json.Str(id.value)),
        "normProfileId"      -> canvas.normProfileId.fold[Json](Json.Null)(id => Json.Str(id.value)),
        "safeguardProfileId" -> canvas.safeguardProfileId.fold[Json](Json.Null)(id => Json.Str(id.value)),
        "taskRunIds"         -> Json.Arr(Chunk.fromIterable(canvas.linkedTaskRunIds.map(id => Json.Str(id.value)))),
      ),
      "updatedAt"  -> Json.Str(canvas.updatedAt.toString),
      "createdAt"  -> Json.Str(canvas.createdAt.toString),
      "revisions"  -> Json.Num(BigDecimal(canvas.revisions.size)),
    )

  // ── 1. spdd_render_prompt ──────────────────────────────────────────────

  private val renderPromptTool: Tool = Tool(
    name = "spdd_render_prompt",
    description =
      "Render an SPDD system prompt template (spdd-story, spdd-analysis, spdd-reasons-canvas, spdd-generate, " +
        "spdd-api-test, spdd-prompt-update, spdd-sync, spdd-unit-test) by interpolating placeholders. The caller " +
        "(an LLM agent) then runs the rendered prompt itself.",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "name"    -> Json.Obj(
          "type"        -> Json.Str("string"),
          "description" -> Json.Str("Template name without the .md suffix (e.g. 'spdd-reasons-canvas')."),
        ),
        "context" -> Json.Obj(
          "type"                 -> Json.Str("object"),
          "additionalProperties" -> Json.Obj("type" -> Json.Str("string")),
          "description"          -> Json.Str("Map of placeholder name to string value."),
        ),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("name"), Json.Str("context"))),
    ),
    execute = args =>
      for
        name    <- fieldStr(args, "name")
        ctxJson <- fieldObj(args, "context")
        ctxMap   = ctxJson.fields.toMap.collect { case (k, Json.Str(v)) => k -> v }
        rendered <- liftPrompt(promptLoader.load(name, ctxMap), s"render $name")
      yield Json.Obj(
        "name"     -> Json.Str(name),
        "rendered" -> Json.Str(rendered),
      ),
  )

  // ── 2. spdd_canvas_create ──────────────────────────────────────────────

  private val canvasCreateTool: Tool = Tool(
    name = "spdd_canvas_create",
    description =
      "Persist a new ReasonsCanvas in Draft status. Caller supplies all 7 sections; optional links to story/" +
        "analysis/specification/norm-profile/safeguard-profile. Returns the new canvasId.",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "projectId"          -> primitiveSchema("string"),
        "title"              -> primitiveSchema("string"),
        "sections"           -> Json.Obj(
          "type"       -> Json.Str("object"),
          "properties" -> Json.Obj(
            "requirements" -> primitiveSchema("string"),
            "entities"     -> primitiveSchema("string"),
            "approach"     -> primitiveSchema("string"),
            "structure"    -> primitiveSchema("string"),
            "operations"   -> primitiveSchema("string"),
            "norms"        -> primitiveSchema("string"),
            "safeguards"   -> primitiveSchema("string"),
          ),
          "required"   -> Json.Arr(
            Chunk(
              Json.Str("requirements"),
              Json.Str("entities"),
              Json.Str("approach"),
              Json.Str("structure"),
              Json.Str("operations"),
              Json.Str("norms"),
              Json.Str("safeguards"),
            )
          ),
        ),
        "storyIssueId"       -> primitiveSchema("string"),
        "analysisId"         -> primitiveSchema("string"),
        "specificationId"    -> primitiveSchema("string"),
        "normProfileId"      -> primitiveSchema("string"),
        "safeguardProfileId" -> primitiveSchema("string"),
        "authorKind"         -> primitiveSchema("string"),
        "authorId"           -> primitiveSchema("string"),
        "authorDisplayName"  -> primitiveSchema("string"),
      ),
      "required"   -> Json.Arr(
        Chunk(
          Json.Str("projectId"),
          Json.Str("title"),
          Json.Str("sections"),
          Json.Str("authorKind"),
          Json.Str("authorId"),
          Json.Str("authorDisplayName"),
        )
      ),
    ),
    execute = args =>
      for
        projectId <- fieldStr(args, "projectId")
        title     <- fieldStr(args, "title")
        sections  <- fieldObj(args, "sections")
        author    <- authorFromCanvasArgs(args)
        now       <- Clock.instant
        canvasId   = CanvasId.generate
        reqContent <- fieldStr(sections, "requirements")
        entContent <- fieldStr(sections, "entities")
        appContent <- fieldStr(sections, "approach")
        strContent <- fieldStr(sections, "structure")
        opsContent <- fieldStr(sections, "operations")
        normContent <- fieldStr(sections, "norms")
        safeContent <- fieldStr(sections, "safeguards")
        sec       = ReasonsSections(
                      requirements = CanvasSection(reqContent, author, now),
                      entities = CanvasSection(entContent, author, now),
                      approach = CanvasSection(appContent, author, now),
                      structure = CanvasSection(strContent, author, now),
                      operations = CanvasSection(opsContent, author, now),
                      norms = CanvasSection(normContent, author, now),
                      safeguards = CanvasSection(safeContent, author, now),
                    )
        event     = CanvasEvent.Created(
                      canvasId = canvasId,
                      projectId = ProjectId(projectId),
                      title = title,
                      sections = sec,
                      storyIssueId = fieldStrOpt(args, "storyIssueId").map(IssueId(_)),
                      analysisId = fieldStrOpt(args, "analysisId").map(AnalysisDocId(_)),
                      specificationId = fieldStrOpt(args, "specificationId").map(SpecificationId(_)),
                      normProfileId = fieldStrOpt(args, "normProfileId").map(NormProfileId(_)),
                      safeguardProfileId = fieldStrOpt(args, "safeguardProfileId").map(SafeguardProfileId(_)),
                      author = author,
                      occurredAt = now,
                    )
        _         <- liftPersistence(canvasRepo.append(event), "canvas append create")
        canvas    <- liftPersistence(canvasRepo.get(canvasId), "canvas get after create")
      yield Json.Obj(
        "canvasId" -> Json.Str(canvasId.value),
        "status"   -> Json.Str(canvas.status.toString),
        "version"  -> Json.Num(BigDecimal(canvas.version)),
      ),
  )

  // ── 3. spdd_canvas_get ─────────────────────────────────────────────────

  private val canvasGetTool: Tool = Tool(
    name = "spdd_canvas_get",
    description = "Fetch a ReasonsCanvas by id, returning all sections, status, version, and links.",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj("canvasId" -> primitiveSchema("string")),
      "required"   -> Json.Arr(Chunk(Json.Str("canvasId"))),
    ),
    execute = args =>
      for
        canvasIdRaw <- fieldStr(args, "canvasId")
        canvas      <- liftPersistence(canvasRepo.get(CanvasId(canvasIdRaw)), "canvas get")
      yield renderCanvas(canvas),
  )

  // ── 4. spdd_canvas_list ────────────────────────────────────────────────

  private val canvasListTool: Tool = Tool(
    name = "spdd_canvas_list",
    description = "List ReasonsCanvases, sorted newest-first. Optionally filter by projectId.",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj("projectId" -> primitiveSchema("string")),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = args =>
      for
        all       <- liftPersistence(canvasRepo.list, "canvas list")
        projectId  = fieldStrOpt(args, "projectId").map(ProjectId(_))
        filtered   = projectId.fold(all)(pid => all.filter(_.projectId == pid))
      yield Json.Obj(
        "canvases" -> Json.Arr(Chunk.fromIterable(filtered.map(renderCanvas)))
      ),
  )

  // ── 5. spdd_canvas_update_sections ─────────────────────────────────────

  private val canvasUpdateSectionsTool: Tool = Tool(
    name = "spdd_canvas_update_sections",
    description =
      "Apply one or more section updates to a Canvas (the prompt-first or sync edits). Bumps version. If the " +
        "canvas was Approved, this knocks status back to InReview (the SPDD golden rule).",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "canvasId"          -> primitiveSchema("string"),
        "updates"           -> Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> Json.Obj(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(
              "sectionId" -> primitiveSchema("string"),
              "content"   -> primitiveSchema("string"),
            ),
            "required"   -> Json.Arr(Chunk(Json.Str("sectionId"), Json.Str("content"))),
          ),
        ),
        "rationale"         -> primitiveSchema("string"),
        "authorKind"        -> primitiveSchema("string"),
        "authorId"          -> primitiveSchema("string"),
        "authorDisplayName" -> primitiveSchema("string"),
      ),
      "required"   -> Json.Arr(
        Chunk(
          Json.Str("canvasId"),
          Json.Str("updates"),
          Json.Str("authorKind"),
          Json.Str("authorId"),
          Json.Str("authorDisplayName"),
        )
      ),
    ),
    execute = args =>
      for
        canvasIdRaw <- fieldStr(args, "canvasId")
        author      <- authorFromCanvasArgs(args)
        now         <- Clock.instant
        rawUpdates  <- args match
                         case Json.Obj(fields) =>
                           fields.toMap.get("updates") match
                             case Some(Json.Arr(values)) => ZIO.succeed(values.toList)
                             case _                      =>
                               ZIO.fail(ToolExecutionError.InvalidParameters("Field 'updates' must be a JSON array"))
                         case _                =>
                           ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be a JSON object"))
        updates     <- ZIO.foreach(rawUpdates) {
                         case obj: Json.Obj =>
                           for
                             secRaw <- fieldStr(obj, "sectionId")
                             secId  <- ZIO
                                         .fromEither(parseSectionId(secRaw))
                                         .mapError(ToolExecutionError.InvalidParameters.apply)
                             content <- fieldStr(obj, "content")
                           yield CanvasSectionUpdate(secId, content)
                         case _             =>
                           ZIO.fail(ToolExecutionError.InvalidParameters("Each update must be an object"))
                       }
        _           <- ZIO
                         .fail(ToolExecutionError.InvalidParameters("'updates' must contain at least one entry"))
                         .when(updates.isEmpty)
        rationale    = fieldStrOpt(args, "rationale")
        event        = CanvasEvent.SectionUpdated(
                         canvasId = CanvasId(canvasIdRaw),
                         updates = updates,
                         author = author,
                         rationale = rationale,
                         occurredAt = now,
                       )
        _           <- liftPersistence(canvasRepo.append(event), "canvas append section-updated")
        canvas      <- liftPersistence(canvasRepo.get(CanvasId(canvasIdRaw)), "canvas get after update")
      yield Json.Obj(
        "canvasId"        -> Json.Str(canvasIdRaw),
        "status"          -> Json.Str(canvas.status.toString),
        "version"         -> Json.Num(BigDecimal(canvas.version)),
        "updatedSections" -> Json.Arr(Chunk.fromIterable(updates.map(u => Json.Str(sectionIdName(u.sectionId))))),
      ),
  )

  // ── 6. spdd_canvas_approve ─────────────────────────────────────────────

  private val canvasApproveTool: Tool = Tool(
    name = "spdd_canvas_approve",
    description = "Approve a ReasonsCanvas. Caller is recorded as approvedBy.",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "canvasId"          -> primitiveSchema("string"),
        "authorKind"        -> primitiveSchema("string"),
        "authorId"          -> primitiveSchema("string"),
        "authorDisplayName" -> primitiveSchema("string"),
      ),
      "required"   -> Json.Arr(
        Chunk(
          Json.Str("canvasId"),
          Json.Str("authorKind"),
          Json.Str("authorId"),
          Json.Str("authorDisplayName"),
        )
      ),
    ),
    execute = args =>
      for
        canvasIdRaw <- fieldStr(args, "canvasId")
        approvedBy  <- authorFromCanvasArgs(args)
        now         <- Clock.instant
        event        = CanvasEvent.Approved(CanvasId(canvasIdRaw), approvedBy, now)
        _           <- liftPersistence(canvasRepo.append(event), "canvas append approve")
        canvas      <- liftPersistence(canvasRepo.get(CanvasId(canvasIdRaw)), "canvas get after approve")
      yield Json.Obj(
        "canvasId" -> Json.Str(canvasIdRaw),
        "status"   -> Json.Str(canvas.status.toString),
      ),
  )

  // ── 7. spdd_canvas_mark_stale ──────────────────────────────────────────

  private val canvasMarkStaleTool: Tool = Tool(
    name = "spdd_canvas_mark_stale",
    description = "Mark a ReasonsCanvas as Stale (downstream code drifted; needs sync or prompt-update).",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "canvasId"          -> primitiveSchema("string"),
        "reason"            -> primitiveSchema("string"),
        "authorKind"        -> primitiveSchema("string"),
        "authorId"          -> primitiveSchema("string"),
        "authorDisplayName" -> primitiveSchema("string"),
      ),
      "required"   -> Json.Arr(
        Chunk(
          Json.Str("canvasId"),
          Json.Str("reason"),
          Json.Str("authorKind"),
          Json.Str("authorId"),
          Json.Str("authorDisplayName"),
        )
      ),
    ),
    execute = args =>
      for
        canvasIdRaw <- fieldStr(args, "canvasId")
        reason      <- fieldStr(args, "reason")
        markedBy    <- authorFromCanvasArgs(args)
        now         <- Clock.instant
        event        = CanvasEvent.MarkedStale(CanvasId(canvasIdRaw), reason, markedBy, now)
        _           <- liftPersistence(canvasRepo.append(event), "canvas append mark-stale")
        canvas      <- liftPersistence(canvasRepo.get(CanvasId(canvasIdRaw)), "canvas get after mark-stale")
      yield Json.Obj(
        "canvasId" -> Json.Str(canvasIdRaw),
        "status"   -> Json.Str(canvas.status.toString),
        "reason"   -> Json.Str(reason),
      ),
  )

  // ── 8. spdd_canvas_link_run ────────────────────────────────────────────

  private val canvasLinkRunTool: Tool = Tool(
    name = "spdd_canvas_link_run",
    description =
      "Record that a TaskRun was generated from this Canvas (call when /spdd-generate produces a run). " +
        "Idempotent on repeat for the same taskRunId.",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "canvasId"  -> primitiveSchema("string"),
        "taskRunId" -> primitiveSchema("string"),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("canvasId"), Json.Str("taskRunId"))),
    ),
    execute = args =>
      for
        canvasIdRaw <- fieldStr(args, "canvasId")
        runIdRaw    <- fieldStr(args, "taskRunId")
        now         <- Clock.instant
        event        = CanvasEvent.LinkedToTaskRun(CanvasId(canvasIdRaw), TaskRunId(runIdRaw), now)
        _           <- liftPersistence(canvasRepo.append(event), "canvas append link-run")
        canvas      <- liftPersistence(canvasRepo.get(CanvasId(canvasIdRaw)), "canvas get after link-run")
      yield Json.Obj(
        "canvasId"   -> Json.Str(canvasIdRaw),
        "taskRunIds" -> Json.Arr(Chunk.fromIterable(canvas.linkedTaskRunIds.map(id => Json.Str(id.value)))),
      ),
  )

  // ── 9. spdd_canvas_search_similar ──────────────────────────────────────

  private val canvasSearchSimilarTool: Tool = Tool(
    name = "spdd_canvas_search_similar",
    description =
      "Find approved Canvases similar to a query (Jaccard similarity over normalized section tokens). " +
        "Use at the start of /spdd-analysis to bring prior context. Optional projectId filter. Default limit 5.",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "query"     -> primitiveSchema("string"),
        "projectId" -> primitiveSchema("string"),
        "limit"     -> primitiveSchema("integer"),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("query"))),
    ),
    execute = args =>
      for
        query     <- fieldStr(args, "query")
        projectId  = fieldStrOpt(args, "projectId").map(ProjectId(_))
        limit      = fieldIntOpt(args, "limit").getOrElse(5)
        hits      <- liftPersistence(similarityIndex.findSimilar(query, projectId, limit), "canvas similarity")
      yield Json.Obj(
        "query"   -> Json.Str(query),
        "matches" -> Json.Arr(
          Chunk.fromIterable(
            hits.map(hit =>
              Json.Obj(
                "canvasId"     -> Json.Str(hit.canvasId.value),
                "projectId"    -> Json.Str(hit.projectId.value),
                "title"        -> Json.Str(hit.title),
                "score"        -> Json.Num(BigDecimal(hit.score)),
                "matchedTerms" -> Json.Arr(Chunk.fromIterable(hit.matchedTerms.map(Json.Str(_)))),
              )
            )
          )
        ),
      ),
  )

  // ── Schema helpers ──────────────────────────────────────────────────────

  private def primitiveSchema(jsonType: String): Json =
    Json.Obj("type" -> Json.Str(jsonType))

  // ── Public surface ──────────────────────────────────────────────────────

  val all: List[Tool] = List(
    renderPromptTool,
    canvasCreateTool,
    canvasGetTool,
    canvasListTool,
    canvasUpdateSectionsTool,
    canvasApproveTool,
    canvasMarkStaleTool,
    canvasLinkRunTool,
    canvasSearchSimilarTool,
  )
