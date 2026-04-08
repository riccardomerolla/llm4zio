package issues.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{ Path, Paths }
import java.time.Instant

import zio.*
import zio.http.*

import analysis.entity.AnalysisType
import board.entity.{ IssueEstimate as BoardIssueEstimate, IssuePriority as BoardIssuePriority, * }
import issues.entity.api.*
import issues.entity.{ AgentIssue as DomainIssue, * }
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, BoardIssueId, IssueId }

object IssueControllerSupport:

  def parseBoardIssueId(raw: String): IO[PersistenceError, BoardIssueId] =
    ZIO.fromEither(BoardIssueId.fromString(raw)).mapError(err => PersistenceError.QueryFailed("board_issue_id", err))

  def parseIssuePriority(raw: String): IssuePriority =
    raw.trim.toLowerCase match
      case "critical" => IssuePriority.Critical
      case "high"     => IssuePriority.High
      case "low"      => IssuePriority.Low
      case _          => IssuePriority.Medium

  def toBoardPriority(priority: IssuePriority): BoardIssuePriority =
    priority match
      case IssuePriority.Critical => BoardIssuePriority.Critical
      case IssuePriority.High     => BoardIssuePriority.High
      case IssuePriority.Medium   => BoardIssuePriority.Medium
      case IssuePriority.Low      => BoardIssuePriority.Low

  def toBoardEstimate(estimate: Option[String]): IO[PersistenceError, Option[BoardIssueEstimate]] =
    estimate match
      case None        => ZIO.none
      case Some("XS")  => ZIO.succeed(Some(BoardIssueEstimate.XS))
      case Some("S")   => ZIO.succeed(Some(BoardIssueEstimate.S))
      case Some("M")   => ZIO.succeed(Some(BoardIssueEstimate.M))
      case Some("L")   => ZIO.succeed(Some(BoardIssueEstimate.L))
      case Some("XL")  => ZIO.succeed(Some(BoardIssueEstimate.XL))
      case Some(other) =>
        ZIO.fail(PersistenceError.QueryFailed("issue_estimate", s"Unsupported board estimate '$other'"))

  def parseAcceptanceCriteria(raw: Option[String]): List[String] =
    raw.toList.flatMap(_.split("[\\n,]").toList).map(_.trim).filter(_.nonEmpty)

  def analysisTitle(analysisType: AnalysisType): String =
    analysisType match
      case AnalysisType.CodeReview   => "Code Review"
      case AnalysisType.Architecture => "Architecture"
      case AnalysisType.Security     => "Security"
      case AnalysisType.Custom(name) => Option(name).map(_.trim).filter(_.nonEmpty).getOrElse("Custom Analysis")

  def buildVscodeUrl(workspacePath: String, filePath: String): String =
    val relative = Paths.get(filePath)
    val resolved =
      if relative.isAbsolute then relative.normalize() else Paths.get(workspacePath).resolve(relative).normalize()
    s"vscode://file${resolved.toUri.getRawPath}"

  def statusMatches(status: IssueStatus, token: String): Boolean =
    parseIssueStatusToken(token).contains(status)

  def parseIssueStatusToken(raw: String): Option[IssueStatus] =
    raw.trim.toLowerCase match
      case "backlog"      => Some(IssueStatus.Backlog)
      case "todo"         => Some(IssueStatus.Todo)
      case "human_review" => Some(IssueStatus.HumanReview)
      case "humanreview"  => Some(IssueStatus.HumanReview)
      case "rework"       => Some(IssueStatus.Rework)
      case "merging"      => Some(IssueStatus.Merging)
      case "done"         => Some(IssueStatus.Done)
      case "canceled"     => Some(IssueStatus.Canceled)
      case "cancelled"    => Some(IssueStatus.Canceled)
      case "duplicated"   => Some(IssueStatus.Duplicated)
      case "archived"     => Some(IssueStatus.Archived)
      case "open"         => Some(IssueStatus.Backlog)
      case "assigned"     => Some(IssueStatus.Todo)
      case "in_progress"  => Some(IssueStatus.InProgress)
      case "inprogress"   => Some(IssueStatus.InProgress)
      case "completed"    => Some(IssueStatus.Done)
      case "failed"       => Some(IssueStatus.Rework)
      case "skipped"      => Some(IssueStatus.Canceled)
      case _              => None

  private val allowedBoardTransitions: Map[IssueStatus, Set[IssueStatus]] = Map(
    IssueStatus.Backlog     ->
      Set(IssueStatus.Todo, IssueStatus.Done, IssueStatus.Canceled, IssueStatus.Duplicated, IssueStatus.Archived),
    IssueStatus.Todo        -> Set(
      IssueStatus.Backlog,
      IssueStatus.InProgress,
      IssueStatus.Done,
      IssueStatus.Canceled,
      IssueStatus.Duplicated,
      IssueStatus.Archived,
    ),
    IssueStatus.InProgress  -> Set(
      IssueStatus.HumanReview,
      IssueStatus.Done,
      IssueStatus.Canceled,
      IssueStatus.Duplicated,
      IssueStatus.Archived,
    ),
    IssueStatus.HumanReview ->
      Set(
        IssueStatus.Rework,
        IssueStatus.Merging,
        IssueStatus.Done,
        IssueStatus.Canceled,
        IssueStatus.Duplicated,
        IssueStatus.Archived,
      ),
    IssueStatus.Rework      ->
      Set(
        IssueStatus.Backlog,
        IssueStatus.Todo,
        IssueStatus.InProgress,
        IssueStatus.Merging,
        IssueStatus.Done,
        IssueStatus.Canceled,
        IssueStatus.Duplicated,
        IssueStatus.Archived,
      ),
    IssueStatus.Merging     ->
      Set(IssueStatus.Done, IssueStatus.Canceled, IssueStatus.Duplicated, IssueStatus.Archived),
    IssueStatus.Done        -> Set(IssueStatus.Backlog, IssueStatus.Archived),
    IssueStatus.Canceled    -> Set(IssueStatus.Backlog, IssueStatus.Archived),
    IssueStatus.Duplicated  -> Set(IssueStatus.Archived),
    IssueStatus.Archived    -> Set(IssueStatus.Backlog),
  )

  def canonicalBoardStatus(status: IssueStatus): IssueStatus = status

  def boardStatusFromState(state: IssueState): IssueStatus =
    state match
      case IssueState.Backlog(_)         => IssueStatus.Backlog
      case IssueState.Todo(_)            => IssueStatus.Todo
      case IssueState.Open(_)            => IssueStatus.Backlog
      case IssueState.Assigned(_, _)     => IssueStatus.Todo
      case IssueState.InProgress(_, _)   => IssueStatus.InProgress
      case IssueState.HumanReview(_)     => IssueStatus.HumanReview
      case IssueState.Rework(_, _)       => IssueStatus.Rework
      case IssueState.Merging(_)         => IssueStatus.Merging
      case IssueState.Done(_, _)         => IssueStatus.Done
      case IssueState.Canceled(_, _)     => IssueStatus.Canceled
      case IssueState.Duplicated(_, _)   => IssueStatus.Duplicated
      case IssueState.Archived(_)        => IssueStatus.Archived
      case IssueState.Completed(_, _, _) => IssueStatus.Done
      case IssueState.Failed(_, _, _)    => IssueStatus.Rework
      case IssueState.Skipped(_, _)      => IssueStatus.Canceled

  def domainIssueToView(issue: DomainIssue): AgentIssueView =
    val (status, assignedAgent, assignedAt, completedAt, errorMessage) = issue.state match
      case IssueState.Backlog(_)            => (IssueStatus.Backlog, None, None, None, None)
      case IssueState.Todo(at)              => (IssueStatus.Todo, None, Some(at), None, None)
      case IssueState.Open(_)               => (IssueStatus.Backlog, None, None, None, None)
      case IssueState.Assigned(agent, at)   => (IssueStatus.Todo, Some(agent.value), Some(at), None, None)
      case IssueState.InProgress(agent, at) => (IssueStatus.InProgress, Some(agent.value), Some(at), None, None)
      case IssueState.HumanReview(at)       => (IssueStatus.HumanReview, None, None, Some(at), None)
      case IssueState.Rework(at, msg)       => (IssueStatus.Rework, None, None, Some(at), Some(msg))
      case IssueState.Merging(at)           => (IssueStatus.Merging, None, None, Some(at), None)
      case IssueState.Done(at, _)           => (IssueStatus.Done, None, None, Some(at), None)
      case IssueState.Canceled(at, msg)     => (IssueStatus.Canceled, None, None, Some(at), Some(msg))
      case IssueState.Duplicated(at, msg)   => (IssueStatus.Duplicated, None, None, Some(at), Some(msg))
      case IssueState.Archived(at)          => (IssueStatus.Archived, None, None, Some(at), None)
      case IssueState.Completed(_, at, _)   => (IssueStatus.Done, None, None, Some(at), None)
      case IssueState.Failed(_, at, msg)    => (IssueStatus.Rework, None, None, Some(at), Some(msg))
      case IssueState.Skipped(at, reason)   => (IssueStatus.Canceled, None, None, Some(at), Some(reason))
    val priority                                                       =
      IssuePriority.values.find(_.toString.equalsIgnoreCase(issue.priority)).getOrElse(IssuePriority.Medium)
    val createdAt                                                      = issue.state match
      case IssueState.Backlog(at) => at
      case IssueState.Open(at)    => at
      case _                      => Instant.EPOCH
    AgentIssueView(
      id = Some(issue.id.value),
      runId = issue.runId.map(_.value),
      conversationId = issue.conversationId.map(_.value),
      title = issue.title,
      description = issue.description,
      issueType = issue.issueType,
      tags = if issue.tags.isEmpty then None else Some(issue.tags.mkString(",")),
      requiredCapabilities =
        if issue.requiredCapabilities.isEmpty then None else Some(issue.requiredCapabilities.mkString(",")),
      contextPath = Option(issue.contextPath).filter(_.nonEmpty),
      sourceFolder = Option(issue.sourceFolder).filter(_.nonEmpty),
      workspaceId = issue.workspaceId,
      externalRef = issue.externalRef,
      externalUrl = issue.externalUrl,
      promptTemplate = issue.promptTemplate,
      acceptanceCriteria = issue.acceptanceCriteria,
      estimate = issue.estimate,
      kaizenSkill = issue.kaizenSkill,
      proofOfWorkRequirements = issue.proofOfWorkRequirements,
      priority = priority,
      status = status,
      assignedAgent = assignedAgent,
      assignedAt = assignedAt,
      completedAt = completedAt,
      errorMessage = errorMessage,
      mergeConflictFiles = issue.mergeConflictFiles,
      createdAt = createdAt,
      updatedAt = assignedAt.orElse(completedAt).getOrElse(createdAt),
    )

  def ensureTransitionAllowed(
    currentState: IssueState,
    requestedStatus: IssueStatus,
    issueId: String,
  ): IO[PersistenceError, Unit] =
    val from    = boardStatusFromState(currentState)
    val allowed = allowedBoardTransitions.getOrElse(from, Set.empty)
    if allowed.contains(requestedStatus) then ZIO.unit
    else
      ZIO.fail(
        PersistenceError.QueryFailed(
          "status_transition",
          s"Invalid transition for issue $issueId: ${from.toString} -> ${requestedStatus.toString}",
        )
      )

  def ensureHumanReviewApprovalAllowed(issue: DomainIssue): IO[PersistenceError, Unit] =
    issue.state match
      case _: IssueState.HumanReview => ZIO.unit
      case other                     =>
        ZIO.fail(
          PersistenceError.QueryFailed(
            "approve_issue",
            s"Only HumanReview issues can be approved: ${issue.id.value} is ${other.toString}",
          )
        )

  def approvalEvents(
    issue: DomainIssue,
    approvedBy: String,
    autoMerge: Boolean,
    now: Instant,
  ): List[IssueEvent] =
    val approved   = IssueEvent.Approved(issue.id, approvedBy, now, now)
    val transition =
      if autoMerge then IssueEvent.MovedToMerging(issue.id, movedAt = now, occurredAt = now)
      else IssueEvent.MarkedDone(issue.id, doneAt = now, result = s"Approved by $approvedBy", occurredAt = now)
    List(approved, transition)

  def parseBooleanConfig(value: String): Boolean =
    value.trim.equalsIgnoreCase("true") || value.trim == "1" || value.trim.equalsIgnoreCase("yes")

  def statusToEvents(
    issue: DomainIssue,
    request: IssueStatusUpdateRequest,
    fallbackAgent: String,
    now: Instant,
  ): IO[PersistenceError, List[IssueEvent]] =
    val issueId                  = issue.id
    val primaryEvent: IssueEvent = request.status match
      case IssueStatus.Backlog     => IssueEvent.MovedToBacklog(issueId = issueId, movedAt = now, occurredAt = now)
      case IssueStatus.Todo        => IssueEvent.MovedToTodo(issueId = issueId, movedAt = now, occurredAt = now)
      case IssueStatus.InProgress  =>
        IssueEvent.Started(issueId = issueId, agent = AgentId(fallbackAgent), startedAt = now, occurredAt = now)
      case IssueStatus.HumanReview => IssueEvent.MovedToHumanReview(issueId = issueId, movedAt = now, occurredAt = now)
      case IssueStatus.Rework      =>
        IssueEvent.MovedToRework(
          issueId = issueId,
          movedAt = now,
          reason = request.reason.map(_.trim).filter(_.nonEmpty).getOrElse("Needs rework"),
          occurredAt = now,
        )
      case IssueStatus.Merging     => IssueEvent.MovedToMerging(issueId = issueId, movedAt = now, occurredAt = now)
      case IssueStatus.Done        =>
        IssueEvent.MarkedDone(
          issueId = issueId,
          doneAt = now,
          result = request.resultData.map(_.trim).filter(_.nonEmpty).getOrElse("Completed"),
          occurredAt = now,
        )
      case IssueStatus.Canceled    =>
        IssueEvent.Canceled(
          issueId = issueId,
          canceledAt = now,
          reason = request.reason.map(_.trim).filter(_.nonEmpty).getOrElse("Canceled"),
          occurredAt = now,
        )
      case IssueStatus.Duplicated  =>
        IssueEvent.Duplicated(
          issueId = issueId,
          duplicatedAt = now,
          reason = request.reason.map(_.trim).filter(_.nonEmpty).getOrElse("Duplicated"),
          occurredAt = now,
        )
      case IssueStatus.Archived    => IssueEvent.MovedToBacklog(issueId = issueId, movedAt = now, occurredAt = now)
    ZIO.succeed(List(primaryEvent))

  def parseTagList(raw: Option[String]): List[String] =
    raw.toList.flatMap(_.split(",").toList).map(_.trim).filter(_.nonEmpty).distinct

  def parseCapabilityList(raw: Option[String]): List[String] =
    raw.toList.flatMap(_.split(",").toList).map(_.trim.toLowerCase).filter(_.nonEmpty).distinct

  def required(form: Map[String, String], key: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(form.get(key).map(_.trim).filter(_.nonEmpty))
      .orElseFail(PersistenceError.QueryFailed("parseForm", s"Missing field '$key'"))

  def optional(form: Map[String, String], key: String): Option[String] =
    form.get(key).map(_.trim).filter(_.nonEmpty)

  def sanitizeProofRequirements(requirements: List[String]): List[String] =
    requirements.map(_.trim).filter(_.nonEmpty).distinct

  def parseProofOfWorkRequirements(raw: Option[String]): List[String] =
    raw.toList.flatMap(_.split("[\\n,]").toList).map(_.trim).filter(_.nonEmpty).distinct

  def parseEstimate(raw: Option[String]): IO[PersistenceError, Option[String]] =
    raw match
      case None        => ZIO.none
      case Some(value) =>
        ZIO
          .fromOption(DomainIssue.normalizeEstimate(value))
          .orElseFail(
            PersistenceError.QueryFailed(
              "issue_estimate",
              s"Estimate must be one of: ${DomainIssue.ValidEstimates.toList.sorted.mkString(", ")}",
            )
          )
          .map(Some(_))

  def executionPrompt(issue: DomainIssue): String =
    issue.promptTemplate
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(template => renderPromptTemplate(template, issue))
      .filter(_.trim.nonEmpty)
      .getOrElse {
        val sections = List(
          Option(issue.description).map(_.trim).filter(_.nonEmpty),
          issue.acceptanceCriteria.map(criteria => s"Acceptance criteria:\n$criteria"),
          issue.estimate.map(value => s"Estimate:\n$value"),
          issue.kaizenSkill.map(skill => s"Kaizen skill:\n$skill"),
          Option.when(issue.proofOfWorkRequirements.nonEmpty) {
            "Proof-of-work requirements:\n" + issue.proofOfWorkRequirements.map(req => s"- $req").mkString("\n")
          },
        ).flatten
        sections.mkString("\n\n").trim
      }

  def renderPromptTemplate(template: String, issue: DomainIssue): String =
    template
      .replace("${title}", issue.title)
      .replace("${description}", issue.description)
      .replace("${acceptanceCriteria}", issue.acceptanceCriteria.getOrElse(""))
      .replace("${estimate}", issue.estimate.getOrElse(""))
      .replace("${kaizenSkill}", issue.kaizenSkill.getOrElse(""))
      .replace("${proofOfWorkRequirements}", issue.proofOfWorkRequirements.mkString(", "))
      .replace("${contextPath}", issue.contextPath)
      .replace("${sourceFolder}", issue.sourceFolder)

  def parseMarkdownIssue(file: Path, markdown: String, now: Instant): IssueEvent.Created =
    val lines = markdown.linesIterator.toList
    val title =
      lines
        .find(_.trim.startsWith("#"))
        .map(_.replaceFirst("^#+\\s*", "").trim)
        .filter(_.nonEmpty)
        .getOrElse(file.getFileName.toString.stripSuffix(".md"))

    def metadata(key: String): Option[String] =
      lines.find(_.toLowerCase.startsWith(s"$key:")).flatMap(_.split(":", 2).lift(1).map(_.trim).filter(_.nonEmpty))

    IssueEvent.Created(
      issueId = IssueId.generate,
      title = title,
      description = markdown,
      issueType = metadata("type").getOrElse("task"),
      priority = metadata("priority").getOrElse("medium"),
      occurredAt = now,
      requiredCapabilities =
        parseCapabilityList(metadata("required_capabilities").orElse(metadata("required-capabilities"))),
    )

  def withQuery(basePath: String, req: Request): String =
    val knownKeys = List("mode", "run_id", "status", "q", "tag", "workspace", "agent", "priority")
    knownKeys.flatMap(key => req.queryParam(key).map(v => s"${urlEncode(key)}=${urlEncode(v)}")) match
      case Nil    => basePath
      case params =>
        val sep = if basePath.contains("?") then "&" else "?"
        s"$basePath$sep${params.mkString("&")}"

  def redirectPermanent(path: String): Response =
    Response(
      status = Status.MovedPermanently,
      headers = Headers(Header.Location(URL.decode(path).getOrElse(URL.root))),
    )

  def parseForm(req: Request): IO[PersistenceError, Map[String, String]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap { kv =>
            kv.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .toMap
      }
      .mapError(err => PersistenceError.QueryFailed("parseForm", err.getMessage))

  def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  def urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
