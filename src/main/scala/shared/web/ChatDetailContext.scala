package shared.web

import db.TaskReportRow
import issues.entity.IssueWorkReport

final case class ChatDetailContext(
  proofOfWork: Option[IssueWorkReport],
  reports: List[TaskReportRow],
  graphReports: List[TaskReportRow],
  memorySessionId: Option[String],
)

object ChatDetailContext:
  val empty: ChatDetailContext = ChatDetailContext(
    proofOfWork = None,
    reports = Nil,
    graphReports = Nil,
    memorySessionId = None,
  )
