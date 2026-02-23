package db

import zio.*

trait TaskRepository:
  // Runs
  def createRun(run: TaskRunRow): IO[PersistenceError, Long]
  def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]
  def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]
  def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]]
  def deleteRun(id: Long): IO[PersistenceError, Unit]

  // Reports and artifacts
  def saveReport(report: TaskReportRow): IO[PersistenceError, Long]
  def getReport(reportId: Long): IO[PersistenceError, Option[TaskReportRow]]
  def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[TaskReportRow]]
  def saveArtifact(artifact: TaskArtifactRow): IO[PersistenceError, Long]
  def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[TaskArtifactRow]]

  // Settings
  def getAllSettings: IO[PersistenceError, List[SettingRow]]
  def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]
  def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]
  def getSettingsByPrefix(prefix: String): IO[PersistenceError, List[SettingRow]] =
    getAllSettings.map(_.filter(_.key.startsWith(prefix)))
  def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]          =
    ZIO.fail(PersistenceError.QueryFailed("deleteSettingsByPrefix", s"Not implemented for prefix: $prefix"))

  // Workflows
  def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]          =
    ZIO.fail(PersistenceError.QueryFailed("createWorkflow", "Not implemented"))
  def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]           =
    ZIO.fail(PersistenceError.QueryFailed("getWorkflow", "Not implemented"))
  def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
    ZIO.fail(PersistenceError.QueryFailed("getWorkflowByName", "Not implemented"))
  def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                     =
    ZIO.fail(PersistenceError.QueryFailed("listWorkflows", "Not implemented"))
  def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]          =
    ZIO.fail(PersistenceError.QueryFailed("updateWorkflow", "Not implemented"))
  def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                       =
    ZIO.fail(PersistenceError.QueryFailed("deleteWorkflow", "Not implemented"))

  // Custom Agents
  def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             =
    ZIO.fail(PersistenceError.QueryFailed("createCustomAgent", "Not implemented"))
  def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           =
    ZIO.fail(PersistenceError.QueryFailed("getCustomAgent", "Not implemented"))
  def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
    ZIO.fail(PersistenceError.QueryFailed("getCustomAgentByName", "Not implemented"))
  def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     =
    ZIO.fail(PersistenceError.QueryFailed("listCustomAgents", "Not implemented"))
  def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             =
    ZIO.fail(PersistenceError.QueryFailed("updateCustomAgent", "Not implemented"))
  def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          =
    ZIO.fail(PersistenceError.QueryFailed("deleteCustomAgent", "Not implemented"))

import shared.store.{ ConfigStoreModule, DataStoreModule }

object TaskRepository:
  def createRun(run: TaskRunRow): ZIO[TaskRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[TaskRepository](_.createRun(run))

  def updateRun(run: TaskRunRow): ZIO[TaskRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TaskRepository](_.updateRun(run))

  def getRun(id: Long): ZIO[TaskRepository, PersistenceError, Option[TaskRunRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getRun(id))

  def listRuns(offset: Int, limit: Int): ZIO[TaskRepository, PersistenceError, List[TaskRunRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.listRuns(offset, limit))

  def deleteRun(id: Long): ZIO[TaskRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TaskRepository](_.deleteRun(id))

  def saveReport(report: TaskReportRow): ZIO[TaskRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[TaskRepository](_.saveReport(report))

  def getReport(reportId: Long): ZIO[TaskRepository, PersistenceError, Option[TaskReportRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getReport(reportId))

  def getReportsByTask(taskRunId: Long): ZIO[TaskRepository, PersistenceError, List[TaskReportRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getReportsByTask(taskRunId))

  def saveArtifact(artifact: TaskArtifactRow): ZIO[TaskRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[TaskRepository](_.saveArtifact(artifact))

  def getArtifactsByTask(taskRunId: Long): ZIO[TaskRepository, PersistenceError, List[TaskArtifactRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getArtifactsByTask(taskRunId))

  def getAllSettings: ZIO[TaskRepository, PersistenceError, List[SettingRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getAllSettings)

  def getSetting(key: String): ZIO[TaskRepository, PersistenceError, Option[SettingRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getSetting(key))

  def upsertSetting(key: String, value: String): ZIO[TaskRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TaskRepository](_.upsertSetting(key, value))

  def getSettingsByPrefix(prefix: String): ZIO[TaskRepository, PersistenceError, List[SettingRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getSettingsByPrefix(prefix))

  def deleteSettingsByPrefix(prefix: String): ZIO[TaskRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TaskRepository](_.deleteSettingsByPrefix(prefix))

  def createWorkflow(workflow: WorkflowRow): ZIO[TaskRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[TaskRepository](_.createWorkflow(workflow))

  def getWorkflow(id: Long): ZIO[TaskRepository, PersistenceError, Option[WorkflowRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getWorkflow(id))

  def getWorkflowByName(name: String): ZIO[TaskRepository, PersistenceError, Option[WorkflowRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getWorkflowByName(name))

  def listWorkflows: ZIO[TaskRepository, PersistenceError, List[WorkflowRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.listWorkflows)

  def updateWorkflow(workflow: WorkflowRow): ZIO[TaskRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TaskRepository](_.updateWorkflow(workflow))

  def deleteWorkflow(id: Long): ZIO[TaskRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TaskRepository](_.deleteWorkflow(id))

  def createCustomAgent(agent: CustomAgentRow): ZIO[TaskRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[TaskRepository](_.createCustomAgent(agent))

  def getCustomAgent(id: Long): ZIO[TaskRepository, PersistenceError, Option[CustomAgentRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getCustomAgent(id))

  def getCustomAgentByName(name: String): ZIO[TaskRepository, PersistenceError, Option[CustomAgentRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.getCustomAgentByName(name))

  def listCustomAgents: ZIO[TaskRepository, PersistenceError, List[CustomAgentRow]] =
    ZIO.serviceWithZIO[TaskRepository](_.listCustomAgents)

  def updateCustomAgent(agent: CustomAgentRow): ZIO[TaskRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TaskRepository](_.updateCustomAgent(agent))

  def deleteCustomAgent(id: Long): ZIO[TaskRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TaskRepository](_.deleteCustomAgent(id))

  val live
    : ZLayer[
      DataStoreModule.DataStoreService & ConfigStoreModule.ConfigStoreService,
      Nothing,
      TaskRepository,
    ] =
    TaskRepositoryLive.live

end TaskRepository
