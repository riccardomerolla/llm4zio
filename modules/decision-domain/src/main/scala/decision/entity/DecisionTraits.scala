package decision.entity

import java.time.Instant

import scala.annotation.unused

import zio.*

import issues.entity.AgentIssue
import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, IssueId }

/** Focused trait for creating decisions.
  *
  * Consumers that only need to open new decisions depend on this instead of the full DecisionInbox.
  */
trait DecisionCreator:
  def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision]
  def openManualDecision(
    @unused title: String,
    @unused context: String,
    @unused referenceId: String,
    @unused summary: String,
    @unused urgency: DecisionUrgency = DecisionUrgency.Medium,
    @unused workspaceId: Option[String] = None,
    @unused issueId: Option[IssueId] = None,
  ): IO[PersistenceError, Decision] =
    ZIO.fail(PersistenceError.QueryFailed("decision_manual", "Manual decision creation not implemented"))

/** Focused trait for resolving and querying decisions.
  *
  * Consumers that only need to resolve, query, or list decisions depend on this instead of the full DecisionInbox.
  */
trait DecisionResolver:
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
