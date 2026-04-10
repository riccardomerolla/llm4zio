package checkpoint.entity

import java.time.Instant

import shared.errors.{ ControlPlaneError, PersistenceError, StateError }
import workspace.entity.{ GitError, WorkspaceError }

enum CheckpointReviewError:
  case Persistence(message: String)
  case State(message: String)
  case Control(message: String)
  case Workspace(message: String)
  case Git(message: String)
  case NotFound(runId: String)
  case InvalidAction(runId: String, action: String, reason: String)

object CheckpointReviewError:
  def fromPersistence(error: PersistenceError): CheckpointReviewError =
    CheckpointReviewError.Persistence(error.toString)

  def fromState(error: StateError): CheckpointReviewError =
    CheckpointReviewError.State(error.toString)

  def fromControl(error: ControlPlaneError): CheckpointReviewError =
    CheckpointReviewError.Control(error.toString)

  def fromWorkspace(error: WorkspaceError): CheckpointReviewError =
    CheckpointReviewError.Workspace(error.toString)

  def fromGit(error: GitError): CheckpointReviewError =
    CheckpointReviewError.Git(error.toString)

final case class CheckpointRunSummary(
  runId: String,
  agentName: String,
  stage: String,
  currentStepLabel: String,
  conversationId: Option[String],
  workspaceId: Option[String],
  issueId: Option[String],
  checkpointCount: Int,
  lastCheckpointAt: Option[Instant],
  statusMessage: Option[String],
)

final case class CheckpointTextEvidence(
  label: String,
  content: String,
)

final case class CheckpointConversationExcerpt(
  sender: String,
  senderType: String,
  content: String,
  createdAt: Instant,
)

final case class CheckpointArtifactDelta(
  key: String,
  before: Option[String],
  after: Option[String],
)

final case class CheckpointComparison(
  leftStep: String,
  rightStep: String,
  currentStepChanged: Boolean,
  completedStepsAdded: List[String],
  completedStepsRemoved: List[String],
  artifactDeltas: List[CheckpointArtifactDelta],
  errorsAdded: List[String],
  errorsResolved: List[String],
)

enum CheckpointOperatorAction:
  case ApproveContinue
  case Redirect
  case Pause
  case Abort
  case FlagFullReview

final case class CheckpointActionResult(
  action: CheckpointOperatorAction,
  runId: String,
  summary: String,
)
