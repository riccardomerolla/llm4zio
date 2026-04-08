package shared.web

import issues.entity.IssueWorkReport
import orchestration.entity.PlannerPreviewState
import taskrun.entity.TaskReportRow

final case class ChatDetailContext(
  proofOfWork: Option[IssueWorkReport],
  reports: List[TaskReportRow],
  graphReports: List[TaskReportRow],
  memorySessionId: Option[String],
  plannerState: Option[PlannerPreviewState],
)

object ChatDetailContext:
  val empty: ChatDetailContext = ChatDetailContext(
    proofOfWork = None,
    reports = Nil,
    graphReports = Nil,
    memorySessionId = None,
    plannerState = None,
  )
