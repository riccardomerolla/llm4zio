package board.entity

import zio.*

import shared.ids.Ids.BoardIssueId

/** Result of a dispatch cycle. */
final case class DispatchResult(
  dispatchedIssueIds: List[BoardIssueId],
  skippedIssueIds: List[BoardIssueId],
)

/** Focused trait for dispatching and assigning issues to agents.
  *
  * Consumers that only need to trigger dispatch cycles or assign issues depend on this instead of the full
  * BoardOrchestrator.
  */
trait IssueDispatcher:
  def dispatchCycle(workspacePath: String): IO[BoardError, DispatchResult]
  def assignIssue(workspacePath: String, issueId: BoardIssueId, agentName: String): IO[BoardError, Unit]
  def markIssueStarted(workspacePath: String, issueId: BoardIssueId, agentName: String, branchName: String)
    : IO[BoardError, Unit]

/** Focused trait for completing and approving issues.
  *
  * Consumers that only need to mark issues complete or approve them depend on this instead of the full
  * BoardOrchestrator.
  */
trait IssueApprover:
  def completeIssue(workspacePath: String, issueId: BoardIssueId, success: Boolean, details: String)
    : IO[BoardError, Unit]
  def approveIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit]
