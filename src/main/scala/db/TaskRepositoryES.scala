package db

import java.time.Instant

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.gigamap.domain.GigaMapQuery
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import store.*

final case class TaskRepositoryES(
  taskRuns: GigaMap[TaskRunId, store.TaskRunRow],
  taskReports: GigaMap[ReportId, store.TaskReportRow],
  taskArtifacts: GigaMap[ArtifactId, store.TaskArtifactRow],
  settings: GigaMap[String, String],
  workflows: GigaMap[WorkflowId, store.WorkflowRow],
  agents: GigaMap[AgentId, store.CustomAgentRow],
) extends TaskRepository:

  private val builtInAgentNamesLower: Set[String] = Set(
    "chat-agent",
    "code-agent",
    "task-planner",
    "web-search-agent",
    "file-agent",
    "report-agent",
    "router-agent",
  )

  override def createRun(run: db.TaskRunRow): IO[PersistenceError, Long] =
    for
      id <- nextId("createRun")
      _  <- taskRuns
              .put(TaskRunId(id.toString), toStoreRunRow(run.copy(id = id)))
              .mapError(storeError("createRun"))
    yield id

  override def updateRun(run: db.TaskRunRow): IO[PersistenceError, Unit] =
    for
      existing <- taskRuns.get(TaskRunId(run.id.toString)).mapError(storeError("updateRun"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("task_runs", run.id))
                    .when(existing.isEmpty)
      _        <- taskRuns.put(TaskRunId(run.id.toString), toStoreRunRow(run)).mapError(storeError("updateRun"))
    yield ()

  override def getRun(id: Long): IO[PersistenceError, Option[db.TaskRunRow]] =
    taskRuns
      .get(TaskRunId(id.toString))
      .map(_.map(fromStoreRunRow))
      .mapError(storeError("getRun"))

  override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[db.TaskRunRow]] =
    queryAll(taskRuns, "listRuns")
      .map(_.toList.map(fromStoreRunRow).sortBy(_.startedAt)(Ordering[Instant].reverse).slice(offset, offset + limit))

  override def deleteRun(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- taskRuns.get(TaskRunId(id.toString)).mapError(storeError("deleteRun"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("task_runs", id))
                    .when(existing.isEmpty)
      _        <- taskRuns.remove(TaskRunId(id.toString)).unit.mapError(storeError("deleteRun"))
    yield ()

  override def saveReport(report: db.TaskReportRow): IO[PersistenceError, Long] =
    for
      id <- nextId("saveReport")
      _  <- taskReports
              .put(ReportId(id.toString), toStoreReportRow(report.copy(id = id)))
              .mapError(storeError("saveReport"))
    yield id

  override def getReport(reportId: Long): IO[PersistenceError, Option[db.TaskReportRow]] =
    taskReports
      .get(ReportId(reportId.toString))
      .map(_.map(fromStoreReportRow))
      .mapError(storeError("getReport"))

  override def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[db.TaskReportRow]] =
    taskReports
      .query(GigaMapQuery.ByIndex("taskRunId", taskRunId.toString))
      .map(_.toList.map(fromStoreReportRow).sortBy(_.createdAt))
      .mapError(storeError("getReportsByTask"))

  override def saveArtifact(artifact: db.TaskArtifactRow): IO[PersistenceError, Long] =
    for
      id <- nextId("saveArtifact")
      _  <- taskArtifacts
              .put(ArtifactId(id.toString), toStoreArtifactRow(artifact.copy(id = id)))
              .mapError(storeError("saveArtifact"))
    yield id

  override def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[db.TaskArtifactRow]] =
    taskArtifacts
      .query(GigaMapQuery.ByIndex("taskRunId", taskRunId.toString))
      .map(_.toList.map(fromStoreArtifactRow).sortBy(_.createdAt))
      .mapError(storeError("getArtifactsByTask"))

  override def getAllSettings: IO[PersistenceError, List[db.SettingRow]] =
    for
      rows <- settings.entries.mapError(storeError("getAllSettings"))
      now  <- Clock.instant
    yield rows.toList.map { case (key, raw) => decodeSetting(key, raw, now) }.sortBy(_.key)

  override def getSetting(key: String): IO[PersistenceError, Option[db.SettingRow]] =
    for
      raw <- settings.get(key).mapError(storeError("getSetting"))
      now <- Clock.instant
    yield raw.map(value => decodeSetting(key, value, now))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- settings
               .put(key, StoredSetting(value, now).toJson)
               .mapError(storeError("upsertSetting"))
    yield ()

  override def getSettingsByPrefix(prefix: String): IO[PersistenceError, List[db.SettingRow]] =
    getAllSettings.map(_.filter(_.key.startsWith(prefix)))

  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    for
      keys <- settings.keys.mapError(storeError("deleteSettingsByPrefix"))
      _    <- ZIO.foreachDiscard(keys.filter(_.startsWith(prefix))) { key =>
                settings.remove(key).unit.mapError(storeError("deleteSettingsByPrefix"))
              }
    yield ()

  override def createWorkflow(workflow: db.WorkflowRow): IO[PersistenceError, Long] =
    for
      id <- nextId("createWorkflow")
      _  <- workflows
              .put(WorkflowId(id.toString), toStoreWorkflowRow(workflow.copy(id = Some(id))))
              .mapError(storeError("createWorkflow"))
    yield id

  override def getWorkflow(id: Long): IO[PersistenceError, Option[db.WorkflowRow]] =
    workflows
      .get(WorkflowId(id.toString))
      .map(_.flatMap(fromStoreWorkflowRow))
      .mapError(storeError("getWorkflow"))

  override def getWorkflowByName(name: String): IO[PersistenceError, Option[db.WorkflowRow]] =
    queryAll(workflows, "getWorkflowByName")
      .map(_.toList.flatMap(fromStoreWorkflowRow).find(_.name.equalsIgnoreCase(name.trim)))

  override def listWorkflows: IO[PersistenceError, List[db.WorkflowRow]] =
    queryAll(workflows, "listWorkflows")
      .map(_.toList.flatMap(fromStoreWorkflowRow).sortBy(w => (!w.isBuiltin, w.name.toLowerCase)))

  override def updateWorkflow(workflow: db.WorkflowRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(workflow.id)
                    .orElseFail(PersistenceError.QueryFailed("updateWorkflow", "Missing id for workflow update"))
      existing <- workflows.get(WorkflowId(id.toString)).mapError(storeError("updateWorkflow"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("workflows", id))
                    .when(existing.isEmpty)
      _        <- workflows
                    .put(WorkflowId(id.toString), toStoreWorkflowRow(workflow))
                    .mapError(storeError("updateWorkflow"))
    yield ()

  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- workflows.get(WorkflowId(id.toString)).mapError(storeError("deleteWorkflow"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("workflows", id))
                    .when(existing.isEmpty)
      _        <- workflows.remove(WorkflowId(id.toString)).unit.mapError(storeError("deleteWorkflow"))
    yield ()

  override def createCustomAgent(agent: db.CustomAgentRow): IO[PersistenceError, Long] =
    for
      _  <- validateCustomAgentName(agent.name, "createCustomAgent")
      id <- nextId("createCustomAgent")
      _  <- agents
              .put(AgentId(id.toString), toStoreAgentRow(agent.copy(id = Some(id))))
              .mapError(storeError("createCustomAgent"))
    yield id

  override def getCustomAgent(id: Long): IO[PersistenceError, Option[db.CustomAgentRow]] =
    agents
      .get(AgentId(id.toString))
      .map(_.flatMap(fromStoreAgentRow))
      .mapError(storeError("getCustomAgent"))

  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[db.CustomAgentRow]] =
    queryAll(agents, "getCustomAgentByName")
      .map(_.toList.flatMap(fromStoreAgentRow).find(_.name.equalsIgnoreCase(name.trim)))

  override def listCustomAgents: IO[PersistenceError, List[db.CustomAgentRow]] =
    queryAll(agents, "listCustomAgents")
      .map(_.toList.flatMap(fromStoreAgentRow).sortBy(agent => (agent.displayName.toLowerCase, agent.name.toLowerCase)))

  override def updateCustomAgent(agent: db.CustomAgentRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(agent.id)
                    .orElseFail(PersistenceError.QueryFailed("updateCustomAgent", "Missing id for custom agent update"))
      _        <- validateCustomAgentName(agent.name, "updateCustomAgent")
      existing <- agents.get(AgentId(id.toString)).mapError(storeError("updateCustomAgent"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("custom_agents", id))
                    .when(existing.isEmpty)
      _        <- agents
                    .put(AgentId(id.toString), toStoreAgentRow(agent))
                    .mapError(storeError("updateCustomAgent"))
    yield ()

  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- agents.get(AgentId(id.toString)).mapError(storeError("deleteCustomAgent"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("custom_agents", id))
                    .when(existing.isEmpty)
      _        <- agents.remove(AgentId(id.toString)).unit.mapError(storeError("deleteCustomAgent"))
    yield ()

  private def validateCustomAgentName(name: String, context: String): IO[PersistenceError, Unit] =
    val normalized = name.trim.toLowerCase
    if normalized.isEmpty then ZIO.fail(PersistenceError.QueryFailed(context, "Custom agent name cannot be empty"))
    else if builtInAgentNamesLower.contains(normalized) then
      ZIO.fail(PersistenceError.QueryFailed(context, s"Custom agent name '$name' conflicts with built-in agent name"))
    else ZIO.unit

  private def toStoreRunRow(run: db.TaskRunRow): store.TaskRunRow =
    store.TaskRunRow(
      id = run.id.toString,
      sourceDir = run.sourceDir,
      outputDir = run.outputDir,
      status = run.status.toString,
      workflowId = run.workflowId.map(_.toString),
      currentPhase = run.currentPhase,
      errorMessage = run.errorMessage,
      startedAt = run.startedAt,
      completedAt = run.completedAt,
      totalFiles = run.totalFiles,
      processedFiles = run.processedFiles,
      successfulConversions = run.successfulConversions,
      failedConversions = run.failedConversions,
    )

  private def fromStoreRunRow(row: store.TaskRunRow): db.TaskRunRow =
    val parsed = RunStatus.values.find(_.toString == row.status).getOrElse(RunStatus.Failed)
    db.TaskRunRow(
      id = row.id.toLongOption.getOrElse(0L),
      sourceDir = row.sourceDir,
      outputDir = row.outputDir,
      status = parsed,
      startedAt = row.startedAt,
      completedAt = row.completedAt,
      totalFiles = row.totalFiles,
      processedFiles = row.processedFiles,
      successfulConversions = row.successfulConversions,
      failedConversions = row.failedConversions,
      currentPhase = row.currentPhase,
      errorMessage = row.errorMessage,
      workflowId = row.workflowId.flatMap(_.toLongOption),
    )

  private def toStoreReportRow(report: db.TaskReportRow): store.TaskReportRow =
    store.TaskReportRow(
      id = report.id.toString,
      taskRunId = report.taskRunId.toString,
      stepName = report.stepName,
      reportType = report.reportType,
      content = report.content,
      createdAt = report.createdAt,
    )

  private def fromStoreReportRow(report: store.TaskReportRow): db.TaskReportRow =
    db.TaskReportRow(
      id = report.id.toLongOption.getOrElse(0L),
      taskRunId = report.taskRunId.toLongOption.getOrElse(0L),
      stepName = report.stepName,
      reportType = report.reportType,
      content = report.content,
      createdAt = report.createdAt,
    )

  private def toStoreArtifactRow(artifact: db.TaskArtifactRow): store.TaskArtifactRow =
    store.TaskArtifactRow(
      id = artifact.id.toString,
      taskRunId = artifact.taskRunId.toString,
      stepName = artifact.stepName,
      key = artifact.key,
      value = artifact.value,
      createdAt = artifact.createdAt,
    )

  private def fromStoreArtifactRow(artifact: store.TaskArtifactRow): db.TaskArtifactRow =
    db.TaskArtifactRow(
      id = artifact.id.toLongOption.getOrElse(0L),
      taskRunId = artifact.taskRunId.toLongOption.getOrElse(0L),
      stepName = artifact.stepName,
      key = artifact.key,
      value = artifact.value,
      createdAt = artifact.createdAt,
    )

  private def toStoreWorkflowRow(workflow: db.WorkflowRow): store.WorkflowRow =
    store.WorkflowRow(
      id = workflow.id.getOrElse(0L).toString,
      name = workflow.name,
      description = workflow.description,
      stepsJson = workflow.steps,
      isBuiltin = workflow.isBuiltin,
      createdAt = workflow.createdAt,
      updatedAt = workflow.updatedAt,
    )

  private def fromStoreWorkflowRow(workflow: store.WorkflowRow): Option[db.WorkflowRow] =
    workflow.id.toLongOption.map { parsedId =>
      db.WorkflowRow(
        id = Some(parsedId),
        name = workflow.name,
        description = workflow.description,
        steps = workflow.stepsJson,
        isBuiltin = workflow.isBuiltin,
        createdAt = workflow.createdAt,
        updatedAt = workflow.updatedAt,
      )
    }

  private def toStoreAgentRow(agent: db.CustomAgentRow): store.CustomAgentRow =
    store.CustomAgentRow(
      id = agent.id.getOrElse(0L).toString,
      name = agent.name,
      displayName = agent.displayName,
      description = agent.description,
      systemPrompt = agent.systemPrompt,
      tagsJson = agent.tags,
      enabled = agent.enabled,
      createdAt = agent.createdAt,
      updatedAt = agent.updatedAt,
    )

  private def fromStoreAgentRow(agent: store.CustomAgentRow): Option[db.CustomAgentRow] =
    agent.id.toLongOption.map { parsedId =>
      db.CustomAgentRow(
        id = Some(parsedId),
        name = agent.name,
        displayName = agent.displayName,
        description = agent.description,
        systemPrompt = agent.systemPrompt,
        tags = agent.tagsJson,
        enabled = agent.enabled,
        createdAt = agent.createdAt,
        updatedAt = agent.updatedAt,
      )
    }

  private def queryAll[K, V](map: GigaMap[K, V], op: String): IO[PersistenceError, Chunk[V]] =
    map.query(GigaMapQuery.All()).mapError(storeError(op))

  private def nextId(op: String): IO[PersistenceError, Long] =
    ZIO
      .attempt(java.util.UUID.randomUUID().getMostSignificantBits & Long.MaxValue)
      .mapError(storeError(op))
      .flatMap(id => if id == 0L then nextId(op) else ZIO.succeed(id))

  private def storeError(op: String)(throwable: Throwable): PersistenceError =
    PersistenceError.QueryFailed(op, Option(throwable.getMessage).getOrElse(throwable.toString))

  final private case class StoredSetting(value: String, updatedAt: Instant) derives JsonCodec

  private def decodeSetting(key: String, raw: String, fallbackUpdatedAt: Instant): db.SettingRow =
    raw.fromJson[StoredSetting] match
      case Right(stored) => db.SettingRow(key = key, value = stored.value, updatedAt = stored.updatedAt)
      case Left(_)       => db.SettingRow(key = key, value = raw, updatedAt = fallbackUpdatedAt)

object TaskRepositoryES:
  val live
    : ZLayer[
      DataStoreModule.TaskRunsStore & DataStoreModule.TaskReportsStore & DataStoreModule.TaskArtifactsStore &
        ConfigStoreModule.SettingsStore & ConfigStoreModule.WorkflowsStore & ConfigStoreModule.CustomAgentsStore,
      Nothing,
      TaskRepository,
    ] =
    ZLayer.fromZIO {
      for
        taskRuns      <- DataStoreModule.taskRunsMap
        taskReports   <- ZIO.serviceWith[DataStoreModule.TaskReportsStore](_.map)
        taskArtifacts <- ZIO.serviceWith[DataStoreModule.TaskArtifactsStore](_.map)
        settings      <- ConfigStoreModule.settingsMap
        workflows     <- ConfigStoreModule.workflowsMap
        agents        <- ConfigStoreModule.customAgentsMap
      yield TaskRepositoryES(taskRuns, taskReports, taskArtifacts, settings, workflows, agents)
    }
