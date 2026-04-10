package issues.control

import java.time.Instant

import zio.*

import analysis.entity.{ AnalysisDoc, AnalysisRepository, AnalysisType }
import issues.entity.{ AgentIssue, IssueEvent }
import shared.errors.PersistenceError

object IssueAnalysisAttachment:

  private val trackedTypes: List[AnalysisType] = List(
    AnalysisType.CodeReview,
    AnalysisType.Architecture,
    AnalysisType.Security,
  )

  def latestForHumanReview(
    issue: AgentIssue,
    analysisRepository: AnalysisRepository,
    now: Instant,
  ): IO[PersistenceError, Option[IssueEvent.AnalysisAttached]] =
    issue.workspaceId match
      case Some(workspaceId) =>
        analysisRepository
          .listByWorkspace(workspaceId)
          .map(docs => buildAttachmentEvent(issue, docs, now))
      case None              =>
        ZIO.succeed(None)

  private[issues] def buildAttachmentEvent(
    issue: AgentIssue,
    docs: List[AnalysisDoc],
    now: Instant,
  ): Option[IssueEvent.AnalysisAttached] =
    val latestDocIds = trackedTypes.flatMap(analysisType =>
      docs
        .filter(_.analysisType == analysisType)
        .sortBy(doc => (doc.updatedAt, doc.createdAt))
        .lastOption
        .map(_.id)
    )
    Option.when(latestDocIds.nonEmpty) {
      IssueEvent.AnalysisAttached(
        issueId = issue.id,
        analysisDocIds = latestDocIds.distinct,
        attachedAt = now,
        occurredAt = now,
      )
    }
