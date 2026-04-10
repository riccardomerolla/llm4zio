package analysis.entity

import java.time.Instant

enum WorkspaceAnalysisState:
  case Idle
  case Pending
  case Running
  case Completed
  case Failed

final case class WorkspaceAnalysisStatus(
  workspaceId: String,
  analysisType: AnalysisType,
  state: WorkspaceAnalysisState,
  queuedAt: Option[Instant] = None,
  startedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  lastUpdatedAt: Instant,
)
