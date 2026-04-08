package mcp

import zio.*
import zio.json.*
import zio.json.ast.Json

import _root_.config.entity.WorkflowDefinition
import analysis.entity.AnalysisType
import daemon.entity.DaemonAgentSpec
import decision.entity.*
import evolution.entity.*
import governance.control.GovernanceTransitionDecision
import governance.entity.*
import llm4zio.tools.ToolExecutionError
import plan.entity.*
import sdlc.entity.*
import shared.ids.Ids.ProjectId
import specification.entity.*

private[mcp] object GatewayMcpToolSupport:

  def fieldStr(args: Json, key: String): IO[ToolExecutionError, String] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(key) match
          case Some(Json.Str(v)) => ZIO.succeed(v)
          case _                 => ZIO.fail(ToolExecutionError.InvalidParameters(s"Missing required string field: $key"))
      case _                =>
        ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be a JSON object"))

  def fieldStrOpt(args: Json, key: String): Option[String] =
    args match
      case Json.Obj(fields) => fields.toMap.get(key).collect { case Json.Str(v) => v }
      case _                => None

  def fieldInt(args: Json, key: String): IO[ToolExecutionError, Int] =
    fieldLong(args, key).map(_.toInt)

  def fieldIntOpt(args: Json, key: String): Option[Int] =
    args match
      case Json.Obj(fields) => fields.toMap.get(key).collect { case Json.Num(v) => v.intValue() }
      case _                => None

  def fieldLong(args: Json, key: String): IO[ToolExecutionError, Long] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(key) match
          case Some(Json.Num(v)) => ZIO.succeed(v.longValue)
          case _                 => ZIO.fail(ToolExecutionError.InvalidParameters(s"Missing required integer field: $key"))
      case _                =>
        ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be a JSON object"))

  def fieldBool(args: Json, key: String): IO[ToolExecutionError, Boolean] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(key) match
          case Some(Json.Bool(v)) => ZIO.succeed(v)
          case _                  => ZIO.fail(ToolExecutionError.InvalidParameters(s"Missing required boolean field: $key"))
      case _                =>
        ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be a JSON object"))

  def fieldBoolOpt(args: Json, key: String): Option[Boolean] =
    args match
      case Json.Obj(fields) => fields.toMap.get(key).collect { case Json.Bool(v) => v }
      case _                => None

  def decodeFieldOpt[A: JsonDecoder](args: Json, key: String): IO[ToolExecutionError, Option[A]] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(key) match
          case Some(value) =>
            ZIO
              .fromEither(value.toJson.fromJson[A])
              .map(Some(_))
              .mapError(error => ToolExecutionError.InvalidParameters(s"Invalid $key: $error"))
          case None        => ZIO.none
      case _                =>
        ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be a JSON object"))

  def fieldStrListOpt(args: Json, key: String): List[String] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(key) match
          case Some(Json.Arr(values)) => values.collect { case Json.Str(value) => value }.toList
          case _                      => Nil
      case _                => Nil

  def fieldObj(args: Json, key: String): IO[ToolExecutionError, Json.Obj] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(key) match
          case Some(value: Json.Obj) => ZIO.succeed(value)
          case _                     => ZIO.fail(ToolExecutionError.InvalidParameters(s"Missing required object field: $key"))
      case _                =>
        ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be a JSON object"))

  def toJsonAst[A: JsonEncoder](value: A, label: String): IO[ToolExecutionError, Json] =
    ZIO
      .fromEither(value.toJsonAST)
      .mapError(error => ToolExecutionError.ExecutionFailed(s"Failed to encode $label: $error"))

  def authorFromArgs(args: Json): IO[ToolExecutionError, SpecificationAuthor] =
    for
      authorId          <- fieldStr(args, "authorId")
      authorDisplayName <- fieldStr(args, "authorDisplayName")
      authorKind        <- fieldStr(args, "authorKind").flatMap(raw =>
                             ZIO.fromEither(parseSpecificationAuthorKind(raw))
                               .mapError(ToolExecutionError.InvalidParameters.apply)
                           )
    yield SpecificationAuthor(authorKind, authorId, authorDisplayName)

  def parseSpecificationStatus(raw: String): Either[String, SpecificationStatus] =
    raw.trim.toLowerCase match
      case "draft"        => Right(SpecificationStatus.Draft)
      case "inrefinement" => Right(SpecificationStatus.InRefinement)
      case "approved"     => Right(SpecificationStatus.Approved)
      case "superseded"   => Right(SpecificationStatus.Superseded)
      case other          => Left(s"Unknown specification status: $other")

  def parseSpecificationAuthorKind(raw: String): Either[String, SpecificationAuthorKind] =
    raw.trim.toLowerCase match
      case "human" => Right(SpecificationAuthorKind.Human)
      case "agent" => Right(SpecificationAuthorKind.Agent)
      case other   => Left(s"Unknown specification author kind: $other")

  def parsePlanStatus(raw: String): Either[String, PlanStatus] =
    raw.trim.toLowerCase match
      case "draft"     => Right(PlanStatus.Draft)
      case "validated" => Right(PlanStatus.Validated)
      case "executing" => Right(PlanStatus.Executing)
      case "completed" => Right(PlanStatus.Completed)
      case "abandoned" => Right(PlanStatus.Abandoned)
      case other       => Left(s"Unknown plan status: $other")

  def parseGovernanceStage(raw: String): Either[String, GovernanceLifecycleStage] =
    raw.trim.toLowerCase match
      case "backlog"     => Right(GovernanceLifecycleStage.Backlog)
      case "todo"        => Right(GovernanceLifecycleStage.Todo)
      case "inprogress"  => Right(GovernanceLifecycleStage.InProgress)
      case "humanreview" => Right(GovernanceLifecycleStage.HumanReview)
      case "rework"      => Right(GovernanceLifecycleStage.Rework)
      case "merging"     => Right(GovernanceLifecycleStage.Merging)
      case "done"        => Right(GovernanceLifecycleStage.Done)
      case other         => Left(s"Unknown governance stage: $other")

  def parseGovernanceAction(raw: String): Either[String, GovernanceLifecycleAction] =
    raw.trim.toLowerCase match
      case "dispatch"     => Right(GovernanceLifecycleAction.Dispatch)
      case "startwork"    => Right(GovernanceLifecycleAction.StartWork)
      case "completework" => Right(GovernanceLifecycleAction.CompleteWork)
      case "approve"      => Right(GovernanceLifecycleAction.Approve)
      case "merge"        => Right(GovernanceLifecycleAction.Merge)
      case "requeue"      => Right(GovernanceLifecycleAction.Requeue)
      case other          => Left(s"Unknown governance action: $other")

  def parseGovernanceGate(raw: String): Either[String, GovernanceGate] =
    raw.trim.toLowerCase match
      case "specreview"            => Right(GovernanceGate.SpecReview)
      case "planningreview"        => Right(GovernanceGate.PlanningReview)
      case "humanapproval"         => Right(GovernanceGate.HumanApproval)
      case "codereview"            => Right(GovernanceGate.CodeReview)
      case "cipassed"              => Right(GovernanceGate.CiPassed)
      case "proofofwork"           => Right(GovernanceGate.ProofOfWork)
      case value if value.nonEmpty => Right(GovernanceGate.Custom(raw.trim))
      case _                       => Left("Governance gate must be non-empty")

  def parseGovernanceGates(values: List[String]): IO[ToolExecutionError, Set[GovernanceGate]] =
    ZIO
      .foreach(values)(raw =>
        ZIO.fromEither(parseGovernanceGate(raw)).mapError(ToolExecutionError.InvalidParameters.apply)
      )
      .map(_.toSet)

  def renderGovernanceDecision(decision: GovernanceTransitionDecision): Json =
    Json.Obj(
      "allowed"               -> Json.Bool(decision.allowed),
      "requiredGates"         -> Json.Arr(Chunk.fromIterable(decision.requiredGates.toList.sortBy(_.toString).map(gate =>
        Json.Str(gate.toString)
      ))),
      "missingGates"          -> Json.Arr(Chunk.fromIterable(decision.missingGates.toList.sortBy(_.toString).map(gate =>
        Json.Str(gate.toString)
      ))),
      "humanApprovalRequired" -> Json.Bool(decision.humanApprovalRequired),
      "daemonTriggers"        -> Json.Arr(
        Chunk.fromIterable(
          decision.daemonTriggers.map(trigger =>
            Json.Obj(
              "id"         -> Json.Str(trigger.id),
              "agentName"  -> Json.Str(trigger.agentName),
              "issueTypes" -> Json.Arr(Chunk.fromIterable(trigger.issueTypes.map(Json.Str(_)))),
              "enabled"    -> Json.Bool(trigger.enabled),
            )
          )
        )
      ),
      "escalationRules"       -> Json.Arr(
        Chunk.fromIterable(
          decision.escalationRules.map(rule =>
            Json.Obj(
              "id"     -> Json.Str(rule.id),
              "kind"   -> Json.Str(rule.kind.toString),
              "target" -> Json.Str(rule.target),
            )
          )
        )
      ),
      "reason"                -> Json.Str(decision.reason.getOrElse("")),
    )

  def renderDashboardSnapshot(snapshot: SdlcSnapshot): Json =
    Json.Obj(
      "generatedAt"      -> Json.Str(snapshot.generatedAt.toString),
      "counts"           -> Json.Obj(
        "specifications"   -> Json.Num(BigDecimal(snapshot.specificationCount)),
        "plans"            -> Json.Num(BigDecimal(snapshot.planCount)),
        "issues"           -> Json.Num(BigDecimal(snapshot.issueCount)),
        "pendingDecisions" -> Json.Num(BigDecimal(snapshot.pendingDecisionCount)),
      ),
      "lifecycle"        -> Json.Arr(Chunk.fromIterable(snapshot.lifecycle.map(stage =>
        Json.Obj(
          "key"         -> Json.Str(stage.key),
          "label"       -> Json.Str(stage.label),
          "count"       -> Json.Num(BigDecimal(stage.count)),
          "href"        -> Json.Str(stage.href),
          "description" -> Json.Str(stage.description),
        )
      ))),
      "churnAlerts"      -> Json.Arr(Chunk.fromIterable(snapshot.churnAlerts.map(renderChurnAlert))),
      "stoppages"        -> Json.Arr(Chunk.fromIterable(snapshot.stoppages.map(renderStoppage))),
      "escalations"      -> Json.Arr(Chunk.fromIterable(snapshot.escalations.map(renderEscalationIndicator))),
      "agentPerformance" -> Json.Arr(Chunk.fromIterable(snapshot.agentPerformance.map(item =>
        Json.Obj(
          "agentName"         -> Json.Str(item.agentName),
          "throughput"        -> Json.Num(BigDecimal(item.throughput)),
          "successRate"       -> Json.Num(BigDecimal(item.successRate)),
          "averageCycleHours" -> Json.Num(BigDecimal(item.averageCycleHours)),
          "activeIssues"      -> Json.Num(BigDecimal(item.activeIssues)),
          "costUsd"           -> Json.Num(BigDecimal(item.costUsd)),
        )
      ))),
      "recentActivity"   -> Json.Num(BigDecimal(snapshot.recentActivity.size)),
    )

  def renderChurnAlert(alert: ChurnAlert): Json =
    Json.Obj(
      "issueId"         -> Json.Str(alert.issueId),
      "title"           -> Json.Str(alert.title),
      "transitionCount" -> Json.Num(BigDecimal(alert.transitionCount)),
      "bounceCount"     -> Json.Num(BigDecimal(alert.bounceCount)),
      "currentState"    -> Json.Str(alert.currentState),
      "lastChangedAt"   -> Json.Str(alert.lastChangedAt.toString),
    )

  def renderStoppage(alert: StoppageAlert): Json =
    Json.Obj(
      "kind"         -> Json.Str(alert.kind),
      "issueId"      -> Json.Str(alert.issueId),
      "title"        -> Json.Str(alert.title),
      "currentState" -> Json.Str(alert.currentState),
      "ageHours"     -> Json.Num(BigDecimal(alert.ageHours)),
      "blockedBy"    -> Json.Arr(Chunk.fromIterable(alert.blockedBy.map(Json.Str(_)))),
    )

  def renderEscalationIndicator(indicator: EscalationIndicator): Json =
    Json.Obj(
      "kind"        -> Json.Str(indicator.kind),
      "referenceId" -> Json.Str(indicator.referenceId),
      "title"       -> Json.Str(indicator.title),
      "urgency"     -> Json.Str(indicator.urgency),
      "ageHours"    -> Json.Num(BigDecimal(indicator.ageHours)),
      "summary"     -> Json.Str(indicator.summary),
    )

  def parseAnalysisType(raw: String): Either[String, AnalysisType] =
    raw.trim.toLowerCase match
      case "code_review" | "codereview" | "code-review" => Right(AnalysisType.CodeReview)
      case "architecture"                               => Right(AnalysisType.Architecture)
      case "security"                                   => Right(AnalysisType.Security)
      case value if value.nonEmpty                      => Right(AnalysisType.Custom(value))
      case _                                            => Left("analysisType must be a non-empty string")

  def parseDecisionStatus(raw: String): Either[String, DecisionStatus] =
    raw.trim.toLowerCase match
      case "pending"   => Right(DecisionStatus.Pending)
      case "resolved"  => Right(DecisionStatus.Resolved)
      case "escalated" => Right(DecisionStatus.Escalated)
      case "expired"   => Right(DecisionStatus.Expired)
      case other       => Left(s"Unknown decision status: $other")

  def parseDecisionSourceKind(raw: String): Either[String, DecisionSourceKind] =
    raw.trim.toLowerCase match
      case "issue_review" | "issuereview"         => Right(DecisionSourceKind.IssueReview)
      case "governance"                           => Right(DecisionSourceKind.Governance)
      case "agent_escalation" | "agentescalation" => Right(DecisionSourceKind.AgentEscalation)
      case "manual"                               => Right(DecisionSourceKind.Manual)
      case other                                  => Left(s"Unknown decision source: $other")

  def parseDecisionUrgency(raw: String): Either[String, DecisionUrgency] =
    raw.trim.toLowerCase match
      case "low"      => Right(DecisionUrgency.Low)
      case "medium"   => Right(DecisionUrgency.Medium)
      case "high"     => Right(DecisionUrgency.High)
      case "critical" => Right(DecisionUrgency.Critical)
      case other      => Left(s"Unknown decision urgency: $other")

  def parseDecisionResolution(raw: String): Either[String, DecisionResolutionKind] =
    raw.trim.toLowerCase match
      case "approved"        => Right(DecisionResolutionKind.Approved)
      case "reworkrequested" => Right(DecisionResolutionKind.ReworkRequested)
      case "acknowledged"    => Right(DecisionResolutionKind.Acknowledged)
      case "escalated"       => Right(DecisionResolutionKind.Escalated)
      case "expired"         => Right(DecisionResolutionKind.Expired)
      case other             => Left(s"Unknown decision resolution: $other")

  def parseEvolutionStatus(raw: String): Either[String, EvolutionProposalStatus] =
    raw.trim.toLowerCase match
      case "proposed"   => Right(EvolutionProposalStatus.Proposed)
      case "approved"   => Right(EvolutionProposalStatus.Approved)
      case "applied"    => Right(EvolutionProposalStatus.Applied)
      case "rolledback" => Right(EvolutionProposalStatus.RolledBack)
      case other        => Left(s"Unknown evolution status: $other")

  def parseEvolutionTemplate(raw: String): Either[String, EvolutionTemplateKind] =
    raw.trim.toLowerCase match
      case "add_quality_gate" | "addqualitygate"               => Right(EvolutionTemplateKind.AddQualityGate)
      case "change_testing_strategy" | "changetestingstrategy" => Right(EvolutionTemplateKind.ChangeTestingStrategy)
      case "add_daemon_agent" | "adddaemonagent"               => Right(EvolutionTemplateKind.AddDaemonAgent)
      case other if other.nonEmpty                             => Right(EvolutionTemplateKind.Custom(other))
      case _                                                   => Left("Template must be a non-empty string")

  def parseEvolutionTarget(projectId: String, rawKind: String, payload: Json.Obj): Either[String, EvolutionTarget] =
    val project = ProjectId(projectId)
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

  def renderAnalysisType(analysisType: AnalysisType): String =
    analysisType match
      case AnalysisType.CodeReview   => "CodeReview"
      case AnalysisType.Architecture => "Architecture"
      case AnalysisType.Security     => "Security"
      case AnalysisType.Custom(name) => name

  def summarizeAnalysis(markdown: String): String =
    val lines            = markdown.linesIterator.map(_.trim).toList
    val executiveSummary = sectionBody(lines, "executive summary")
    executiveSummary
      .orElse(firstParagraph(lines))
      .getOrElse("No summary available.")

  def analysisTypeRank(analysisType: AnalysisType): Int =
    analysisType match
      case AnalysisType.CodeReview   => 0
      case AnalysisType.Architecture => 1
      case AnalysisType.Security     => 2
      case AnalysisType.Custom(_)    => 3

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
