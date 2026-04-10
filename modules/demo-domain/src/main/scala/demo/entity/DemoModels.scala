package demo.entity

import zio.json.*

enum DemoError:
  case SetupFailed(message: String)
  case CleanupFailed(message: String)
  case ProjectCreationFailed(cause: String)
  case WorkspaceCreationFailed(cause: String)
  case IssueSeedingFailed(cause: String)
  case DispatchFailed(cause: String)

object DemoError:
  given JsonCodec[DemoError] = DeriveJsonCodec.gen[DemoError]

case class DemoResult(
  projectId: String,
  workspaceId: String,
  workspacePath: String,
  issueCount: Int,
  estimatedSeconds: Int,
) derives JsonCodec

case class DemoStatus(
  projectId: String,
  workspaceId: String,
  workspacePath: String,
  dispatched: Int,
  inProgress: Int,
  atReview: Int,
  done: Int,
  total: Int,
) derives JsonCodec
