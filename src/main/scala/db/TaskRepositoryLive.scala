package db

import java.time.Instant

import zio.*
import zio.schema.Schema

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.service.{ LifecycleCommand, LifecycleStatus }
import shared.store.*

final case class TaskRepositoryLive(
  dataStore: DataStoreModule.DataStoreService,
  configStore: ConfigStoreModule.ConfigStoreService,
) extends TaskRepository:

  private val dkv = dataStore.store
  private val ckv = configStore.store

  private val builtInAgentNamesLower: Set[String] = Set(
    "chat-agent",
    "code-agent",
    "task-planner",
    "web-search-agent",
    "file-agent",
    "report-agent",
    "router-agent",
  )

  // Key helpers — data store
  private def runKey(id: Long): String      = s"run:$id"
  private def reportKey(id: Long): String   = s"report:$id"
  private def artifactKey(id: Long): String = s"artifact:$id"

  // Key helpers — config store
  private def settingKey(key: String): String = s"setting:$key"
  private def workflowKey(id: Long): String   = s"workflow:$id"
  private def agentKey(id: Long): String      = s"agent:$id"

  override def createRun(run: db.TaskRunRow): IO[PersistenceError, Long] =
    for
      id <- nextId("createRun")
      _  <- dkv
              .store(runKey(id), toStoreRunRow(run.copy(id = id)))
              .mapError(storeErr("createRun"))
    yield id

  override def updateRun(run: db.TaskRunRow): IO[PersistenceError, Unit] =
    for
      existing <- dkv.fetch[String, shared.store.TaskRunRow](runKey(run.id)).mapError(storeErr("updateRun"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("task_runs", run.id))
                    .when(existing.isEmpty)
      _        <- dkv.store(runKey(run.id), toStoreRunRow(run)).mapError(storeErr("updateRun"))
    yield ()

  override def getRun(id: Long): IO[PersistenceError, Option[db.TaskRunRow]] =
    dkv
      .fetch[String, shared.store.TaskRunRow](runKey(id))
      .map(_.flatMap(fromStoreRunRow))
      .mapError(storeErr("getRun"))

  override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[db.TaskRunRow]] =
    fetchAllDataByPrefix[shared.store.TaskRunRow]("run:", "listRuns")
      .map(_.flatMap(fromStoreRunRow).sortBy(_.startedAt)(Ordering[Instant].reverse).slice(offset, offset + limit))

  override def deleteRun(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- dkv.fetch[String, shared.store.TaskRunRow](runKey(id)).mapError(storeErr("deleteRun"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("task_runs", id))
                    .when(existing.isEmpty)
      _        <- dkv.remove[String](runKey(id)).mapError(storeErr("deleteRun"))
    yield ()

  override def saveReport(report: db.TaskReportRow): IO[PersistenceError, Long] =
    for
      id <- nextId("saveReport")
      _  <- dkv
              .store(reportKey(id), toStoreReportRow(report.copy(id = id)))
              .mapError(storeErr("saveReport"))
    yield id

  override def getReport(reportId: Long): IO[PersistenceError, Option[db.TaskReportRow]] =
    dkv
      .fetch[String, shared.store.TaskReportRow](reportKey(reportId))
      .map(_.flatMap(fromStoreReportRow))
      .mapError(storeErr("getReport"))

  override def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[db.TaskReportRow]] =
    fetchAllDataByPrefix[shared.store.TaskReportRow]("report:", "getReportsByTask")
      .map(_.filter(_.taskRunId == taskRunId.toString).flatMap(fromStoreReportRow).sortBy(_.createdAt))

  override def saveArtifact(artifact: db.TaskArtifactRow): IO[PersistenceError, Long] =
    for
      id <- nextId("saveArtifact")
      _  <- dkv
              .store(artifactKey(id), toStoreArtifactRow(artifact.copy(id = id)))
              .mapError(storeErr("saveArtifact"))
    yield id

  override def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[db.TaskArtifactRow]] =
    fetchAllDataByPrefix[shared.store.TaskArtifactRow]("artifact:", "getArtifactsByTask")
      .map(_.filter(_.taskRunId == taskRunId.toString).flatMap(fromStoreArtifactRow).sortBy(_.createdAt))

  override def getAllSettings: IO[PersistenceError, List[db.SettingRow]] =
    for
      keys <- configStore.rawStore
                .streamKeys[String]
                .filter(_.startsWith("setting:"))
                .runCollect
                .mapError(storeErr("getAllSettings"))
      rows <- ZIO.foreach(keys.toList) { k =>
                ckv
                  .fetch[String, String](k)
                  .mapError(storeErr("getAllSettings"))
                  .map(_.map(raw => decodeSetting(k.stripPrefix("setting:"), raw)).toList)
              }
    yield rows.flatten.sortBy(_.key)

  override def getSetting(key: String): IO[PersistenceError, Option[db.SettingRow]] =
    ckv.fetch[String, String](settingKey(key)).mapError(storeErr("getSetting")).map(_.map(value =>
      decodeSetting(key, value)
    ))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    ckv.store(settingKey(key), value).mapError(storeErr("upsertSetting")) *> checkpointConfigStore("upsertSetting")

  override def getSettingsByPrefix(prefix: String): IO[PersistenceError, List[db.SettingRow]] =
    getAllSettings.map(_.filter(_.key.startsWith(prefix)))

  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    for
      keys <- configStore.rawStore
                .streamKeys[String]
                .filter(k => k.startsWith(s"setting:$prefix"))
                .runCollect
                .mapError(storeErr("deleteSettingsByPrefix"))
      _    <- ZIO.foreachDiscard(keys.toList) { k =>
                ckv.remove[String](k).mapError(storeErr("deleteSettingsByPrefix"))
              }
      _    <- checkpointConfigStore("deleteSettingsByPrefix")
    yield ()

  override def createWorkflow(workflow: db.WorkflowRow): IO[PersistenceError, Long] =
    for
      id <- nextId("createWorkflow")
      _  <- ckv
              .store(workflowKey(id), toStoreWorkflowRow(workflow, id))
              .mapError(storeErr("createWorkflow"))
    yield id

  override def getWorkflow(id: Long): IO[PersistenceError, Option[db.WorkflowRow]] =
    ckv
      .fetch[String, shared.store.WorkflowRow](workflowKey(id))
      .map(_.flatMap(fromStoreWorkflowRow))
      .mapError(storeErr("getWorkflow"))

  override def getWorkflowByName(name: String): IO[PersistenceError, Option[db.WorkflowRow]] =
    fetchAllConfigByPrefix[shared.store.WorkflowRow]("workflow:", "getWorkflowByName")
      .map(_.flatMap(fromStoreWorkflowRow).find(_.name.equalsIgnoreCase(name.trim)))

  override def listWorkflows: IO[PersistenceError, List[db.WorkflowRow]] =
    fetchAllConfigByPrefix[shared.store.WorkflowRow]("workflow:", "listWorkflows")
      .map(_.flatMap(fromStoreWorkflowRow).sortBy(w => (!w.isBuiltin, w.name.toLowerCase)))

  override def updateWorkflow(workflow: db.WorkflowRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(workflow.id)
                    .orElseFail(PersistenceError.QueryFailed("updateWorkflow", "Missing id for workflow update"))
      existing <- ckv.fetch[String, shared.store.WorkflowRow](workflowKey(id)).mapError(storeErr("updateWorkflow"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("workflows", id))
                    .when(existing.isEmpty)
      _        <- ckv
                    .store(workflowKey(id), toStoreWorkflowRow(workflow, id))
                    .mapError(storeErr("updateWorkflow"))
    yield ()

  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- ckv.fetch[String, shared.store.WorkflowRow](workflowKey(id)).mapError(storeErr("deleteWorkflow"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("workflows", id))
                    .when(existing.isEmpty)
      _        <- ckv.remove[String](workflowKey(id)).mapError(storeErr("deleteWorkflow"))
    yield ()

  override def createCustomAgent(agent: db.CustomAgentRow): IO[PersistenceError, Long] =
    for
      _  <- validateCustomAgentName(agent.name, "createCustomAgent")
      id <- nextId("createCustomAgent")
      _  <- ckv
              .store(agentKey(id), toStoreAgentRow(agent, id))
              .mapError(storeErr("createCustomAgent"))
    yield id

  override def getCustomAgent(id: Long): IO[PersistenceError, Option[db.CustomAgentRow]] =
    ckv
      .fetch[String, shared.store.CustomAgentRow](agentKey(id))
      .map(_.flatMap(fromStoreAgentRow))
      .mapError(storeErr("getCustomAgent"))

  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[db.CustomAgentRow]] =
    fetchAllConfigByPrefix[shared.store.CustomAgentRow]("agent:", "getCustomAgentByName")
      .map(_.flatMap(fromStoreAgentRow).find(_.name.equalsIgnoreCase(name.trim)))

  override def listCustomAgents: IO[PersistenceError, List[db.CustomAgentRow]] =
    fetchAllConfigByPrefix[shared.store.CustomAgentRow]("agent:", "listCustomAgents")
      .map(_.flatMap(fromStoreAgentRow).sortBy(agent => (agent.displayName.toLowerCase, agent.name.toLowerCase)))

  override def updateCustomAgent(agent: db.CustomAgentRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(agent.id)
                    .orElseFail(PersistenceError.QueryFailed("updateCustomAgent", "Missing id for custom agent update"))
      _        <- validateCustomAgentName(agent.name, "updateCustomAgent")
      existing <- ckv.fetch[String, shared.store.CustomAgentRow](agentKey(id)).mapError(storeErr("updateCustomAgent"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("custom_agents", id))
                    .when(existing.isEmpty)
      _        <- ckv
                    .store(agentKey(id), toStoreAgentRow(agent, id))
                    .mapError(storeErr("updateCustomAgent"))
    yield ()

  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- ckv.fetch[String, shared.store.CustomAgentRow](agentKey(id)).mapError(storeErr("deleteCustomAgent"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("custom_agents", id))
                    .when(existing.isEmpty)
      _        <- ckv.remove[String](agentKey(id)).mapError(storeErr("deleteCustomAgent"))
    yield ()

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private def fetchAllDataByPrefix[V](prefix: String, op: String)(using Schema[V]): IO[PersistenceError, List[V]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .flatMap(keys => ZIO.foreach(keys.toList)(k => dkv.fetch[String, V](k).mapError(storeErr(op))).map(_.flatten))

  private def fetchAllConfigByPrefix[V](prefix: String, op: String)(using Schema[V]): IO[PersistenceError, List[V]] =
    configStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .flatMap(keys => ZIO.foreach(keys.toList)(k => ckv.fetch[String, V](k).mapError(storeErr(op))).map(_.flatten))

  private def validateCustomAgentName(name: String, context: String): IO[PersistenceError, Unit] =
    val normalized = name.trim.toLowerCase
    if normalized.isEmpty then ZIO.fail(PersistenceError.QueryFailed(context, "Custom agent name cannot be empty"))
    else if builtInAgentNamesLower.contains(normalized) then
      ZIO.fail(PersistenceError.QueryFailed(context, s"Custom agent name '$name' conflicts with built-in agent name"))
    else ZIO.unit

  private def nextId(op: String): IO[PersistenceError, Long] =
    ZIO
      .attempt(java.util.UUID.randomUUID().getMostSignificantBits & Long.MaxValue)
      .mapError(storeErrThrowable(op))
      .flatMap(id => if id == 0L then nextId(op) else ZIO.succeed(id))

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def storeErrThrowable(op: String)(t: Throwable): PersistenceError =
    PersistenceError.QueryFailed(op, Option(t.getMessage).getOrElse(t.toString))

  private def decodeSetting(key: String, raw: String): db.SettingRow =
    db.SettingRow(key = key, value = raw, updatedAt = Instant.EPOCH)

  private def checkpointConfigStore(op: String): IO[PersistenceError, Unit] =
    for
      status <- configStore.rawStore
                  .maintenance(LifecycleCommand.Checkpoint)
                  .mapError(err => PersistenceError.QueryFailed(op, err.toString))
      _      <- status match
                  case LifecycleStatus.Failed(message) =>
                    ZIO.fail(PersistenceError.QueryFailed(op, s"Config store checkpoint failed: $message"))
                  case _                               => ZIO.unit
    yield ()

  private def toStoreRunRow(run: db.TaskRunRow): shared.store.TaskRunRow =
    shared.store.TaskRunRow(
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

  private def fromStoreRunRow(row: shared.store.TaskRunRow): Option[db.TaskRunRow] =
    for
      parsedStatus <- RunStatus.values.find(_.toString == row.status)
      parsedId     <- row.id.toLongOption
    yield db.TaskRunRow(
      id = parsedId,
      sourceDir = row.sourceDir,
      outputDir = row.outputDir,
      status = parsedStatus,
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

  private def toStoreReportRow(report: db.TaskReportRow): shared.store.TaskReportRow =
    shared.store.TaskReportRow(
      id = report.id.toString,
      taskRunId = report.taskRunId.toString,
      stepName = report.stepName,
      reportType = report.reportType,
      content = report.content,
      createdAt = report.createdAt,
    )

  private def fromStoreReportRow(report: shared.store.TaskReportRow): Option[db.TaskReportRow] =
    for
      reportId <- report.id.toLongOption
      runId    <- report.taskRunId.toLongOption
    yield db.TaskReportRow(
      id = reportId,
      taskRunId = runId,
      stepName = report.stepName,
      reportType = report.reportType,
      content = report.content,
      createdAt = report.createdAt,
    )

  private def toStoreArtifactRow(artifact: db.TaskArtifactRow): shared.store.TaskArtifactRow =
    shared.store.TaskArtifactRow(
      id = artifact.id.toString,
      taskRunId = artifact.taskRunId.toString,
      stepName = artifact.stepName,
      key = artifact.key,
      value = artifact.value,
      createdAt = artifact.createdAt,
    )

  private def fromStoreArtifactRow(artifact: shared.store.TaskArtifactRow): Option[db.TaskArtifactRow] =
    for
      artifactId <- artifact.id.toLongOption
      runId      <- artifact.taskRunId.toLongOption
    yield db.TaskArtifactRow(
      id = artifactId,
      taskRunId = runId,
      stepName = artifact.stepName,
      key = artifact.key,
      value = artifact.value,
      createdAt = artifact.createdAt,
    )

  private def toStoreWorkflowRow(workflow: db.WorkflowRow, id: Long): shared.store.WorkflowRow =
    shared.store.WorkflowRow(
      id = id.toString,
      name = workflow.name,
      description = workflow.description,
      stepsJson = workflow.steps,
      isBuiltin = workflow.isBuiltin,
      createdAt = workflow.createdAt,
      updatedAt = workflow.updatedAt,
    )

  private def fromStoreWorkflowRow(workflow: shared.store.WorkflowRow): Option[db.WorkflowRow] =
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

  private def toStoreAgentRow(agent: db.CustomAgentRow, id: Long): shared.store.CustomAgentRow =
    shared.store.CustomAgentRow(
      id = id.toString,
      name = agent.name,
      displayName = agent.displayName,
      description = agent.description,
      systemPrompt = agent.systemPrompt,
      tagsJson = agent.tags,
      enabled = agent.enabled,
      createdAt = agent.createdAt,
      updatedAt = agent.updatedAt,
    )

  private def fromStoreAgentRow(agent: shared.store.CustomAgentRow): Option[db.CustomAgentRow] =
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

object TaskRepositoryLive:
  val live
    : ZLayer[
      DataStoreModule.DataStoreService & ConfigStoreModule.ConfigStoreService,
      Nothing,
      TaskRepository,
    ] =
    ZLayer.fromZIO {
      for
        dataStore   <- ZIO.service[DataStoreModule.DataStoreService]
        configStore <- ZIO.service[ConfigStoreModule.ConfigStoreService]
      yield TaskRepositoryLive(dataStore, configStore)
    }
