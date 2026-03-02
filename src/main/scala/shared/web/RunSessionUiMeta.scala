package shared.web

import workspace.entity.RunStatus

final case class RunChainItem(
  runId: String,
  conversationId: String,
)

final case class RunSessionUiMeta(
  runId: String,
  workspaceId: String,
  status: RunStatus,
  attachedUsersCount: Int,
  parent: Option[RunChainItem],
  next: Option[RunChainItem],
  breadcrumb: List[RunChainItem],
)
