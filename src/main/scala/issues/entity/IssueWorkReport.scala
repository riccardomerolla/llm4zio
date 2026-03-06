package issues.entity

import java.time.Instant

import zio.*

import orchestration.entity.DiffStats
import shared.ids.Ids.IssueId
import taskrun.entity.{ CiStatus, PrStatus, TaskArtifact, TaskReport, TokenUsage }

final case class IssueWorkReport(
  issueId: IssueId,
  walkthrough: Option[String],
  agentSummary: Option[String],
  diffStats: Option[DiffStats],
  prLink: Option[String],
  prStatus: Option[PrStatus],
  ciStatus: Option[CiStatus],
  tokenUsage: Option[TokenUsage],
  runtimeSeconds: Option[Long],
  reports: List[TaskReport],
  artifacts: List[TaskArtifact],
  lastUpdated: Instant,
)

object IssueWorkReport:
  def empty(issueId: IssueId, at: Instant): IssueWorkReport =
    IssueWorkReport(
      issueId = issueId,
      walkthrough = None,
      agentSummary = None,
      diffStats = None,
      prLink = None,
      prStatus = None,
      ciStatus = None,
      tokenUsage = None,
      runtimeSeconds = None,
      reports = Nil,
      artifacts = Nil,
      lastUpdated = at,
    )

trait IssueWorkReportProjection:
  def get(issueId: IssueId): UIO[Option[IssueWorkReport]]
  def getAll: UIO[Map[IssueId, IssueWorkReport]]
  def updateWalkthrough(issueId: IssueId, summary: String, at: Instant): UIO[Unit]
  def updateAgentSummary(issueId: IssueId, summary: String, at: Instant): UIO[Unit]
  def updateDiffStats(issueId: IssueId, stats: DiffStats, at: Instant): UIO[Unit]
  def updatePrLink(issueId: IssueId, prUrl: String, status: PrStatus, at: Instant): UIO[Unit]
  def updateCiStatus(issueId: IssueId, status: CiStatus, at: Instant): UIO[Unit]
  def updateTokenUsage(issueId: IssueId, usage: TokenUsage, runtimeSeconds: Long, at: Instant): UIO[Unit]
  def addReport(issueId: IssueId, report: TaskReport, at: Instant): UIO[Unit]
  def addArtifact(issueId: IssueId, artifact: TaskArtifact, at: Instant): UIO[Unit]

object IssueWorkReportProjection:

  def make: UIO[IssueWorkReportProjection] =
    Ref.make(Map.empty[IssueId, IssueWorkReport]).map(Live(_))

  val layer: ULayer[IssueWorkReportProjection] =
    ZLayer.fromZIO(make)

  final private class Live(state: Ref[Map[IssueId, IssueWorkReport]]) extends IssueWorkReportProjection:

    override def get(issueId: IssueId): UIO[Option[IssueWorkReport]] =
      state.get.map(_.get(issueId))

    override def getAll: UIO[Map[IssueId, IssueWorkReport]] =
      state.get

    private def upsert(issueId: IssueId, at: Instant)(f: IssueWorkReport => IssueWorkReport): UIO[Unit] =
      state.update { map =>
        val current = map.getOrElse(issueId, IssueWorkReport.empty(issueId, at))
        map.updated(issueId, f(current).copy(lastUpdated = at))
      }

    override def updateWalkthrough(issueId: IssueId, summary: String, at: Instant): UIO[Unit] =
      upsert(issueId, at)(_.copy(walkthrough = Some(summary)))

    override def updateAgentSummary(issueId: IssueId, summary: String, at: Instant): UIO[Unit] =
      upsert(issueId, at)(_.copy(agentSummary = Some(summary)))

    override def updateDiffStats(issueId: IssueId, stats: DiffStats, at: Instant): UIO[Unit] =
      upsert(issueId, at)(_.copy(diffStats = Some(stats)))

    override def updatePrLink(issueId: IssueId, prUrl: String, status: PrStatus, at: Instant): UIO[Unit] =
      upsert(issueId, at)(_.copy(prLink = Some(prUrl), prStatus = Some(status)))

    override def updateCiStatus(issueId: IssueId, status: CiStatus, at: Instant): UIO[Unit] =
      upsert(issueId, at)(_.copy(ciStatus = Some(status)))

    override def updateTokenUsage(issueId: IssueId, usage: TokenUsage, runtimeSeconds: Long, at: Instant): UIO[Unit] =
      upsert(issueId, at)(_.copy(tokenUsage = Some(usage), runtimeSeconds = Some(runtimeSeconds)))

    override def addReport(issueId: IssueId, report: TaskReport, at: Instant): UIO[Unit] =
      upsert(issueId, at)(r => r.copy(reports = r.reports :+ report))

    override def addArtifact(issueId: IssueId, artifact: TaskArtifact, at: Instant): UIO[Unit] =
      upsert(issueId, at)(r => r.copy(artifacts = r.artifacts :+ artifact))
