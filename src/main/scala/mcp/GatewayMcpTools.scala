package mcp

import java.time.Instant

import zio.*
import zio.json.*
import zio.json.ast.Json

import _root_.config.entity.WorkflowDefinition
import agent.entity.AgentRepository
import analysis.entity.{ AnalysisRepository, AnalysisType }
import daemon.entity.DaemonAgentSpec
import decision.control.DecisionInbox
import decision.entity.{ DecisionFilter, DecisionResolutionKind, DecisionSourceKind, DecisionStatus, DecisionUrgency }
import evolution.control.{ EvolutionEngine, EvolutionProposalRequest }
import evolution.entity.*
import governance.entity.GovernancePolicy
import issues.entity.{ IssueEvent, IssueRepository }
import knowledge.control.KnowledgeGraphService
import llm4zio.tools.{ Tool, ToolExecutionError }
import memory.entity.{ MemoryFilter, MemoryRepository, UserId }
import shared.ids.Ids.IssueId
import workspace.control.WorkspaceRunService
import workspace.entity.WorkspaceRepository

/** Gateway tools exposed over MCP.
  *
  * Each tool is a pure `Tool` value: name + JSON schema + execute function. All repository/service dependencies are
  * injected at construction time.
  */
final class GatewayMcpTools(
  issueRepo: IssueRepository,
  agentRepo: AgentRepository,
  wsRepo: WorkspaceRepository,
  runService: WorkspaceRunService,
  decisionInbox: DecisionInbox,
  evolutionEngine: EvolutionEngine,
  memoryRepo: MemoryRepository,
  analysisRepo: AnalysisRepository,
  knowledgeGraph: KnowledgeGraphService,
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

  private val searchDecisionsTool: Tool = Tool(
    name = "search_decisions",
    description = "Search structured decision logs and linked decision knowledge",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "query"       -> Json.Obj("type" -> Json.Str("string")),
        "workspaceId" -> Json.Obj("type" -> Json.Str("string")),
        "limit"       -> Json.Obj("type" -> Json.Str("integer")),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("query"))),
    ),
    execute = args =>
      for
        query      <- fieldStr(args, "query")
        workspaceId = fieldStrOpt(args, "workspaceId").map(_.trim).filter(_.nonEmpty)
        limit       = fieldIntOpt(args, "limit").getOrElse(10)
        results    <- knowledgeGraph
                        .searchDecisions(query, workspaceId, limit)
                        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield Json.Arr(
        Chunk.fromIterable(
          results.map(result =>
            Json.Obj(
              "id"               -> Json.Str(result.decision.id.value),
              "title"            -> Json.Str(result.decision.title),
              "decisionTaken"    -> Json.Str(result.decision.decisionTaken),
              "rationale"        -> Json.Str(result.decision.rationale),
              "score"            -> Json.Num(BigDecimal(result.score)),
              "issueIds"         -> Json.Arr(Chunk.fromIterable(result.decision.issueIds.map(id => Json.Str(id.value)))),
              "planIds"          -> Json.Arr(Chunk.fromIterable(result.decision.planIds.map(id => Json.Str(id.value)))),
              "specificationIds" -> Json.Arr(
                Chunk.fromIterable(result.decision.specificationIds.map(id => Json.Str(id.value)))
              ),
            )
          )
        )
      ),
  )

  private val getArchitecturalContextTool: Tool = Tool(
    name = "get_architectural_context",
    description = "Retrieve architectural rationale, constraints, and linked decisions for a query",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "query"       -> Json.Obj("type" -> Json.Str("string")),
        "workspaceId" -> Json.Obj("type" -> Json.Str("string")),
        "limit"       -> Json.Obj("type" -> Json.Str("integer")),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("query"))),
    ),
    execute = args =>
      for
        query      <- fieldStr(args, "query")
        workspaceId = fieldStrOpt(args, "workspaceId").map(_.trim).filter(_.nonEmpty)
        limit       = fieldIntOpt(args, "limit").getOrElse(10)
        context    <- knowledgeGraph
                        .getArchitecturalContext(query, workspaceId, limit)
                        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield Json.Obj(
        "decisions"        -> Json.Arr(
          Chunk.fromIterable(
            context.decisions.map(item =>
              Json.Obj(
                "id"    -> Json.Str(item.decision.id.value),
                "title" -> Json.Str(item.decision.title),
                "score" -> Json.Num(BigDecimal(item.score)),
              )
            )
          )
        ),
        "knowledgeEntries" -> Json.Arr(
          Chunk.fromIterable(
            context.knowledgeEntries.map(entry =>
              Json.Obj(
                "id"   -> Json.Str(entry.id.value),
                "kind" -> Json.Str(entry.kind.value),
                "text" -> Json.Str(entry.text),
              )
            )
          )
        ),
        "analysisDocs"     -> Json.Arr(
          Chunk.fromIterable(
            context.analysisDocs.map(doc =>
              Json.Obj(
                "id"       -> Json.Str(doc.id.value),
                "type"     -> Json.Str(doc.analysisType.toString),
                "filePath" -> Json.Str(doc.filePath),
                "content"  -> Json.Str(doc.content.take(500)),
              )
            )
          )
        ),
        "edges"            -> Json.Arr(
          Chunk.fromIterable(
            context.edges.map(edge =>
              Json.Obj(
                "from"     -> Json.Str(edge.fromId),
                "to"       -> Json.Str(edge.toId),
                "relation" -> Json.Str(edge.relation),
                "score"    -> Json.Num(BigDecimal(edge.score)),
              )
            )
          )
        ),
      ),
  )

  private val listDecisionsTool: Tool = Tool(
    name = "list_decisions",
    description = "List queued human decisions with optional filters",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "status"  -> Json.Obj("type" -> Json.Str("string")),
        "source"  -> Json.Obj("type" -> Json.Str("string")),
        "urgency" -> Json.Obj("type" -> Json.Str("string")),
        "limit"   -> Json.Obj("type" -> Json.Str("integer")),
      ),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = args =>
      for
        status  <- fieldStrOpt(args, "status") match
                     case Some(raw) =>
                       ZIO.fromEither(parseDecisionStatus(raw))
                         .map(Some(_))
                         .mapError(ToolExecutionError.InvalidParameters.apply)
                     case None      => ZIO.none
        source  <- fieldStrOpt(args, "source") match
                     case Some(raw) =>
                       ZIO.fromEither(parseDecisionSourceKind(raw))
                         .map(Some(_))
                         .mapError(ToolExecutionError.InvalidParameters.apply)
                     case None      => ZIO.none
        urgency <- fieldStrOpt(args, "urgency") match
                     case Some(raw) =>
                       ZIO.fromEither(parseDecisionUrgency(raw))
                         .map(Some(_))
                         .mapError(ToolExecutionError.InvalidParameters.apply)
                     case None      => ZIO.none
        limit    = fieldIntOpt(args, "limit").getOrElse(20).max(1)
        items   <- decisionInbox
                     .list(
                       DecisionFilter(
                         statuses = status.map(Set(_)).getOrElse(Set.empty),
                         sourceKind = source,
                         urgency = urgency,
                         limit = limit,
                       )
                     )
                     .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield Json.Arr(
        Chunk.fromIterable(
          items.map(item =>
            Json.Obj(
              "id"          -> Json.Str(item.id.value),
              "title"       -> Json.Str(item.title),
              "status"      -> Json.Str(item.status.toString),
              "urgency"     -> Json.Str(item.urgency.toString),
              "source"      -> Json.Str(item.source.kind.toString),
              "referenceId" -> Json.Str(item.source.referenceId),
              "deadlineAt"  -> Json.Str(item.deadlineAt.map(_.toString).getOrElse("")),
            )
          )
        )
      ),
  )

  private val resolveDecisionTool: Tool = Tool(
    name = "resolve_decision",
    description = "Resolve a human decision by decision ID",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "decisionId" -> Json.Obj("type" -> Json.Str("string")),
        "resolution" -> Json.Obj("type" -> Json.Str("string")),
        "actor"      -> Json.Obj("type" -> Json.Str("string")),
        "summary"    -> Json.Obj("type" -> Json.Str("string")),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("decisionId"), Json.Str("resolution"))),
    ),
    execute = args =>
      for
        decisionId <- fieldStr(args, "decisionId")
        resolution <- fieldStr(args, "resolution").flatMap(raw =>
                        ZIO.fromEither(parseDecisionResolution(raw))
                          .mapError(ToolExecutionError.InvalidParameters.apply)
                      )
        actor       = fieldStrOpt(args, "actor").getOrElse("mcp")
        summary     = fieldStrOpt(args, "summary").getOrElse("Resolved from MCP")
        resolved   <- decisionInbox
                        .resolve(shared.ids.Ids.DecisionId(decisionId), resolution, actor, summary)
                        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield Json.Obj(
        "decisionId" -> Json.Str(resolved.id.value),
        "status"     -> Json.Str(resolved.status.toString),
        "resolution" -> Json.Str(resolved.resolution.map(_.kind.toString).getOrElse("")),
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

  private val proposeEvolutionTool: Tool = Tool(
    name = "propose_evolution",
    description = "Create a project-scoped platform evolution proposal that must be approved by a human",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "projectId"     -> Json.Obj("type" -> Json.Str("string")),
        "title"         -> Json.Obj("type" -> Json.Str("string")),
        "rationale"     -> Json.Obj("type" -> Json.Str("string")),
        "summary"       -> Json.Obj("type" -> Json.Str("string")),
        "proposedBy"    -> Json.Obj("type" -> Json.Str("string")),
        "targetKind"    -> Json.Obj("type" -> Json.Str("string")),
        "targetPayload" -> Json.Obj("type" -> Json.Str("object")),
        "template"      -> Json.Obj("type" -> Json.Str("string")),
      ),
      "required"   -> Json.Arr(
        Chunk(
          Json.Str("projectId"),
          Json.Str("title"),
          Json.Str("rationale"),
          Json.Str("summary"),
          Json.Str("targetKind"),
          Json.Str("targetPayload"),
        )
      ),
    ),
    execute = args =>
      for
        projectId  <- fieldStr(args, "projectId")
        title      <- fieldStr(args, "title")
        rationale  <- fieldStr(args, "rationale")
        summary    <- fieldStr(args, "summary")
        proposedBy  = fieldStrOpt(args, "proposedBy").getOrElse("mcp")
        targetKind <- fieldStr(args, "targetKind")
        payload    <- fieldObj(args, "targetPayload")
        template   <- fieldStrOpt(args, "template") match
                        case Some(raw) =>
                          ZIO.fromEither(
                            parseEvolutionTemplate(raw)
                          ).map(Some(_)).mapError(ToolExecutionError.InvalidParameters.apply)
                        case None      => ZIO.none
        target     <- ZIO
                        .fromEither(parseEvolutionTarget(projectId, targetKind, payload))
                        .mapError(ToolExecutionError.InvalidParameters.apply)
        created    <- evolutionEngine
                        .propose(
                          EvolutionProposalRequest(
                            projectId = shared.ids.Ids.ProjectId(projectId),
                            title = title,
                            rationale = rationale,
                            target = target,
                            proposedBy = proposedBy,
                            summary = summary,
                            template = template,
                          )
                        )
                        .mapError(error => ToolExecutionError.ExecutionFailed(error.toString))
      yield Json.Obj(
        "proposalId" -> Json.Str(created.id.value),
        "status"     -> Json.Str(created.status.toString),
        "decisionId" -> Json.Str(created.decisionId.map(_.value).getOrElse("")),
      ),
  )

  private val listProposalsTool: Tool = Tool(
    name = "list_proposals",
    description = "List platform evolution proposals",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "projectId" -> Json.Obj("type" -> Json.Str("string")),
        "status"    -> Json.Obj("type" -> Json.Str("string")),
        "query"     -> Json.Obj("type" -> Json.Str("string")),
      ),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = args =>
      for
        statuses <- fieldStrOpt(args, "status") match
                      case Some(raw) =>
                        ZIO.fromEither(
                          parseEvolutionStatus(raw)
                        ).map(value => Set(value)).mapError(ToolExecutionError.InvalidParameters.apply)
                      case None      => ZIO.succeed(Set.empty[EvolutionProposalStatus])
        items    <- evolutionEngine
                      .list(
                        EvolutionProposalFilter(
                          projectId = fieldStrOpt(args, "projectId").map(shared.ids.Ids.ProjectId.apply),
                          statuses = statuses,
                          query = fieldStrOpt(args, "query"),
                        )
                      )
                      .mapError(error => ToolExecutionError.ExecutionFailed(error.toString))
      yield Json.Arr(
        Chunk.fromIterable(
          items.map(item =>
            Json.Obj(
              "id"         -> Json.Str(item.id.value),
              "projectId"  -> Json.Str(item.projectId.value),
              "title"      -> Json.Str(item.title),
              "status"     -> Json.Str(item.status.toString),
              "proposedBy" -> Json.Str(item.proposer.actor),
              "decisionId" -> Json.Str(item.decisionId.map(_.value).getOrElse("")),
            )
          )
        )
      ),
  )

  private val getEvolutionHistoryTool: Tool = Tool(
    name = "get_evolution_history",
    description = "Get the event history for a specific evolution proposal",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "proposalId" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("proposalId"))),
    ),
    execute = args =>
      for
        proposalId <- fieldStr(args, "proposalId")
        events     <- evolutionEngine
                        .history(shared.ids.Ids.EvolutionProposalId(proposalId))
                        .mapError(error => ToolExecutionError.ExecutionFailed(error.toString))
      yield Json.Arr(
        Chunk.fromIterable(
          events.map(event =>
            Json.Obj(
              "eventType"  -> Json.Str(event.getClass.getSimpleName.stripSuffix("$")),
              "proposalId" -> Json.Str(event.proposalId.value),
              "projectId"  -> Json.Str(event.projectId.value),
              "occurredAt" -> Json.Str(event.occurredAt.toString),
            )
          )
        )
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

  private def fieldObj(args: Json, key: String): IO[ToolExecutionError, Json.Obj] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(key) match
          case Some(value: Json.Obj) => ZIO.succeed(value)
          case _                     => ZIO.fail(ToolExecutionError.InvalidParameters(s"Missing required object field: $key"))
      case _                =>
        ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be a JSON object"))

  private def parseAnalysisType(raw: String): Either[String, AnalysisType] =
    raw.trim.toLowerCase match
      case "code_review" | "codereview" | "code-review" => Right(AnalysisType.CodeReview)
      case "architecture"                               => Right(AnalysisType.Architecture)
      case "security"                                   => Right(AnalysisType.Security)
      case value if value.nonEmpty                      => Right(AnalysisType.Custom(value))
      case _                                            => Left("analysisType must be a non-empty string")

  private def parseDecisionStatus(raw: String): Either[String, DecisionStatus] =
    raw.trim.toLowerCase match
      case "pending"   => Right(DecisionStatus.Pending)
      case "resolved"  => Right(DecisionStatus.Resolved)
      case "escalated" => Right(DecisionStatus.Escalated)
      case "expired"   => Right(DecisionStatus.Expired)
      case other       => Left(s"Unknown decision status: $other")

  private def parseDecisionSourceKind(raw: String): Either[String, DecisionSourceKind] =
    raw.trim.toLowerCase match
      case "issue_review" | "issuereview"         => Right(DecisionSourceKind.IssueReview)
      case "governance"                           => Right(DecisionSourceKind.Governance)
      case "agent_escalation" | "agentescalation" => Right(DecisionSourceKind.AgentEscalation)
      case "manual"                               => Right(DecisionSourceKind.Manual)
      case other                                  => Left(s"Unknown decision source: $other")

  private def parseDecisionUrgency(raw: String): Either[String, DecisionUrgency] =
    raw.trim.toLowerCase match
      case "low"      => Right(DecisionUrgency.Low)
      case "medium"   => Right(DecisionUrgency.Medium)
      case "high"     => Right(DecisionUrgency.High)
      case "critical" => Right(DecisionUrgency.Critical)
      case other      => Left(s"Unknown decision urgency: $other")

  private def parseDecisionResolution(raw: String): Either[String, DecisionResolutionKind] =
    raw.trim.toLowerCase match
      case "approved"        => Right(DecisionResolutionKind.Approved)
      case "reworkrequested" => Right(DecisionResolutionKind.ReworkRequested)
      case "acknowledged"    => Right(DecisionResolutionKind.Acknowledged)
      case "escalated"       => Right(DecisionResolutionKind.Escalated)
      case "expired"         => Right(DecisionResolutionKind.Expired)
      case other             => Left(s"Unknown decision resolution: $other")

  private def parseEvolutionStatus(raw: String): Either[String, EvolutionProposalStatus] =
    raw.trim.toLowerCase match
      case "proposed"   => Right(EvolutionProposalStatus.Proposed)
      case "approved"   => Right(EvolutionProposalStatus.Approved)
      case "applied"    => Right(EvolutionProposalStatus.Applied)
      case "rolledback" => Right(EvolutionProposalStatus.RolledBack)
      case other        => Left(s"Unknown evolution status: $other")

  private def parseEvolutionTemplate(raw: String): Either[String, EvolutionTemplateKind] =
    raw.trim.toLowerCase match
      case "add_quality_gate" | "addqualitygate"               => Right(EvolutionTemplateKind.AddQualityGate)
      case "change_testing_strategy" | "changetestingstrategy" => Right(EvolutionTemplateKind.ChangeTestingStrategy)
      case "add_daemon_agent" | "adddaemonagent"               => Right(EvolutionTemplateKind.AddDaemonAgent)
      case other if other.nonEmpty                             => Right(EvolutionTemplateKind.Custom(other))
      case _                                                   => Left("Template must be a non-empty string")

  private def parseEvolutionTarget(projectId: String, rawKind: String, payload: Json.Obj)
    : Either[String, EvolutionTarget] =
    val project = shared.ids.Ids.ProjectId(projectId)
    rawKind.trim.toLowerCase match
      case "governance" | "governance_policy" =>
        payload.toJson.fromJson[GovernancePolicy].map { policy =>
          EvolutionTarget.GovernancePolicyTarget(
            projectId = project,
            policyId = Some(policy.id),
            name = policy.name,
            transitionRules = policy.transitionRules,
            daemonTriggers = policy.daemonTriggers,
            escalationRules = policy.escalationRules,
            completionCriteria = policy.completionCriteria,
            isDefault = policy.isDefault,
          )
        }.left.map(error => s"Invalid governance payload: $error")
      case "workflow" | "workflow_definition" =>
        payload.toJson.fromJson[WorkflowDefinition].map(workflow =>
          EvolutionTarget.WorkflowDefinitionTarget(projectId = project, workflow = workflow)
        ).left.map(error => s"Invalid workflow payload: $error")
      case "daemon" | "daemon_agent_spec"     =>
        payload.toJson.fromJson[DaemonAgentSpec].map(spec =>
          EvolutionTarget.DaemonAgentSpecTarget(spec = spec.copy(projectId = project))
        ).left.map(error => s"Invalid daemon payload: $error")
      case other                              =>
        Left(s"Unknown evolution target kind: $other")

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
    listDecisionsTool,
    resolveDecisionTool,
    proposeEvolutionTool,
    listProposalsTool,
    getEvolutionHistoryTool,
    getAnalysisDocsTool,
    getAnalysisSummaryTool,
    searchDecisionsTool,
    getArchitecturalContextTool,
  )
