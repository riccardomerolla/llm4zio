package board.control

import zio.*

import issues.control.{ IssueWorkReportHydrator, IssueWorkReportSubscriber }
import orchestration.control.WorkReportEventBus

object IssueWorkReportProjectionFactory:

  val live
    : ZLayer[WorkReportEventBus & issues.entity.IssueRepository & taskrun.entity.TaskRunRepository, Nothing, issues.entity.IssueWorkReportProjection] =
    ZLayer.scoped {
      for
        bus         <- ZIO.service[WorkReportEventBus]
        issueRepo   <- ZIO.service[issues.entity.IssueRepository]
        taskRunRepo <- ZIO.service[taskrun.entity.TaskRunRepository]
        projection  <- issues.entity.IssueWorkReportProjection.make
        _           <- IssueWorkReportHydrator.runStartup(projection, issueRepo, taskRunRepo)
        _           <- IssueWorkReportSubscriber(bus, projection, issueRepo).start
      yield projection
    }
