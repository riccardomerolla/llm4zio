package shared.web

import db.TaskReportRow
import issues.entity.IssueWorkReport
import orchestration.control.PlannerPreviewState

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
