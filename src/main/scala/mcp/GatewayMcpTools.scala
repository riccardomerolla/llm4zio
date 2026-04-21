package mcp

import java.time.Instant

import zio.*
import zio.json.*
import zio.json.ast.Json

import agent.entity.AgentRepository
import analysis.entity.AnalysisRepository
import daemon.control.DaemonAgentScheduler
import decision.control.DecisionInbox
import decision.entity.*
import evolution.control.{ EvolutionEngine, EvolutionProposalRequest }
import evolution.entity.*
import governance.control.{ GovernanceEvaluationContext, GovernancePolicyService }
import governance.entity.*
import issues.entity.{ IssueEvent, IssueRepository }
import knowledge.control.KnowledgeGraphService
import llm4zio.tools.{ Tool, ToolExecutionError }
import mcp.GatewayMcpToolSupport.*
import memory.entity.{ MemoryFilter, MemoryRepository, Scope }
import plan.entity.*
import sdlc.control.SdlcDashboardService
import shared.ids.Ids.*
import specification.entity.*
import workspace.entity.{ WorkspaceRepository, WorkspaceRunService }

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
  governancePolicyService: GovernancePolicyService,
  specificationRepository: SpecificationRepository,
  planRepository: PlanRepository,
  daemonScheduler: DaemonAgentScheduler,
  sdlcDashboardService: SdlcDashboardService,
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
                         .assign(workspaceId, workspace.entity.AssignRunRequest(issueRef, prompt, agentName))
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
                     .searchRelevant(Scope("mcp"), query, limit, MemoryFilter())
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

  private val getDecisionTool: Tool = Tool(
    name = "get_decision",
    description = "Get full decision details by decision ID",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "decisionId" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("decisionId"))),
    ),
    execute = args =>
      for
        decisionId <- fieldStr(args, "decisionId")
        decision   <- decisionInbox
                        .get(DecisionId(decisionId))
                        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json       <- toJsonAst(decision, "decision")
      yield json,
  )

  private val escalateDecisionTool: Tool = Tool(
    name = "escalate_decision",
    description = "Escalate a human decision by decision ID",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "decisionId" -> Json.Obj("type" -> Json.Str("string")),
        "reason"     -> Json.Obj("type" -> Json.Str("string")),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("decisionId"), Json.Str("reason"))),
    ),
    execute = args =>
      for
        decisionId <- fieldStr(args, "decisionId")
        reason     <- fieldStr(args, "reason")
        decision   <- decisionInbox
                        .escalate(DecisionId(decisionId), reason)
                        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json       <- toJsonAst(decision, "decision")
      yield json,
  )

  // ── governance tools ─────────────────────────────────────────────────────

  private val getGovernancePolicyTool: Tool = Tool(
    name = "get_governance_policy",
    description = "Resolve the effective governance policy for a workspace",
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
        policy      <- governancePolicyService
                         .resolvePolicyForWorkspace(workspaceId)
                         .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json        <- toJsonAst(policy, "governance policy")
      yield json,
  )

  private val evaluateGovernanceTransitionTool: Tool = Tool(
    name = "evaluate_governance_transition",
    description = "Evaluate whether a workspace transition is allowed under governance policy",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "workspaceId"          -> Json.Obj("type" -> Json.Str("string")),
        "issueType"            -> Json.Obj("type" -> Json.Str("string")),
        "fromStage"            -> Json.Obj("type" -> Json.Str("string")),
        "toStage"              -> Json.Obj("type" -> Json.Str("string")),
        "action"               -> Json.Obj("type" -> Json.Str("string")),
        "satisfiedGates"       -> Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> Json.Obj("type" -> Json.Str("string")),
        ),
        "tags"                 -> Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> Json.Obj("type" -> Json.Str("string")),
        ),
        "humanApprovalGranted" -> Json.Obj("type" -> Json.Str("boolean")),
      ),
      "required"   -> Json.Arr(
        Chunk(
          Json.Str("workspaceId"),
          Json.Str("issueType"),
          Json.Str("fromStage"),
          Json.Str("toStage"),
          Json.Str("action"),
        )
      ),
    ),
    execute = args =>
      for
        workspaceId    <- fieldStr(args, "workspaceId")
        issueType      <- fieldStr(args, "issueType")
        fromStage      <- fieldStr(args, "fromStage").flatMap(raw =>
                            ZIO.fromEither(parseGovernanceStage(raw))
                              .mapError(ToolExecutionError.InvalidParameters.apply)
                          )
        toStage        <- fieldStr(args, "toStage").flatMap(raw =>
                            ZIO.fromEither(parseGovernanceStage(raw))
                              .mapError(ToolExecutionError.InvalidParameters.apply)
                          )
        action         <- fieldStr(args, "action").flatMap(raw =>
                            ZIO.fromEither(parseGovernanceAction(raw))
                              .mapError(ToolExecutionError.InvalidParameters.apply)
                          )
        satisfiedGates <- parseGovernanceGates(fieldStrListOpt(args, "satisfiedGates"))
        decision       <- governancePolicyService
                            .evaluateForWorkspace(
                              workspaceId,
                              GovernanceEvaluationContext(
                                issueType = issueType,
                                transition = GovernanceTransition(fromStage, toStage, action),
                                satisfiedGates = satisfiedGates,
                                tags = fieldStrListOpt(args, "tags").map(_.trim).filter(_.nonEmpty).toSet,
                                humanApprovalGranted = fieldBoolOpt(args, "humanApprovalGranted").getOrElse(false),
                              ),
                            )
                            .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield renderGovernanceDecision(decision),
  )

  // ── specification tools ─────────────────────────────────────────────────

  private val listSpecificationsTool: Tool = Tool(
    name = "list_specifications",
    description = "List specifications with optional status filtering",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "status" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = args =>
      for
        status <- fieldStrOpt(args, "status") match
                    case Some(raw) =>
                      ZIO.fromEither(parseSpecificationStatus(raw))
                        .map(Some(_))
                        .mapError(ToolExecutionError.InvalidParameters.apply)
                    case None      => ZIO.none
        items  <- specificationRepository
                    .list
                    .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json   <- toJsonAst(items.filter(spec => status.forall(_ == spec.status)), "specifications")
      yield json,
  )

  private val getSpecificationTool: Tool = Tool(
    name = "get_specification",
    description = "Get specification details by specification ID",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "specificationId" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("specificationId"))),
    ),
    execute = args =>
      for
        specificationId <- fieldStr(args, "specificationId")
        specification   <- specificationRepository
                             .get(SpecificationId(specificationId))
                             .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json            <- toJsonAst(specification, "specification")
      yield json,
  )

  private val createSpecificationTool: Tool = Tool(
    name = "create_specification",
    description = "Create a new specification draft",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "title"             -> Json.Obj("type" -> Json.Str("string")),
        "content"           -> Json.Obj("type" -> Json.Str("string")),
        "authorId"          -> Json.Obj("type" -> Json.Str("string")),
        "authorDisplayName" -> Json.Obj("type" -> Json.Str("string")),
        "authorKind"        -> Json.Obj("type" -> Json.Str("string")),
        "linkedPlanRef"     -> Json.Obj("type" -> Json.Str("string")),
      ),
      "required"   -> Json.Arr(
        Chunk(
          Json.Str("title"),
          Json.Str("content"),
          Json.Str("authorId"),
          Json.Str("authorDisplayName"),
          Json.Str("authorKind"),
        )
      ),
    ),
    execute = args =>
      for
        title         <- fieldStr(args, "title")
        content       <- fieldStr(args, "content")
        author        <- authorFromArgs(args)
        now           <- Clock.instant
        id             = SpecificationId.generate
        _             <- specificationRepository
                           .append(
                             SpecificationEvent.Created(
                               specificationId = id,
                               title = title,
                               content = content,
                               author = author,
                               status = SpecificationStatus.Draft,
                               linkedPlanRef = fieldStrOpt(args, "linkedPlanRef"),
                               occurredAt = now,
                             )
                           )
                           .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        specification <- specificationRepository
                           .get(id)
                           .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json          <- toJsonAst(specification, "specification")
      yield json,
  )

  private val reviseSpecificationTool: Tool = Tool(
    name = "revise_specification",
    description = "Revise an existing specification draft",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "specificationId"   -> Json.Obj("type" -> Json.Str("string")),
        "title"             -> Json.Obj("type" -> Json.Str("string")),
        "content"           -> Json.Obj("type" -> Json.Str("string")),
        "authorId"          -> Json.Obj("type" -> Json.Str("string")),
        "authorDisplayName" -> Json.Obj("type" -> Json.Str("string")),
        "authorKind"        -> Json.Obj("type" -> Json.Str("string")),
        "status"            -> Json.Obj("type" -> Json.Str("string")),
        "linkedPlanRef"     -> Json.Obj("type" -> Json.Str("string")),
      ),
      "required"   -> Json.Arr(
        Chunk(
          Json.Str("specificationId"),
          Json.Str("title"),
          Json.Str("content"),
          Json.Str("authorId"),
          Json.Str("authorDisplayName"),
          Json.Str("authorKind"),
        )
      ),
    ),
    execute = args =>
      for
        specificationId <- fieldStr(args, "specificationId")
        current         <- specificationRepository
                             .get(SpecificationId(specificationId))
                             .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        title           <- fieldStr(args, "title")
        content         <- fieldStr(args, "content")
        author          <- authorFromArgs(args)
        status          <- fieldStrOpt(args, "status") match
                             case Some(raw) =>
                               ZIO.fromEither(parseSpecificationStatus(raw))
                                 .mapError(ToolExecutionError.InvalidParameters.apply)
                             case None      => ZIO.succeed(current.status)
        now             <- Clock.instant
        _               <- specificationRepository
                             .append(
                               SpecificationEvent.Revised(
                                 specificationId = current.id,
                                 version = current.version + 1,
                                 title = title,
                                 beforeContent = current.content,
                                 afterContent = content,
                                 author = author,
                                 status = status,
                                 linkedPlanRef = fieldStrOpt(args, "linkedPlanRef").orElse(current.linkedPlanRef),
                                 occurredAt = now,
                               )
                             )
                             .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        updated         <- specificationRepository
                             .get(current.id)
                             .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json            <- toJsonAst(updated, "specification")
      yield json,
  )

  private val approveSpecificationTool: Tool = Tool(
    name = "approve_specification",
    description = "Approve a specification",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "specificationId"   -> Json.Obj("type" -> Json.Str("string")),
        "authorId"          -> Json.Obj("type" -> Json.Str("string")),
        "authorDisplayName" -> Json.Obj("type" -> Json.Str("string")),
        "authorKind"        -> Json.Obj("type" -> Json.Str("string")),
      ),
      "required"   -> Json.Arr(
        Chunk(
          Json.Str("specificationId"),
          Json.Str("authorId"),
          Json.Str("authorDisplayName"),
          Json.Str("authorKind"),
        )
      ),
    ),
    execute = args =>
      for
        specificationId <- fieldStr(args, "specificationId")
        author          <- authorFromArgs(args)
        now             <- Clock.instant
        _               <- specificationRepository
                             .append(
                               SpecificationEvent.Approved(
                                 specificationId = SpecificationId(specificationId),
                                 approvedBy = author,
                                 occurredAt = now,
                               )
                             )
                             .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        updated         <- specificationRepository
                             .get(SpecificationId(specificationId))
                             .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json            <- toJsonAst(updated, "specification")
      yield json,
  )

  private val getSpecificationDiffTool: Tool = Tool(
    name = "get_specification_diff",
    description = "Get a content diff between two specification versions",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "specificationId" -> Json.Obj("type" -> Json.Str("string")),
        "fromVersion"     -> Json.Obj("type" -> Json.Str("integer")),
        "toVersion"       -> Json.Obj("type" -> Json.Str("integer")),
      ),
      "required"   -> Json.Arr(
        Chunk(Json.Str("specificationId"), Json.Str("fromVersion"), Json.Str("toVersion"))
      ),
    ),
    execute = args =>
      for
        specificationId <- fieldStr(args, "specificationId")
        fromVersion     <- fieldInt(args, "fromVersion")
        toVersion       <- fieldInt(args, "toVersion")
        diff            <- specificationRepository
                             .diff(SpecificationId(specificationId), fromVersion, toVersion)
                             .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json            <- toJsonAst(diff, "specification diff")
      yield json,
  )

  // ── plan tools ──────────────────────────────────────────────────────────

  private val listPlansTool: Tool = Tool(
    name = "list_plans",
    description = "List plans with optional status filtering",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "status" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = args =>
      for
        status <- fieldStrOpt(args, "status") match
                    case Some(raw) =>
                      ZIO.fromEither(parsePlanStatus(raw))
                        .map(Some(_))
                        .mapError(ToolExecutionError.InvalidParameters.apply)
                    case None      => ZIO.none
        items  <- planRepository
                    .list
                    .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json   <- toJsonAst(items.filter(plan => status.forall(_ == plan.status)), "plans")
      yield json,
  )

  private val getPlanTool: Tool = Tool(
    name = "get_plan",
    description = "Get plan details by plan ID",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "planId" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("planId"))),
    ),
    execute = args =>
      for
        planId <- fieldStr(args, "planId")
        plan   <- planRepository
                    .get(PlanId(planId))
                    .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json   <- toJsonAst(plan, "plan")
      yield json,
  )

  private val createPlanTool: Tool = Tool(
    name = "create_plan",
    description = "Create a new plan draft",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "conversationId"  -> Json.Obj("type" -> Json.Str("integer")),
        "workspaceId"     -> Json.Obj("type" -> Json.Str("string")),
        "specificationId" -> Json.Obj("type" -> Json.Str("string")),
        "summary"         -> Json.Obj("type" -> Json.Str("string")),
        "rationale"       -> Json.Obj("type" -> Json.Str("string")),
        "drafts"          -> Json.Obj("type" -> Json.Str("array")),
      ),
      "required"   -> Json.Arr(
        Chunk(Json.Str("conversationId"), Json.Str("summary"), Json.Str("rationale"))
      ),
    ),
    execute = args =>
      for
        conversationId <- fieldLong(args, "conversationId")
        summary        <- fieldStr(args, "summary")
        rationale      <- fieldStr(args, "rationale")
        drafts         <- decodeFieldOpt[List[PlanTaskDraft]](args, "drafts").map(_.getOrElse(Nil))
        now            <- Clock.instant
        id              = PlanId.generate
        _              <- planRepository
                            .append(
                              PlanEvent.Created(
                                planId = id,
                                conversationId = conversationId,
                                workspaceId = fieldStrOpt(args, "workspaceId"),
                                specificationId = fieldStrOpt(args, "specificationId").map(SpecificationId.apply),
                                summary = summary,
                                rationale = rationale,
                                drafts = drafts,
                                occurredAt = now,
                              )
                            )
                            .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        plan           <- planRepository
                            .get(id)
                            .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json           <- toJsonAst(plan, "plan")
      yield json,
  )

  private val revisePlanTool: Tool = Tool(
    name = "revise_plan",
    description = "Revise an existing plan draft",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "planId"          -> Json.Obj("type" -> Json.Str("string")),
        "workspaceId"     -> Json.Obj("type" -> Json.Str("string")),
        "specificationId" -> Json.Obj("type" -> Json.Str("string")),
        "summary"         -> Json.Obj("type" -> Json.Str("string")),
        "rationale"       -> Json.Obj("type" -> Json.Str("string")),
        "drafts"          -> Json.Obj("type" -> Json.Str("array")),
      ),
      "required"   -> Json.Arr(
        Chunk(Json.Str("planId"), Json.Str("summary"), Json.Str("rationale"))
      ),
    ),
    execute = args =>
      for
        planId    <- fieldStr(args, "planId")
        current   <- planRepository
                       .get(PlanId(planId))
                       .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        summary   <- fieldStr(args, "summary")
        rationale <- fieldStr(args, "rationale")
        drafts    <- decodeFieldOpt[List[PlanTaskDraft]](args, "drafts").map(_.getOrElse(current.drafts))
        now       <- Clock.instant
        _         <- planRepository
                       .append(
                         PlanEvent.Revised(
                           planId = current.id,
                           version = current.version + 1,
                           workspaceId = fieldStrOpt(args, "workspaceId").orElse(current.workspaceId),
                           specificationId = fieldStrOpt(args, "specificationId").map(SpecificationId.apply).orElse(
                             current.specificationId
                           ),
                           summary = summary,
                           rationale = rationale,
                           drafts = drafts,
                           occurredAt = now,
                         )
                       )
                       .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        updated   <- planRepository
                       .get(current.id)
                       .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json      <- toJsonAst(updated, "plan")
      yield json,
  )

  private val validatePlanTool: Tool = Tool(
    name = "validate_plan",
    description = "Validate a plan against governance policy and persist the validation result",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "planId" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("planId"))),
    ),
    execute = args =>
      for
        planId     <- fieldStr(args, "planId")
        plan       <- planRepository
                        .get(PlanId(planId))
                        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        now        <- Clock.instant
        validation <- resolvePlanValidation(plan, now)
        _          <- planRepository
                        .append(PlanEvent.Validated(plan.id, validation, now))
                        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        updated    <- planRepository
                        .get(plan.id)
                        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json       <- toJsonAst(updated, "plan")
      yield json,
  )

  // ── daemon tools ────────────────────────────────────────────────────────

  private val listDaemonsTool: Tool = Tool(
    name = "list_daemons",
    description = "List daemon agents and runtime status",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = _ =>
      for
        statuses <- daemonScheduler
                      .list
                      .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        json     <- toJsonAst(statuses, "daemon statuses")
      yield json,
  )

  private val startDaemonTool: Tool = daemonLifecycleTool(
    name = "start_daemon",
    description = "Start a daemon agent",
    action = "started",
    effect = daemonScheduler.start,
  )

  private val stopDaemonTool: Tool = daemonLifecycleTool(
    name = "stop_daemon",
    description = "Stop a daemon agent",
    action = "stopped",
    effect = daemonScheduler.stop,
  )

  private val restartDaemonTool: Tool = daemonLifecycleTool(
    name = "restart_daemon",
    description = "Restart a daemon agent",
    action = "restarted",
    effect = daemonScheduler.restart,
  )

  private val setDaemonEnabledTool: Tool = Tool(
    name = "set_daemon_enabled",
    description = "Enable or disable a daemon agent",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "daemonId" -> Json.Obj("type" -> Json.Str("string")),
        "enabled"  -> Json.Obj("type" -> Json.Str("boolean")),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("daemonId"), Json.Str("enabled"))),
    ),
    execute = args =>
      for
        daemonId <- fieldStr(args, "daemonId")
        enabled  <- fieldBool(args, "enabled")
        _        <- daemonScheduler
                      .setEnabled(DaemonAgentSpecId(daemonId), enabled)
                      .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield Json.Obj(
        "daemonId" -> Json.Str(daemonId),
        "enabled"  -> Json.Bool(enabled),
      ),
  )

  private val triggerDaemonTool: Tool = Tool(
    name = "trigger_daemon",
    description = "Trigger a daemon agent immediately",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "daemonId" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("daemonId"))),
    ),
    execute = args =>
      for
        daemonId <- fieldStr(args, "daemonId")
        _        <- daemonScheduler
                      .trigger(DaemonAgentSpecId(daemonId))
                      .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
      yield Json.Obj(
        "daemonId" -> Json.Str(daemonId),
        "action"   -> Json.Str("triggered"),
      ),
  )

  // ── SDLC dashboard tools ────────────────────────────────────────────────

  private val getSdlcDashboardTool: Tool = Tool(
    name = "get_sdlc_dashboard",
    description = "Get the full SDLC dashboard snapshot",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = _ =>
      sdlcDashboardService.snapshot
        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        .map(renderDashboardSnapshot),
  )

  private val getChurnAlertsTool: Tool = Tool(
    name = "get_churn_alerts",
    description = "Get churn alerts from the SDLC dashboard snapshot",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = _ =>
      sdlcDashboardService.snapshot
        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        .map(snapshot => Json.Arr(Chunk.fromIterable(snapshot.churnAlerts.map(renderChurnAlert)))),
  )

  private val getStoppagesTool: Tool = Tool(
    name = "get_stoppages",
    description = "Get stoppage alerts from the SDLC dashboard snapshot",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = _ =>
      sdlcDashboardService.snapshot
        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        .map(snapshot => Json.Arr(Chunk.fromIterable(snapshot.stoppages.map(renderStoppage)))),
  )

  private val getEscalationsTool: Tool = Tool(
    name = "get_escalations",
    description = "Get escalation indicators from the SDLC dashboard snapshot",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(),
      "required"   -> Json.Arr(Chunk.empty),
    ),
    execute = _ =>
      sdlcDashboardService.snapshot
        .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        .map(snapshot => Json.Arr(Chunk.fromIterable(snapshot.escalations.map(renderEscalationIndicator)))),
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
            .sortBy(doc => (doc.updatedAt, doc.createdAt))(
              using Ordering.Tuple2(
                using Ordering[Instant],
                Ordering[Instant],
              ).reverse
            )
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

  private def daemonLifecycleTool(
    name: String,
    description: String,
    action: String,
    effect: DaemonAgentSpecId => IO[shared.errors.PersistenceError, Unit],
  ): Tool =
    Tool(
      name = name,
      description = description,
      parameters = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "daemonId" -> Json.Obj("type" -> Json.Str("string"))
        ),
        "required"   -> Json.Arr(Chunk(Json.Str("daemonId"))),
      ),
      execute = args =>
        for
          daemonId <- fieldStr(args, "daemonId")
          _        <- effect(DaemonAgentSpecId(daemonId)).mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        yield Json.Obj(
          "daemonId" -> Json.Str(daemonId),
          "action"   -> Json.Str(action),
        ),
    )

  private def resolvePlanValidation(plan: Plan, now: Instant): IO[ToolExecutionError, PlanValidationResult] =
    plan.workspaceId match
      case Some(workspaceId) =>
        for
          specification <- ZIO.foreach(plan.specificationId)(id =>
                             specificationRepository
                               .get(id)
                               .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
                           )
          satisfiedGates = {
            val builder = Set.newBuilder[GovernanceGate]
            specification.foreach { spec =>
              if spec.status == SpecificationStatus.Approved then
                builder += GovernanceGate.SpecReview
                builder += GovernanceGate.PlanningReview
            }
            builder.result()
          }
          decision      <- governancePolicyService
                             .evaluateForWorkspace(
                               workspaceId,
                               GovernanceEvaluationContext(
                                 issueType = "plan",
                                 transition = GovernanceTransition(
                                   GovernanceLifecycleStage.Backlog,
                                   GovernanceLifecycleStage.Todo,
                                   GovernanceLifecycleAction.Dispatch,
                                 ),
                                 satisfiedGates = satisfiedGates,
                               ),
                             )
                             .mapError(e => ToolExecutionError.ExecutionFailed(e.toString))
        yield PlanValidationResult(
          status = if decision.allowed then PlanValidationStatus.Passed else PlanValidationStatus.Blocked,
          requiredGates = decision.requiredGates.toList.sortBy(_.toString),
          missingGates = decision.missingGates.toList.sortBy(_.toString),
          humanApprovalRequired = decision.humanApprovalRequired,
          reason = decision.reason,
          validatedAt = now,
        )
      case None              =>
        ZIO.succeed(
          PlanValidationResult(
            status = PlanValidationStatus.Passed,
            validatedAt = now,
          )
        )

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
    getDecisionTool,
    resolveDecisionTool,
    escalateDecisionTool,
    getGovernancePolicyTool,
    evaluateGovernanceTransitionTool,
    listSpecificationsTool,
    getSpecificationTool,
    createSpecificationTool,
    reviseSpecificationTool,
    approveSpecificationTool,
    getSpecificationDiffTool,
    listPlansTool,
    getPlanTool,
    createPlanTool,
    revisePlanTool,
    validatePlanTool,
    listDaemonsTool,
    startDaemonTool,
    stopDaemonTool,
    restartDaemonTool,
    setDaemonEnabledTool,
    triggerDaemonTool,
    getSdlcDashboardTool,
    getChurnAlertsTool,
    getStoppagesTool,
    getEscalationsTool,
    proposeEvolutionTool,
    listProposalsTool,
    getEvolutionHistoryTool,
    getAnalysisDocsTool,
    getAnalysisSummaryTool,
    searchDecisionsTool,
    getArchitecturalContextTool,
  )
