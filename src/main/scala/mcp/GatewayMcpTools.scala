package mcp

import java.time.Instant

import zio.*
import zio.json.*
import zio.json.ast.Json

import agent.entity.AgentRepository
import analysis.entity.{ AnalysisRepository, AnalysisType }
import issues.entity.{ IssueEvent, IssueRepository }
import llm4zio.tools.{ Tool, ToolExecutionError }
import memory.entity.{ MemoryFilter, MemoryRepository, UserId }
import shared.ids.Ids.IssueId
import workspace.control.WorkspaceRunService
import workspace.entity.WorkspaceRepository

/** The 7 gateway tools exposed over MCP.
  *
  * Each tool is a pure `Tool` value: name + JSON schema + execute function. All repository/service dependencies are
  * injected at construction time.
  */
final class GatewayMcpTools(
  issueRepo: IssueRepository,
  agentRepo: AgentRepository,
  wsRepo: WorkspaceRepository,
  runService: WorkspaceRunService,
  memoryRepo: MemoryRepository,
  analysisRepo: AnalysisRepository,
):

  // ── assign_issue ──────────────────────────────────────────────────────────

  private val assignIssueTool: Tool = Tool(
    name = "assign_issue",
    description = "Create a new issue in the gateway issue tracker",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "title"       -> Json.Obj("type" -> Json.Str("string")),
        "description" -> Json.Obj("type" -> Json.Str("string")),
        "priority"    -> Json.Obj("type" -> Json.Str("string")),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("title"), Json.Str("description"))),
    ),
    execute = args =>
      for
        title       <- fieldStr(args, "title")
        description <- fieldStr(args, "description")
        priority     = fieldStrOpt(args, "priority").getOrElse("medium")
        issueId      = IssueId(java.util.UUID.randomUUID().toString)
        now         <- zio.Clock.instant
        event        = IssueEvent.Created(
                         issueId = issueId,
                         title = title,
                         description = description,
                         issueType = "task",
                         priority = priority,
                         occurredAt = now,
                       )
        _           <- issueRepo
                         .append(event)
                         .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield Json.Obj("issueId" -> Json.Str(issueId.value)),
  )

  // ── run_agent ─────────────────────────────────────────────────────────────

  private val runAgentTool: Tool = Tool(
    name = "run_agent",
    description = "Start an agent run on a workspace for a given issue",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "workspaceId" -> Json.Obj("type" -> Json.Str("string")),
        "issueRef"    -> Json.Obj("type" -> Json.Str("string")),
        "prompt"      -> Json.Obj("type" -> Json.Str("string")),
        "agentName"   -> Json.Obj("type" -> Json.Str("string")),
      ),
      "required"   -> Json.Arr(
        Chunk(Json.Str("workspaceId"), Json.Str("issueRef"), Json.Str("prompt"), Json.Str("agentName"))
      ),
    ),
    execute = args =>
      for
        workspaceId <- fieldStr(args, "workspaceId")
        issueRef    <- fieldStr(args, "issueRef")
        prompt      <- fieldStr(args, "prompt")
        agentName   <- fieldStr(args, "agentName")
        run         <- runService
                         .assign(workspaceId, workspace.control.AssignRunRequest(issueRef, prompt, agentName))
                         .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield Json.Obj("runId" -> Json.Str(run.id), "status" -> Json.Str(run.status.toString)),
  )

  // ── get_run_status ────────────────────────────────────────────────────────

  private val getRunStatusTool: Tool = Tool(
    name = "get_run_status",
    description = "Get the status of an agent run by its run ID",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "runId" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("runId"))),
    ),
    execute = args =>
      for
        runId <- fieldStr(args, "runId")
        opt   <- wsRepo
                   .getRun(runId)
                   .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield opt match
        case None      => Json.Obj("status" -> Json.Str("not_found"), "runId" -> Json.Str(runId))
        case Some(run) =>
          Json.Obj(
            "runId"       -> Json.Str(run.id),
            "status"      -> Json.Str(run.status.toString),
            "workspaceId" -> Json.Str(run.workspaceId),
            "agentName"   -> Json.Str(run.agentName),
          ),
  )

  // ── list_agents ───────────────────────────────────────────────────────────

  private val listAgentsTool: Tool = Tool(
    name = "list_agents",
    description = "List all registered agents in this gateway",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = _ =>
      agentRepo
        .list()
        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        .map { agents =>
          Json.Arr(
            Chunk.fromIterable(
              agents.map(a =>
                Json.Obj(
                  "id"           -> Json.Str(a.id.value),
                  "name"         -> Json.Str(a.name),
                  "description"  -> Json.Str(a.description),
                  "capabilities" -> Json.Arr(Chunk.fromIterable(a.capabilities.map(Json.Str(_)))),
                )
              )
            )
          )
        },
  )

  // ── list_workspaces ───────────────────────────────────────────────────────

  private val listWorkspacesTool: Tool = Tool(
    name = "list_workspaces",
    description = "List all configured workspaces in this gateway",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = _ =>
      wsRepo.list
        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        .map { workspaces =>
          Json.Arr(
            Chunk.fromIterable(
              workspaces.map(ws =>
                Json.Obj(
                  "id"        -> Json.Str(ws.id),
                  "name"      -> Json.Str(ws.name),
                  "localPath" -> Json.Str(ws.localPath),
                  "enabled"   -> Json.Bool(ws.enabled),
                )
              )
            )
          )
        },
  )

  // ── search_conversations ──────────────────────────────────────────────────

  private val searchConversationsTool: Tool = Tool(
    name = "search_conversations",
    description = "Semantic search over conversation memory",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "query" -> Json.Obj("type" -> Json.Str("string")),
        "limit" -> Json.Obj("type" -> Json.Str("integer")),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("query"))),
    ),
    execute = args =>
      for
        query   <- fieldStr(args, "query")
        limit    = fieldIntOpt(args, "limit").getOrElse(10)
        results <- memoryRepo
                     .searchRelevant(UserId("mcp"), query, limit, MemoryFilter())
                     .mapError(e => ToolExecutionError.ExecutionFailed(e.getMessage))
      yield Json.Arr(
        Chunk.fromIterable(
          results.map(r =>
            Json.Obj(
              "text"  -> Json.Str(r.entry.text),
              "score" -> Json.Num(BigDecimal(r.score.toDouble)),
            )
          )
        )
      ),
  )

  // ── get_metrics ───────────────────────────────────────────────────────────

  private val getMetricsTool: Tool = Tool(
    name = "get_metrics",
    description = "Get aggregate gateway metrics (agent count, workspace count, active runs)",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = _ =>
      for
        agents     <- agentRepo.list().mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        workspaces <- wsRepo.list.mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        runs       <- ZIO
                        .foreach(workspaces)(ws =>
                          wsRepo
                            .listRuns(ws.id)
                            .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
                        )
                        .map(_.flatten)
      yield Json.Obj(
        "agents"     -> Json.Num(BigDecimal(agents.size)),
        "workspaces" -> Json.Num(BigDecimal(workspaces.size)),
        "activeRuns" -> Json.Num(BigDecimal(runs.count(r => isActive(r.status)))),
        "totalRuns"  -> Json.Num(BigDecimal(runs.size)),
      ),
  )

  // ── get_analysis_docs ─────────────────────────────────────────────────────

  private val getAnalysisDocsTool: Tool = Tool(
    name = "get_analysis_docs",
    description = "Get workspace analysis documents, optionally filtered by analysis type",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "workspaceId"  -> Json.Obj("type" -> Json.Str("string")),
        "analysisType" -> Json.Obj("type" -> Json.Str("string")),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("workspaceId"))),
    ),
    execute = args =>
      for
        workspaceId     <- fieldStr(args, "workspaceId")
        analysisTypeOpt <- fieldStrOpt(args, "analysisType") match
                             case Some(raw) =>
                               ZIO
                                 .fromEither(parseAnalysisType(raw))
                                 .map(Some(_))
                                 .mapError(msg => ToolExecutionError.InvalidParameters(msg))
                             case None      =>
                               ZIO.succeed(None)
        docs            <- analysisRepo
                             .listByWorkspace(workspaceId)
                             .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield Json.Arr(
        Chunk.fromIterable(
          docs
            .filter(doc => analysisTypeOpt.forall(_ == doc.analysisType))
            .sortBy(doc => (doc.updatedAt, doc.createdAt))(Ordering.Tuple2(
              Ordering[Instant],
              Ordering[Instant],
            ).reverse)
            .map(doc =>
              Json.Obj(
                "id"           -> Json.Str(doc.id.value),
                "analysisType" -> Json.Str(renderAnalysisType(doc.analysisType)),
                "content"      -> Json.Str(doc.content),
                "filePath"     -> Json.Str(doc.filePath),
                "generatedBy"  -> Json.Str(doc.generatedBy.value),
                "createdAt"    -> Json.Str(doc.createdAt.toString),
              )
            )
        )
      ),
  )

  // ── get_analysis_summary ──────────────────────────────────────────────────

  private val getAnalysisSummaryTool: Tool = Tool(
    name = "get_analysis_summary",
    description = "Get a condensed workspace analysis summary assembled from all available analysis docs",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "workspaceId" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("workspaceId"))),
    ),
    execute = args =>
      for
        workspaceId <- fieldStr(args, "workspaceId")
        docs        <- analysisRepo
                         .listByWorkspace(workspaceId)
                         .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        grouped      = docs
                         .groupBy(_.analysisType)
                         .toList
                         .sortBy { case (analysisType, _) => analysisTypeRank(analysisType) }
                         .flatMap {
                           case (_, docsForType) =>
                             docsForType.sortBy(doc => (doc.updatedAt, doc.createdAt)).lastOption
                         }
        summary      = grouped.map(doc =>
                         s"${renderAnalysisType(doc.analysisType)}: ${summarizeAnalysis(doc.content)}"
                       ).mkString("\n\n")
      yield Json.Obj(
        "workspaceId" -> Json.Str(workspaceId),
        "summary"     -> Json.Str(summary),
        "documents"   -> Json.Num(BigDecimal(grouped.size)),
      ),
  )

  // ── helpers ───────────────────────────────────────────────────────────────

  private def fieldStr(args: Json, key: String): IO[ToolExecutionError, String] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(key) match
          case Some(Json.Str(v)) => ZIO.succeed(v)
          case _                 => ZIO.fail(ToolExecutionError.InvalidParameters(s"Missing required string field: $key"))
      case _                =>
        ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be a JSON object"))

  private def fieldStrOpt(args: Json, key: String): Option[String] =
    args match
      case Json.Obj(fields) => fields.toMap.get(key).collect { case Json.Str(v) => v }
      case _                => None

  private def fieldIntOpt(args: Json, key: String): Option[Int] =
    args match
      case Json.Obj(fields) => fields.toMap.get(key).collect { case Json.Num(v) => v.intValue() }
      case _                => None

  private def parseAnalysisType(raw: String): Either[String, AnalysisType] =
    raw.trim.toLowerCase match
      case "code_review" | "codereview" | "code-review" => Right(AnalysisType.CodeReview)
      case "architecture"                               => Right(AnalysisType.Architecture)
      case "security"                                   => Right(AnalysisType.Security)
      case value if value.nonEmpty                      => Right(AnalysisType.Custom(value))
      case _                                            => Left("analysisType must be a non-empty string")

  private def renderAnalysisType(analysisType: AnalysisType): String =
    analysisType match
      case AnalysisType.CodeReview   => "CodeReview"
      case AnalysisType.Architecture => "Architecture"
      case AnalysisType.Security     => "Security"
      case AnalysisType.Custom(name) => name

  private def summarizeAnalysis(markdown: String): String =
    val lines            = markdown.linesIterator.map(_.trim).toList
    val executiveSummary = sectionBody(lines, "executive summary")
    executiveSummary
      .orElse(firstParagraph(lines))
      .getOrElse("No summary available.")

  private def sectionBody(lines: List[String], heading: String): Option[String] =
    val startIndex = lines.indexWhere { line =>
      line.startsWith("#") && line.stripPrefix("#").trim.equalsIgnoreCase(heading)
    }
    if startIndex < 0 then None
    else
      val body = lines
        .drop(startIndex + 1)
        .takeWhile(line => !line.startsWith("#"))
        .dropWhile(_.isEmpty)
        .mkString(" ")
        .trim
      Option(body).filter(_.nonEmpty)

  private def firstParagraph(lines: List[String]): Option[String] =
    val paragraph = lines
      .dropWhile(line => line.isEmpty || line.startsWith("#"))
      .takeWhile(_.nonEmpty)
      .mkString(" ")
      .trim
    Option(paragraph).filter(_.nonEmpty)

  private def analysisTypeRank(analysisType: AnalysisType): Int =
    analysisType match
      case AnalysisType.CodeReview   => 0
      case AnalysisType.Architecture => 1
      case AnalysisType.Security     => 2
      case AnalysisType.Custom(_)    => 3

  private def isActive(status: workspace.entity.RunStatus): Boolean =
    status == workspace.entity.RunStatus.Pending ||
    status.isInstanceOf[workspace.entity.RunStatus.Running]

  val all: List[Tool] = List(
    assignIssueTool,
    runAgentTool,
    getRunStatusTool,
    listAgentsTool,
    listWorkspacesTool,
    searchConversationsTool,
    getMetricsTool,
    getAnalysisDocsTool,
    getAnalysisSummaryTool,
  )
