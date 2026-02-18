package db

import java.sql.{ Connection, ResultSet, Statement, Types }
import java.time.Instant
import javax.sql.DataSource

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

  val live: ZLayer[DataSource, Nothing, TaskRepository] =
    ZLayer.fromZIO {
      for
        dataSource  <- ZIO.service[DataSource]
        initialized <- Ref.Synchronized.make(false)
      yield TaskRepositoryLive(dataSource, initialized)
    }

final case class TaskRepositoryLive(
  dataSource: DataSource,
  initialized: Ref.Synchronized[Boolean],
) extends TaskRepository:
  private val builtInAgentNamesLower: Set[String] = Set(
    "coboldiscovery",
    "cobolanalyzer",
    "businesslogicextractor",
    "dependencymapper",
    "javatransformer",
    "validationagent",
    "documentationagent",
  )

  override def createRun(run: TaskRunRow): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO task_runs (
        |  source_dir, output_dir, status, started_at, completed_at,
        |  total_files, processed_files, successful_conversions, failed_conversions,
        |  current_phase, error_message, workflow_id
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "task_runs") { stmt =>
        stmt.setString(1, run.sourceDir)
        stmt.setString(2, run.outputDir)
        stmt.setString(3, run.status.toString)
        stmt.setString(4, run.startedAt.toString)
        setOptionalString(stmt, 5, run.completedAt.map(_.toString))
        stmt.setInt(6, run.totalFiles)
        stmt.setInt(7, run.processedFiles)
        stmt.setInt(8, run.successfulConversions)
        stmt.setInt(9, run.failedConversions)
        setOptionalString(stmt, 10, run.currentPhase)
        setOptionalString(stmt, 11, run.errorMessage)
        setOptionalLong(stmt, 12, run.workflowId)
      }
    }

  override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit] =
    val sql =
      """UPDATE task_runs
        |SET source_dir = ?,
        |    output_dir = ?,
        |    status = ?,
        |    started_at = ?,
        |    completed_at = ?,
        |    total_files = ?,
        |    processed_files = ?,
        |    successful_conversions = ?,
        |    failed_conversions = ?,
        |    current_phase = ?,
        |    error_message = ?,
        |    workflow_id = ?
        |WHERE id = ?
        |""".stripMargin

    withConnection { conn =>
      executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("task_runs", run.id)) { stmt =>
        stmt.setString(1, run.sourceDir)
        stmt.setString(2, run.outputDir)
        stmt.setString(3, run.status.toString)
        stmt.setString(4, run.startedAt.toString)
        setOptionalString(stmt, 5, run.completedAt.map(_.toString))
        stmt.setInt(6, run.totalFiles)
        stmt.setInt(7, run.processedFiles)
        stmt.setInt(8, run.successfulConversions)
        stmt.setInt(9, run.failedConversions)
        setOptionalString(stmt, 10, run.currentPhase)
        setOptionalString(stmt, 11, run.errorMessage)
        setOptionalLong(stmt, 12, run.workflowId)
        stmt.setLong(13, run.id)
      }
    }

  override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]] =
    val sql =
      """SELECT id, source_dir, output_dir, status, started_at, completed_at,
        |       total_files, processed_files, successful_conversions, failed_conversions,
        |       current_phase, error_message, workflow_id
        |FROM task_runs
        |WHERE id = ?
        |""".stripMargin

    withConnection { conn =>
      queryOne(conn, sql)(_.setLong(1, id))(readRunRow(_, sql))
    }

  override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]] =
    val sql =
      """SELECT id, source_dir, output_dir, status, started_at, completed_at,
        |       total_files, processed_files, successful_conversions, failed_conversions,
        |       current_phase, error_message, workflow_id
        |FROM task_runs
        |ORDER BY started_at DESC
        |LIMIT ? OFFSET ?
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql) { stmt =>
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      }(readRunRow(_, sql))
    }

  override def deleteRun(id: Long): IO[PersistenceError, Unit] =
    val sql = "DELETE FROM task_runs WHERE id = ?"

    withConnection { conn =>
      executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("task_runs", id)) { stmt =>
        stmt.setLong(1, id)
      }
    }

  override def saveReport(report: TaskReportRow): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO task_reports (task_run_id, step_name, report_type, content, created_at)
        |VALUES (?, ?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "task_reports") { stmt =>
        stmt.setLong(1, report.taskRunId)
        stmt.setString(2, report.stepName)
        stmt.setString(3, report.reportType)
        stmt.setString(4, report.content)
        stmt.setString(5, report.createdAt.toString)
      }
    }

  override def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[TaskReportRow]] =
    val sql =
      """SELECT id, task_run_id, step_name, report_type, content, created_at
        |FROM task_reports
        |WHERE task_run_id = ?
        |ORDER BY id ASC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql)(_.setLong(1, taskRunId))(readTaskReportRow)
    }

  override def getReport(reportId: Long): IO[PersistenceError, Option[TaskReportRow]] =
    val sql =
      """SELECT id, task_run_id, step_name, report_type, content, created_at
        |FROM task_reports
        |WHERE id = ?
        |""".stripMargin

    withConnection { conn =>
      queryOne(conn, sql)(_.setLong(1, reportId))(readTaskReportRow)
    }

  override def saveArtifact(artifact: TaskArtifactRow): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO task_artifacts (task_run_id, step_name, key, value, created_at)
        |VALUES (?, ?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "task_artifacts") { stmt =>
        stmt.setLong(1, artifact.taskRunId)
        stmt.setString(2, artifact.stepName)
        stmt.setString(3, artifact.key)
        stmt.setString(4, artifact.value)
        stmt.setString(5, artifact.createdAt.toString)
      }
    }

  override def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[TaskArtifactRow]] =
    val sql =
      """SELECT id, task_run_id, step_name, key, value, created_at
        |FROM task_artifacts
        |WHERE task_run_id = ?
        |ORDER BY id ASC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql)(_.setLong(1, taskRunId))(readTaskArtifactRow)
    }

  override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    val sql = "SELECT key, value, updated_at FROM application_settings ORDER BY key ASC"
    withConnection { conn =>
      queryMany(conn, sql)(_ => ())(readSettingRow)
    }

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    val sql = "SELECT key, value, updated_at FROM application_settings WHERE key = ?"
    withConnection { conn =>
      queryOne(conn, sql)(_.setString(1, key))(readSettingRow)
    }

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    val sql =
      """INSERT OR REPLACE INTO application_settings (key, value, updated_at)
        |VALUES (?, ?, ?)
        |""".stripMargin
    withConnection { conn =>
      withPreparedStatement(conn, sql) { stmt =>
        for
          now <- Clock.instant
          _   <- executeBlocking(sql) {
                   stmt.setString(1, key)
                   stmt.setString(2, value)
                   stmt.setString(3, now.toString)
                   stmt.executeUpdate()
                   ()
                 }
        yield ()
      }
    }

  override def getSettingsByPrefix(prefix: String): IO[PersistenceError, List[SettingRow]] =
    val sql = "SELECT key, value, updated_at FROM application_settings WHERE key LIKE ? ORDER BY key ASC"
    withConnection { conn =>
      queryMany(conn, sql)(_.setString(1, s"${prefix}%"))(readSettingRow)
    }

  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    val sql = "DELETE FROM application_settings WHERE key LIKE ?"
    withConnection { conn =>
      withPreparedStatement(conn, sql) { stmt =>
        executeBlocking(sql) {
          stmt.setString(1, s"${prefix}%")
          stmt.executeUpdate()
          ()
        }
      }
    }

  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO workflows (
        |  name, description, steps, is_builtin, created_at, updated_at
        |) VALUES (?, ?, ?, ?, ?, ?)
        |""".stripMargin
    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "workflows") { stmt =>
        stmt.setString(1, workflow.name.trim)
        setOptionalString(stmt, 2, workflow.description.filter(_.nonEmpty))
        stmt.setString(3, workflow.steps)
        stmt.setInt(4, if workflow.isBuiltin then 1 else 0)
        stmt.setString(5, workflow.createdAt.toString)
        stmt.setString(6, workflow.updatedAt.toString)
      }
    }

  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
    val sql =
      """SELECT id, name, description, steps, is_builtin, created_at, updated_at
        |FROM workflows
        |WHERE id = ?
        |""".stripMargin
    withConnection { conn =>
      queryOne(conn, sql)(_.setLong(1, id))(readWorkflowRow)
    }

  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
    val sql =
      """SELECT id, name, description, steps, is_builtin, created_at, updated_at
        |FROM workflows
        |WHERE lower(name) = lower(?)
        |LIMIT 1
        |""".stripMargin
    withConnection { conn =>
      queryOne(conn, sql)(_.setString(1, name.trim))(readWorkflowRow)
    }

  override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
    val sql =
      """SELECT id, name, description, steps, is_builtin, created_at, updated_at
        |FROM workflows
        |ORDER BY is_builtin DESC, name ASC
        |""".stripMargin
    withConnection { conn =>
      queryMany(conn, sql)(_ => ())(readWorkflowRow)
    }

  override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
    val sql =
      """UPDATE workflows
        |SET name = ?,
        |    description = ?,
        |    steps = ?,
        |    is_builtin = ?,
        |    updated_at = ?
        |WHERE id = ?
        |""".stripMargin
    for
      id <- ZIO
              .fromOption(workflow.id)
              .orElseFail(PersistenceError.QueryFailed("updateWorkflow", "Missing id for workflow update"))
      _  <- withConnection { conn =>
              executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("workflows", id)) { stmt =>
                stmt.setString(1, workflow.name.trim)
                setOptionalString(stmt, 2, workflow.description.filter(_.nonEmpty))
                stmt.setString(3, workflow.steps)
                stmt.setInt(4, if workflow.isBuiltin then 1 else 0)
                stmt.setString(5, workflow.updatedAt.toString)
                stmt.setLong(6, id)
              }
            }
    yield ()

  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
    val sql = "DELETE FROM workflows WHERE id = ?"
    withConnection { conn =>
      executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("workflows", id)) { stmt =>
        stmt.setLong(1, id)
      }
    }

  override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO custom_agents (
        |  name, display_name, description, system_prompt, tags, enabled, created_at, updated_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        |""".stripMargin
    for
      _  <- validateCustomAgentName(agent.name, "createCustomAgent")
      id <- withConnection { conn =>
              executeUpdateReturningKey(conn, sql, "custom_agents") { stmt =>
                stmt.setString(1, agent.name.trim)
                stmt.setString(2, agent.displayName)
                setOptionalString(stmt, 3, agent.description.filter(_.nonEmpty))
                stmt.setString(4, agent.systemPrompt)
                setOptionalString(stmt, 5, agent.tags.filter(_.nonEmpty))
                stmt.setInt(6, if agent.enabled then 1 else 0)
                stmt.setString(7, agent.createdAt.toString)
                stmt.setString(8, agent.updatedAt.toString)
              }
            }
    yield id

  override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
    val sql =
      """SELECT id, name, display_name, description, system_prompt, tags, enabled, created_at, updated_at
        |FROM custom_agents
        |WHERE id = ?
        |""".stripMargin
    withConnection { conn =>
      queryOne(conn, sql)(_.setLong(1, id))(readCustomAgentRow)
    }

  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
    val sql =
      """SELECT id, name, display_name, description, system_prompt, tags, enabled, created_at, updated_at
        |FROM custom_agents
        |WHERE lower(name) = lower(?)
        |LIMIT 1
        |""".stripMargin
    withConnection { conn =>
      queryOne(conn, sql)(_.setString(1, name.trim))(readCustomAgentRow)
    }

  override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] =
    val sql =
      """SELECT id, name, display_name, description, system_prompt, tags, enabled, created_at, updated_at
        |FROM custom_agents
        |ORDER BY display_name ASC, name ASC
        |""".stripMargin
    withConnection { conn =>
      queryMany(conn, sql)(_ => ())(readCustomAgentRow)
    }

  override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
    val sql =
      """UPDATE custom_agents
        |SET name = ?,
        |    display_name = ?,
        |    description = ?,
        |    system_prompt = ?,
        |    tags = ?,
        |    enabled = ?,
        |    updated_at = ?
        |WHERE id = ?
        |""".stripMargin
    for
      id <- ZIO
              .fromOption(agent.id)
              .orElseFail(PersistenceError.QueryFailed("updateCustomAgent", "Missing id for custom agent update"))
      _  <- validateCustomAgentName(agent.name, "updateCustomAgent")
      _  <- withConnection { conn =>
              executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("custom_agents", id)) { stmt =>
                stmt.setString(1, agent.name.trim)
                stmt.setString(2, agent.displayName)
                setOptionalString(stmt, 3, agent.description.filter(_.nonEmpty))
                stmt.setString(4, agent.systemPrompt)
                setOptionalString(stmt, 5, agent.tags.filter(_.nonEmpty))
                stmt.setInt(6, if agent.enabled then 1 else 0)
                stmt.setString(7, agent.updatedAt.toString)
                stmt.setLong(8, id)
              }
            }
    yield ()

  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
    val sql = "DELETE FROM custom_agents WHERE id = ?"
    withConnection { conn =>
      executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("custom_agents", id)) { stmt =>
        stmt.setLong(1, id)
      }
    }

  private def readSettingRow(rs: ResultSet): IO[PersistenceError, SettingRow] =
    ZIO.succeed(
      SettingRow(
        key = rs.getString("key"),
        value = rs.getString("value"),
        updatedAt = Instant.parse(rs.getString("updated_at")),
      )
    )

  private def readWorkflowRow(rs: ResultSet): IO[PersistenceError, WorkflowRow] =
    ZIO.succeed(
      WorkflowRow(
        id = Some(rs.getLong("id")),
        name = rs.getString("name"),
        description = optionalString(rs, "description"),
        steps = rs.getString("steps"),
        isBuiltin = rs.getInt("is_builtin") == 1,
        createdAt = Instant.parse(rs.getString("created_at")),
        updatedAt = Instant.parse(rs.getString("updated_at")),
      )
    )

  private def readCustomAgentRow(rs: ResultSet): IO[PersistenceError, CustomAgentRow] =
    ZIO.succeed(
      CustomAgentRow(
        id = Some(rs.getLong("id")),
        name = rs.getString("name"),
        displayName = rs.getString("display_name"),
        description = optionalString(rs, "description"),
        systemPrompt = rs.getString("system_prompt"),
        tags = optionalString(rs, "tags"),
        enabled = rs.getInt("enabled") == 1,
        createdAt = Instant.parse(rs.getString("created_at")),
        updatedAt = Instant.parse(rs.getString("updated_at")),
      )
    )

  private def validateCustomAgentName(name: String, context: String): IO[PersistenceError, Unit] =
    val normalized = name.trim.toLowerCase
    if normalized.isEmpty then ZIO.fail(PersistenceError.QueryFailed(context, "Custom agent name cannot be empty"))
    else if builtInAgentNamesLower.contains(normalized) then
      ZIO.fail(
        PersistenceError.QueryFailed(context, s"Custom agent name '$name' conflicts with built-in agent name")
      )
    else ZIO.unit

  private def withConnection[A](use: Connection => IO[PersistenceError, A]): IO[PersistenceError, A] =
    ensureSchemaInitialized *> ZIO.acquireReleaseWith(acquireConnection)(closeConnection)(use)

  private def acquireConnection: IO[PersistenceError, Connection] =
    ZIO
      .attemptBlocking {
        val conn = dataSource.getConnection
        val stmt = conn.createStatement()
        try stmt.execute("PRAGMA foreign_keys = ON")
        finally stmt.close()
        conn
      }
      .mapError(e => PersistenceError.ConnectionFailed(e.getMessage))

  private def closeConnection(conn: Connection): UIO[Unit] =
    ZIO.attemptBlocking(conn.close()).ignore

  private def ensureSchemaInitialized: IO[PersistenceError, Unit] =
    initialized.modifyZIO {
      case true  => ZIO.succeed(((), true))
      case false => initializeSchema.as(((), true))
    }

  private def initializeSchema: IO[PersistenceError, Unit] =
    for
      statements <-
        loadSchemaStatements(
          List(
            "db/V1__init_schema.sql",
            "db/V2__chat_and_issues.sql",
            "db/V3__custom_agents.sql",
            "db/V4__workflows.sql",
            "db/V5__activity_events.sql",
            "db/V6__rename_migration_runs_to_task_runs.sql",
            "db/V7__task_storage_generalization.sql",
          )
        )
      _          <- ZIO.acquireReleaseWith(acquireConnection)(closeConnection) { conn =>
                      ZIO.foreachDiscard(statements) { sql =>
                        withStatement(conn, sql)(stmt => executeSchemaStatement(stmt, sql))
                      } *> ensureAgentIssueColumns(conn)
                    }
    yield ()

  private def executeSchemaStatement(stmt: java.sql.Statement, sql: String): IO[PersistenceError, Unit] =
    val normalizedSql = sql.toLowerCase.replaceAll("\\s+", " ").trim
    val isLegacyRunsRename =
      normalizedSql.contains("alter table migration_runs rename to task_runs")
    executeBlocking(sql)(stmt.execute(sql)).unit.catchAll {
      case err @ PersistenceError.QueryFailed(_, cause)
           if normalizedSql.startsWith("alter table task_runs add column workflow_id") &&
           cause.toLowerCase.contains("duplicate column name") =>
        // Existing databases may already include workflow_id.
        ZIO.unit
      case err @ PersistenceError.QueryFailed(_, cause)
           if isLegacyRunsRename &&
           (cause.toLowerCase.contains("no such table") || cause.toLowerCase.contains("already exists")) =>
        // Fresh DBs define task_runs in V1; existing DBs may already be migrated.
        ZIO.unit
      case err => ZIO.fail(err)
    }

  private def ensureAgentIssueColumns(conn: Connection): IO[PersistenceError, Unit] =
    for
      info          <- readAgentIssueColumns(conn)
      _             <- if info.exists(c => c._1 == "run_id" && c._3 == 1) then rebuildAgentIssuesTable(conn) else ZIO.unit
      now           <- readAgentIssueColumns(conn)
      missingColumns = List(
                         "tags"            -> "TEXT",
                         "preferred_agent" -> "TEXT",
                         "context_path"    -> "TEXT",
                         "source_folder"   -> "TEXT",
                       ).filterNot { case (name, _) => now.exists(_._1 == name) }
      _             <- ZIO.foreachDiscard(missingColumns) {
                         case (name, ddl) =>
                           val alterSql = s"ALTER TABLE agent_issues ADD COLUMN $name $ddl"
                           withStatement(conn, alterSql)(stmt => executeBlocking(alterSql)(stmt.execute(alterSql)).unit)
                       }
    yield ()

  private def readAgentIssueColumns(conn: Connection): IO[PersistenceError, List[(String, String, Int)]] =
    val pragmaSql = "PRAGMA table_info(agent_issues)"
    withStatement(conn, pragmaSql) { stmt =>
      ZIO.acquireReleaseWith(executeBlocking(pragmaSql)(stmt.executeQuery(pragmaSql)))(rs =>
        executeBlocking(pragmaSql)(rs.close()).ignore
      ) { rs =>
        def loop(acc: List[(String, String, Int)]): IO[PersistenceError, List[(String, String, Int)]] =
          executeBlocking(pragmaSql)(rs.next()).flatMap {
            case true  =>
              for
                name    <- executeBlocking(pragmaSql)(rs.getString("name"))
                colType <- executeBlocking(pragmaSql)(rs.getString("type"))
                notNull <- executeBlocking(pragmaSql)(rs.getInt("notnull"))
                next    <- loop((name, colType, notNull) :: acc)
              yield next
            case false => ZIO.succeed(acc.reverse)
          }
        loop(Nil)
      }
    }

  private def rebuildAgentIssuesTable(conn: Connection): IO[PersistenceError, Unit] =
    val statements = List(
      "PRAGMA foreign_keys = OFF",
      "DROP TABLE IF EXISTS agent_issues_new",
      """CREATE TABLE agent_issues_new (
        |  id INTEGER PRIMARY KEY AUTOINCREMENT,
        |  run_id INTEGER,
        |  conversation_id INTEGER,
        |  title TEXT NOT NULL,
        |  description TEXT NOT NULL,
        |  issue_type TEXT NOT NULL,
        |  tags TEXT,
        |  preferred_agent TEXT,
        |  context_path TEXT,
        |  source_folder TEXT,
        |  priority TEXT NOT NULL DEFAULT 'medium' CHECK (priority IN ('low', 'medium', 'high', 'critical')),
        |  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'assigned', 'in_progress', 'completed', 'failed', 'skipped')),
        |  assigned_agent TEXT,
        |  assigned_at TEXT,
        |  completed_at TEXT,
        |  error_message TEXT,
        |  result_data TEXT,
        |  created_at TEXT NOT NULL,
        |  updated_at TEXT NOT NULL,
        |  FOREIGN KEY (run_id) REFERENCES task_runs(id) ON DELETE CASCADE,
        |  FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE SET NULL
        |)""".stripMargin,
      """INSERT INTO agent_issues_new (
        |  id, run_id, conversation_id, title, description, issue_type,
        |  tags, preferred_agent, context_path, source_folder,
        |  priority, status, assigned_agent, assigned_at, completed_at,
        |  error_message, result_data, created_at, updated_at
        |)
        |SELECT
        |  id, run_id, conversation_id, title, description, issue_type,
        |  NULL, NULL, NULL, NULL,
        |  priority, status, assigned_agent, assigned_at, completed_at,
        |  error_message, result_data, created_at, updated_at
        |FROM agent_issues""".stripMargin,
      "DROP TABLE agent_issues",
      "ALTER TABLE agent_issues_new RENAME TO agent_issues",
      "CREATE INDEX IF NOT EXISTS idx_agent_issues_run_id ON agent_issues(run_id)",
      "CREATE INDEX IF NOT EXISTS idx_agent_issues_status ON agent_issues(status)",
      "CREATE INDEX IF NOT EXISTS idx_agent_issues_assigned_agent ON agent_issues(assigned_agent)",
      "CREATE INDEX IF NOT EXISTS idx_agent_issues_conversation_id ON agent_issues(conversation_id)",
      "PRAGMA foreign_keys = ON",
    )
    ZIO.foreachDiscard(statements) { sql =>
      withStatement(conn, sql)(stmt => executeBlocking(sql)(stmt.execute(sql)).unit)
    }

  private def loadSchemaStatements(resources: List[String]): IO[PersistenceError, List[String]] =
    ZIO.foreach(resources)(loadStatementsFromResource).map(_.flatten)

  private def loadStatementsFromResource(resource: String): IO[PersistenceError, List[String]] =
    for
      maybeScript <- ZIO
                       .attempt(Option(getClass.getClassLoader.getResourceAsStream(resource)))
                       .mapError(e => PersistenceError.SchemaInitFailed(e.getMessage))
      stream      <- ZIO
                       .fromOption(maybeScript)
                       .orElseFail(PersistenceError.SchemaInitFailed(s"Schema resource $resource not found"))
      script      <- ZIO
                       .acquireReleaseWith(
                         ZIO.succeed(scala.io.Source.fromInputStream(stream, "UTF-8"))
                       )(src => ZIO.attempt(src.close()).orDie)(src => ZIO.attempt(src.mkString))
                       .mapError(e => PersistenceError.SchemaInitFailed(e.getMessage))
    yield script.split(";").map(_.trim).filter(_.nonEmpty).toList

  private def executeUpdateReturningKey(
    conn: Connection,
    sql: String,
    entity: String,
  )(
    bind: java.sql.PreparedStatement => Unit
  ): IO[PersistenceError, Long] =
    withPreparedStatement(conn, sql, Statement.RETURN_GENERATED_KEYS) { stmt =>
      for
        _        <- executeBlocking(sql)(bind(stmt))
        _        <- executeBlocking(sql)(stmt.executeUpdate()).unit
        maybeKey <- executeBlocking(sql) {
                      val keys = stmt.getGeneratedKeys
                      try if keys.next() then Some(keys.getLong(1)) else None
                      finally keys.close()
                    }
        key      <- ZIO
                      .fromOption(maybeKey)
                      .orElseFail(PersistenceError.QueryFailed(sql, s"No generated key returned for $entity"))
      yield key
    }

  private def executeUpdateExpectingRows(
    conn: Connection,
    sql: String,
    notFound: PersistenceError,
  )(
    bind: java.sql.PreparedStatement => Unit
  ): IO[PersistenceError, Unit] =
    withPreparedStatement(conn, sql) { stmt =>
      for
        _       <- executeBlocking(sql)(bind(stmt))
        updated <- executeBlocking(sql)(stmt.executeUpdate())
        _       <- if updated == 0 then ZIO.fail(notFound) else ZIO.unit
      yield ()
    }

  private def queryOne[A](
    conn: Connection,
    sql: String,
  )(
    bind: java.sql.PreparedStatement => Unit
  )(
    read: ResultSet => IO[PersistenceError, A]
  ): IO[PersistenceError, Option[A]] =
    withPreparedStatement(conn, sql) { stmt =>
      for
        _      <- executeBlocking(sql)(bind(stmt))
        result <- executeQuery(stmt, sql) { rs =>
                    for
                      hasNext <- executeBlocking(sql)(rs.next())
                      value   <- if hasNext then read(rs).map(Some(_)) else ZIO.none
                    yield value
                  }
      yield result
    }

  private def queryMany[A](
    conn: Connection,
    sql: String,
  )(
    bind: java.sql.PreparedStatement => Unit
  )(
    read: ResultSet => IO[PersistenceError, A]
  ): IO[PersistenceError, List[A]] =
    withPreparedStatement(conn, sql) { stmt =>
      for
        _      <- executeBlocking(sql)(bind(stmt))
        result <- executeQuery(stmt, sql) { rs =>
                    def loop(acc: List[A]): IO[PersistenceError, List[A]] =
                      for
                        hasNext <- executeBlocking(sql)(rs.next())
                        out     <- if !hasNext then ZIO.succeed(acc.reverse)
                                   else read(rs).flatMap(v => loop(v :: acc))
                      yield out

                    loop(Nil)
                  }
      yield result
    }

  private def executeQuery[A](
    stmt: java.sql.PreparedStatement,
    sql: String,
  )(
    use: ResultSet => IO[PersistenceError, A]
  ): IO[PersistenceError, A] =
    for
      rs  <- executeBlocking(sql)(stmt.executeQuery())
      out <- ZIO.acquireReleaseWith(ZIO.succeed(rs))(closeResultSet) { resultSet =>
               use(resultSet)
             }
    yield out

  private def closeResultSet(rs: ResultSet): UIO[Unit] =
    ZIO.attemptBlocking(rs.close()).ignore

  private def withPreparedStatement[A](
    conn: Connection,
    sql: String,
    mode: Int = Statement.NO_GENERATED_KEYS,
  )(
    use: java.sql.PreparedStatement => IO[PersistenceError, A]
  ): IO[PersistenceError, A] =
    for
      stmt <- executeBlocking(sql)(conn.prepareStatement(sql, mode))
      out  <- ZIO.acquireReleaseWith(ZIO.succeed(stmt))(closeStatement) { prepared =>
                use(prepared)
              }
    yield out

  private def withStatement[A](
    conn: Connection,
    sql: String,
  )(
    use: java.sql.Statement => IO[PersistenceError, A]
  ): IO[PersistenceError, A] =
    for
      stmt <- executeBlocking(sql)(conn.createStatement())
      out  <- ZIO.acquireReleaseWith(ZIO.succeed(stmt))(closeStatement) { statement =>
                use(statement)
              }
    yield out

  private def closeStatement(stmt: java.sql.Statement): UIO[Unit] =
    ZIO.attemptBlocking(stmt.close()).ignore

  private def setOptionalString(stmt: java.sql.PreparedStatement, index: Int, value: Option[String]): Unit =
    value match
      case Some(v) => stmt.setString(index, v)
      case None    => stmt.setNull(index, Types.VARCHAR)

  private def setOptionalLong(stmt: java.sql.PreparedStatement, index: Int, value: Option[Long]): Unit =
    value match
      case Some(v) => stmt.setLong(index, v)
      case None    => stmt.setNull(index, Types.BIGINT)

  private def optionalString(rs: ResultSet, column: String): Option[String] =
    Option(rs.getString(column))

  private def optionalLong(rs: ResultSet, column: String): Option[Long] =
    val value = rs.getLong(column)
    if rs.wasNull() then None else Some(value)

  private def parseRunStatus(raw: String, sql: String): IO[PersistenceError, RunStatus] =
    ZIO
      .fromOption(RunStatus.values.find(_.toString == raw))
      .orElseFail(PersistenceError.QueryFailed(sql, s"Invalid RunStatus: $raw"))

  private def readRunRow(rs: ResultSet, sql: String): IO[PersistenceError, TaskRunRow] =
    for
      status <- parseRunStatus(rs.getString("status"), sql)
    yield TaskRunRow(
      id = rs.getLong("id"),
      workflowId = optionalLong(rs, "workflow_id"),
      sourceDir = rs.getString("source_dir"),
      outputDir = rs.getString("output_dir"),
      status = status,
      startedAt = Instant.parse(rs.getString("started_at")),
      completedAt = optionalString(rs, "completed_at").map(Instant.parse),
      totalFiles = rs.getInt("total_files"),
      processedFiles = rs.getInt("processed_files"),
      successfulConversions = rs.getInt("successful_conversions"),
      failedConversions = rs.getInt("failed_conversions"),
      currentPhase = optionalString(rs, "current_phase"),
      errorMessage = optionalString(rs, "error_message"),
    )

  private def readTaskReportRow(rs: ResultSet): IO[PersistenceError, TaskReportRow] =
    ZIO.succeed(
      TaskReportRow(
        id = rs.getLong("id"),
        taskRunId = rs.getLong("task_run_id"),
        stepName = rs.getString("step_name"),
        reportType = rs.getString("report_type"),
        content = rs.getString("content"),
        createdAt = Instant.parse(rs.getString("created_at")),
      )
    )

  private def readTaskArtifactRow(rs: ResultSet): IO[PersistenceError, TaskArtifactRow] =
    ZIO.succeed(
      TaskArtifactRow(
        id = rs.getLong("id"),
        taskRunId = rs.getLong("task_run_id"),
        stepName = rs.getString("step_name"),
        key = rs.getString("key"),
        value = rs.getString("value"),
        createdAt = Instant.parse(rs.getString("created_at")),
      )
    )

  private def executeBlocking[A](sql: String)(thunk: => A): IO[PersistenceError, A] =
    ZIO
      .attemptBlocking(thunk)
      .mapError(e => PersistenceError.QueryFailed(sql, e.getMessage))
