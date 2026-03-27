package decision.control

import java.time.Instant

import zio.*
import zio.json.*

import _root_.config.entity.ConfigRepository
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import decision.entity.*
import issues.entity.{ AgentIssue, IssueEvent, IssueRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, EventId, IssueId }

trait DecisionInbox:
  def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision]
  def openManualDecision(
    title: String,
    context: String,
    referenceId: String,
    summary: String,
    urgency: DecisionUrgency = DecisionUrgency.Medium,
    workspaceId: Option[String] = None,
    issueId: Option[IssueId] = None,
  ): IO[PersistenceError, Decision] =
    ZIO.fail(PersistenceError.QueryFailed("decision_manual", "Manual decision creation not implemented"))
  def resolve(id: DecisionId, resolutionKind: DecisionResolutionKind, actor: String, summary: String)
    : IO[PersistenceError, Decision]
  def syncOpenIssueReviewDecision(
    issueId: IssueId,
    resolutionKind: DecisionResolutionKind,
    actor: String,
    summary: String,
  ): IO[PersistenceError, Option[Decision]]
  def resolveOpenIssueReviewDecision(
    issueId: IssueId,
    resolutionKind: DecisionResolutionKind,
    actor: String,
    summary: String,
  ): IO[PersistenceError, Option[Decision]]
  def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision]
  def get(id: DecisionId): IO[PersistenceError, Decision]
  def list(filter: DecisionFilter = DecisionFilter()): IO[PersistenceError, List[Decision]]
  def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]]

object DecisionInbox:
  def openIssueReviewDecision(issue: AgentIssue): ZIO[DecisionInbox, PersistenceError, Decision] =
    ZIO.serviceWithZIO[DecisionInbox](_.openIssueReviewDecision(issue))

  def openManualDecision(
    title: String,
    context: String,
    referenceId: String,
    summary: String,
    urgency: DecisionUrgency = DecisionUrgency.Medium,
    workspaceId: Option[String] = None,
    issueId: Option[IssueId] = None,
  ): ZIO[DecisionInbox, PersistenceError, Decision] =
    ZIO.serviceWithZIO[DecisionInbox](
      _.openManualDecision(title, context, referenceId, summary, urgency, workspaceId, issueId)
    )

  def resolve(
    id: DecisionId,
    resolutionKind: DecisionResolutionKind,
    actor: String,
    summary: String,
  ): ZIO[DecisionInbox, PersistenceError, Decision] =
    ZIO.serviceWithZIO[DecisionInbox](_.resolve(id, resolutionKind, actor, summary))

  def syncOpenIssueReviewDecision(
    issueId: IssueId,
    resolutionKind: DecisionResolutionKind,
    actor: String,
    summary: String,
  ): ZIO[DecisionInbox, PersistenceError, Option[Decision]] =
    ZIO.serviceWithZIO[DecisionInbox](_.syncOpenIssueReviewDecision(issueId, resolutionKind, actor, summary))

  def resolveOpenIssueReviewDecision(
    issueId: IssueId,
    resolutionKind: DecisionResolutionKind,
    actor: String,
    summary: String,
  ): ZIO[DecisionInbox, PersistenceError, Option[Decision]] =
    ZIO.serviceWithZIO[DecisionInbox](_.resolveOpenIssueReviewDecision(issueId, resolutionKind, actor, summary))

  def escalate(id: DecisionId, reason: String): ZIO[DecisionInbox, PersistenceError, Decision] =
    ZIO.serviceWithZIO[DecisionInbox](_.escalate(id, reason))

  def get(id: DecisionId): ZIO[DecisionInbox, PersistenceError, Decision] =
    ZIO.serviceWithZIO[DecisionInbox](_.get(id))

  def list(filter: DecisionFilter = DecisionFilter()): ZIO[DecisionInbox, PersistenceError, List[Decision]] =
    ZIO.serviceWithZIO[DecisionInbox](_.list(filter))

  def runMaintenance(now: Instant): ZIO[DecisionInbox, PersistenceError, List[Decision]] =
    ZIO.serviceWithZIO[DecisionInbox](_.runMaintenance(now))

  val live
    : ZLayer[
      ConfigRepository & DecisionRepository & IssueRepository & ActivityHub,
      Nothing,
      DecisionInbox,
    ] =
    ZLayer.fromFunction(DecisionInboxLive.apply)

final case class DecisionInboxLive(
  decisionRepository: DecisionRepository,
  issueRepository: IssueRepository,
  activityHub: ActivityHub,
  configRepository: ConfigRepository,
) extends DecisionInbox:

  override def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision] =
    for
      now      <- Clock.instant
      _        <- runMaintenance(now)
      existing <- openDecisionForIssue(issue.id)
      decision <- existing match
                    case Some(value) => ZIO.succeed(value)
                    case None        => createIssueReviewDecision(issue, now)
    yield decision

  override def openManualDecision(
    title: String,
    context: String,
    referenceId: String,
    summary: String,
    urgency: DecisionUrgency,
    workspaceId: Option[String],
    issueId: Option[IssueId],
  ): IO[PersistenceError, Decision] =
    for
      now            <- Clock.instant
      _              <- runMaintenance(now)
      timeoutSeconds <- loadTimeoutSeconds
      event           = DecisionEvent.Created(
                          decisionId = DecisionId.generate,
                          title = title.trim,
                          context = context.trim,
                          action = DecisionAction.ManualEscalation,
                          source = DecisionSource(
                            kind = DecisionSourceKind.Manual,
                            referenceId = referenceId.trim,
                            summary = summary.trim,
                            workspaceId = workspaceId,
                            issueId = issueId,
                          ),
                          urgency = urgency,
                          deadlineAt = Some(now.plusSeconds(timeoutSeconds)),
                          occurredAt = now,
                        )
      _              <- decisionRepository.append(event)
      created        <- decisionRepository.get(event.decisionId)
      _              <- activityHub.publish(
                          ActivityEvent(
                            id = EventId.generate,
                            eventType = ActivityEventType.DecisionCreated,
                            source = "decision-inbox",
                            summary = s"Manual decision ${created.id.value} opened for ${referenceId.trim}",
                            payload = Some(
                              s"""{"decisionId":"${created.id.value}","referenceId":${referenceId.trim.toJson},"workspaceId":${workspaceId.getOrElse(
                                  ""
                                ).toJson}}"""
                            ),
                            createdAt = now,
                          )
                        )
    yield created

  override def resolve(
    id: DecisionId,
    resolutionKind: DecisionResolutionKind,
    actor: String,
    summary: String,
  ): IO[PersistenceError, Decision] =
    for
      now      <- Clock.instant
      _        <- runMaintenance(now)
      decision <- decisionRepository.get(id)
      _        <- appendResolutionSideEffects(decision, resolutionKind, actor, summary, now)
      updated  <- appendResolutionEvent(decision, resolutionKind, actor, summary)
    yield updated

  override def resolveOpenIssueReviewDecision(
    issueId: IssueId,
    resolutionKind: DecisionResolutionKind,
    actor: String,
    summary: String,
  ): IO[PersistenceError, Option[Decision]] =
    openDecisionForIssue(issueId).flatMap {
      case Some(decision) => resolve(decision.id, resolutionKind, actor, summary).map(Some(_))
      case None           => ZIO.succeed(None)
    }

  override def syncOpenIssueReviewDecision(
    issueId: IssueId,
    resolutionKind: DecisionResolutionKind,
    actor: String,
    summary: String,
  ): IO[PersistenceError, Option[Decision]] =
    openDecisionForIssue(issueId).flatMap {
      case Some(decision) => appendResolutionEvent(decision, resolutionKind, actor, summary).map(Some(_))
      case None           => ZIO.succeed(None)
    }

  override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision] =
    for
      now      <- Clock.instant
      _        <- runMaintenance(now)
      decision <- decisionRepository.get(id)
      _        <- ensureOpen(decision)
      _        <- decisionRepository.append(DecisionEvent.Escalated(id, reason.trim, now))
      updated  <- decisionRepository.get(id)
      _        <- publishEscalation(updated, reason.trim, now)
    yield updated

  override def get(id: DecisionId): IO[PersistenceError, Decision] =
    for
      now      <- Clock.instant
      _        <- runMaintenance(now)
      decision <- decisionRepository.get(id)
    yield decision

  override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]] =
    for
      now       <- Clock.instant
      _         <- runMaintenance(now)
      decisions <- decisionRepository.list(filter)
    yield decisions.sortBy(sortKey)

  override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]] =
    for
      open    <- decisionRepository.list(DecisionFilter(statuses = Set(DecisionStatus.Pending), limit = Int.MaxValue))
      due      = open.filter(_.deadlineAt.exists(!_.isAfter(now)))
      _       <- ZIO.foreachDiscard(due) { decision =>
                   decisionRepository.append(DecisionEvent.Expired(decision.id, now)) *>
                     decisionRepository.append(DecisionEvent.Escalated(decision.id, "Decision deadline exceeded", now))
                 }
      updated <- ZIO.foreach(due)(decision => decisionRepository.get(decision.id))
      _       <- ZIO.foreachDiscard(updated)(decision => publishEscalation(decision, "Decision deadline exceeded", now))
    yield updated

  private def createIssueReviewDecision(issue: AgentIssue, now: Instant): IO[PersistenceError, Decision] =
    for
      timeoutSeconds <- loadTimeoutSeconds
      deadlineAt      = Some(now.plusSeconds(timeoutSeconds))
      urgency         = urgencyFor(issue)
      event           = DecisionEvent.Created(
                          decisionId = DecisionId.generate,
                          title = s"Review issue #${issue.id.value}",
                          context = issue.title,
                          action = DecisionAction.ReviewIssue,
                          source = DecisionSource(
                            kind = DecisionSourceKind.IssueReview,
                            referenceId = issue.id.value,
                            summary = issue.description,
                            workspaceId = issue.workspaceId,
                            issueId = Some(issue.id),
                          ),
                          urgency = urgency,
                          deadlineAt = deadlineAt,
                          occurredAt = now,
                        )
      _              <- decisionRepository.append(event)
      created        <- decisionRepository.get(event.decisionId)
      _              <- activityHub.publish(
                          ActivityEvent(
                            id = EventId.generate,
                            eventType = ActivityEventType.DecisionCreated,
                            source = "decision-inbox",
                            summary = s"Decision ${created.id.value} opened for issue #${issue.id.value}",
                            payload = Some(
                              s"""{"decisionId":"${created.id.value}","issueId":"${issue.id.value}","deadlineAt":"${created.deadlineAt.map(
                                  _.toString
                                ).getOrElse("")}"}"""
                            ),
                            createdAt = now,
                          )
                        )
    yield created

  private def appendResolutionSideEffects(
    decision: Decision,
    resolutionKind: DecisionResolutionKind,
    actor: String,
    summary: String,
    now: Instant,
  ): IO[PersistenceError, Unit] =
    decision.source.kind match
      case DecisionSourceKind.IssueReview =>
        val issueId = decision.source.issueId.getOrElse(IssueId(decision.source.referenceId))
        resolutionKind match
          case DecisionResolutionKind.Approved        =>
            for
              autoMerge <- loadAutoMergePolicy
              approved   = IssueEvent.Approved(issueId, actor.trim, now, now)
              transition =
                if autoMerge then IssueEvent.MovedToMerging(issueId, now, now)
                else IssueEvent.MarkedDone(issueId, now, summarize(summary, resolutionKind), now)
              _         <- issueRepository.append(approved)
              _         <- issueRepository.append(transition)
            yield ()
          case DecisionResolutionKind.ReworkRequested =>
            issueRepository.append(
              IssueEvent.MovedToRework(issueId, now, summarize(summary, resolutionKind), now)
            )
          case DecisionResolutionKind.Acknowledged    => ZIO.unit
          case DecisionResolutionKind.Escalated       => ZIO.unit
          case DecisionResolutionKind.Expired         => ZIO.unit
      case _                              => ZIO.unit

  private def openDecisionForIssue(issueId: IssueId): IO[PersistenceError, Option[Decision]] =
    decisionRepository
      .list(
        DecisionFilter(
          statuses = Set(DecisionStatus.Pending),
          sourceKind = Some(DecisionSourceKind.IssueReview),
          issueId = Some(issueId),
          limit = Int.MaxValue,
        )
      )
      .map(_.sortBy(_.createdAt)(Ordering[java.time.Instant].reverse).headOption)

  private def appendResolutionEvent(
    decision: Decision,
    resolutionKind: DecisionResolutionKind,
    actor: String,
    summary: String,
  ): IO[PersistenceError, Decision] =
    for
      now     <- Clock.instant
      _       <- ensureOpen(decision)
      _       <- decisionRepository.append(
                   DecisionEvent.Resolved(
                     decisionId = decision.id,
                     resolution = DecisionResolution(
                       kind = resolutionKind,
                       actor = actor.trim,
                       summary = summarize(summary, resolutionKind),
                       respondedAt = now,
                     ),
                     occurredAt = now,
                   )
                 )
      updated <- decisionRepository.get(decision.id)
      _       <- publishResolutionMetric(updated)
    yield updated

  private def ensureOpen(decision: Decision): IO[PersistenceError, Unit] =
    if decision.isOpen then ZIO.unit
    else
      ZIO.fail(
        PersistenceError.QueryFailed(
          "decision_state",
          s"Decision ${decision.id.value} is already ${decision.status.toString.toLowerCase}",
        )
      )

  private def loadTimeoutSeconds: IO[PersistenceError, Long] =
    configRepository
      .getSetting("decisions.timeoutSeconds.default")
      .mapError(err => PersistenceError.QueryFailed("config_get:decisions.timeoutSeconds.default", err.toString))
      .map(_.flatMap(row => Option(row.value).map(_.trim).filter(_.nonEmpty).flatMap(_.toLongOption)).getOrElse(3600L))

  private def loadAutoMergePolicy: IO[PersistenceError, Boolean] =
    configRepository
      .getSetting("mergePolicy.autoMerge")
      .mapError(err => PersistenceError.QueryFailed("config_get:mergePolicy.autoMerge", err.toString))
      .map(_.flatMap(row => Option(row.value).map(_.trim)).filter(_.nonEmpty))
      .map {
        case Some(value) => value.equalsIgnoreCase("true") || value == "1" || value.equalsIgnoreCase("yes")
        case None        => true
      }

  private def urgencyFor(issue: AgentIssue): DecisionUrgency =
    issue.priority.trim.toLowerCase match
      case "critical" | "blocker" => DecisionUrgency.Critical
      case "high"                 => DecisionUrgency.High
      case "medium"               => DecisionUrgency.Medium
      case _                      => DecisionUrgency.Low

  private def summarize(summary: String, resolutionKind: DecisionResolutionKind): String =
    Option(summary).map(_.trim).filter(_.nonEmpty).getOrElse {
      resolutionKind match
        case DecisionResolutionKind.Approved        => "Approved from decision inbox"
        case DecisionResolutionKind.ReworkRequested => "Changes requested from decision inbox"
        case DecisionResolutionKind.Acknowledged    => "Acknowledged from decision inbox"
        case DecisionResolutionKind.Escalated       => "Escalated from decision inbox"
        case DecisionResolutionKind.Expired         => "Decision expired"
    }

  private def publishResolutionMetric(decision: Decision): UIO[Unit] =
    for
      decisions <- decisionRepository.list(DecisionFilter(limit = Int.MaxValue)).orElseSucceed(Nil)
      resolved   = decisions.flatMap(_.responseTimeMillis)
      averageMs  = if resolved.isEmpty then 0L else resolved.sum / resolved.size
      _         <- activityHub.publish(
                     ActivityEvent(
                       id = EventId.generate,
                       eventType = ActivityEventType.DecisionResolved,
                       source = "decision-inbox",
                       summary = s"Decision ${decision.id.value} resolved in ${decision.responseTimeMillis.getOrElse(0L)}ms",
                       payload = Some(
                         s"""{"decisionId":"${decision.id.value}","responseTimeMs":${decision.responseTimeMillis.getOrElse(
                             0L
                           )},"averageResponseTimeMs":$averageMs,"resolution":${decision.resolution.map(
                             _.kind.toString
                           ).getOrElse("").toJson}}"""
                       ),
                       createdAt = decision.updatedAt,
                     )
                   )
    yield ()

  private def publishEscalation(decision: Decision, reason: String, now: Instant): UIO[Unit] =
    activityHub.publish(
      ActivityEvent(
        id = EventId.generate,
        eventType = ActivityEventType.DecisionEscalated,
        source = "decision-inbox",
        summary = s"Decision ${decision.id.value} escalated",
        payload = Some(
          s"""{"decisionId":"${decision.id.value}","reason":${reason.toJson},"status":${decision.status.toString.toJson}}"""
        ),
        createdAt = now,
      )
    )

  private def sortKey(decision: Decision): (Int, Long, Long) =
    val urgencyRank = decision.urgency match
      case DecisionUrgency.Critical => 0
      case DecisionUrgency.High     => 1
      case DecisionUrgency.Medium   => 2
      case DecisionUrgency.Low      => 3
    val deadline    = decision.deadlineAt.map(_.toEpochMilli).getOrElse(Long.MaxValue)
    (urgencyRank, deadline, -decision.createdAt.toEpochMilli)
