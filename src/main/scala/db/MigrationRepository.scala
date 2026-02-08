package db

import java.sql.{ Connection, ResultSet, Statement, Types }
import java.time.Instant
import javax.sql.DataSource

import zio.*

trait MigrationRepository:
  // Runs
  def createRun(run: MigrationRunRow): IO[PersistenceError, Long]
  def updateRun(run: MigrationRunRow): IO[PersistenceError, Unit]
  def getRun(id: Long): IO[PersistenceError, Option[MigrationRunRow]]
  def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[MigrationRunRow]]
  def deleteRun(id: Long): IO[PersistenceError, Unit]

  // Files
  def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit]
  def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]]

  // Analysis
  def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long]
  def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]]

  // Dependencies
  def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit]
  def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]]

  // Progress
  def saveProgress(p: PhaseProgressRow): IO[PersistenceError, Long]
  def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]]
  def updateProgress(p: PhaseProgressRow): IO[PersistenceError, Unit]

object MigrationRepository:
  def createRun(run: MigrationRunRow): ZIO[MigrationRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[MigrationRepository](_.createRun(run))

  def updateRun(run: MigrationRunRow): ZIO[MigrationRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[MigrationRepository](_.updateRun(run))

  def getRun(id: Long): ZIO[MigrationRepository, PersistenceError, Option[MigrationRunRow]] =
    ZIO.serviceWithZIO[MigrationRepository](_.getRun(id))

  def listRuns(offset: Int, limit: Int): ZIO[MigrationRepository, PersistenceError, List[MigrationRunRow]] =
    ZIO.serviceWithZIO[MigrationRepository](_.listRuns(offset, limit))

  def deleteRun(id: Long): ZIO[MigrationRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[MigrationRepository](_.deleteRun(id))

  def saveFiles(files: List[CobolFileRow]): ZIO[MigrationRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[MigrationRepository](_.saveFiles(files))

  def getFilesByRun(runId: Long): ZIO[MigrationRepository, PersistenceError, List[CobolFileRow]] =
    ZIO.serviceWithZIO[MigrationRepository](_.getFilesByRun(runId))

  def saveAnalysis(analysis: CobolAnalysisRow): ZIO[MigrationRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[MigrationRepository](_.saveAnalysis(analysis))

  def getAnalysesByRun(runId: Long): ZIO[MigrationRepository, PersistenceError, List[CobolAnalysisRow]] =
    ZIO.serviceWithZIO[MigrationRepository](_.getAnalysesByRun(runId))

  def saveDependencies(deps: List[DependencyRow]): ZIO[MigrationRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[MigrationRepository](_.saveDependencies(deps))

  def getDependenciesByRun(runId: Long): ZIO[MigrationRepository, PersistenceError, List[DependencyRow]] =
    ZIO.serviceWithZIO[MigrationRepository](_.getDependenciesByRun(runId))

  def saveProgress(progress: PhaseProgressRow): ZIO[MigrationRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[MigrationRepository](_.saveProgress(progress))

  def getProgress(runId: Long, phase: String): ZIO[MigrationRepository, PersistenceError, Option[PhaseProgressRow]] =
    ZIO.serviceWithZIO[MigrationRepository](_.getProgress(runId, phase))

  def updateProgress(progress: PhaseProgressRow): ZIO[MigrationRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[MigrationRepository](_.updateProgress(progress))

  val live: ZLayer[DataSource, Nothing, MigrationRepository] =
    ZLayer.fromZIO {
      for
        dataSource  <- ZIO.service[DataSource]
        initialized <- Ref.Synchronized.make(false)
      yield MigrationRepositoryLive(dataSource, initialized)
    }

final case class MigrationRepositoryLive(
  dataSource: DataSource,
  initialized: Ref.Synchronized[Boolean],
) extends MigrationRepository:

  override def createRun(run: MigrationRunRow): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO migration_runs (
        |  source_dir, output_dir, status, started_at, completed_at,
        |  total_files, processed_files, successful_conversions, failed_conversions,
        |  current_phase, error_message
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "migration_runs") { stmt =>
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
      }
    }

  override def updateRun(run: MigrationRunRow): IO[PersistenceError, Unit] =
    val sql =
      """UPDATE migration_runs
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
        |    error_message = ?
        |WHERE id = ?
        |""".stripMargin

    withConnection { conn =>
      executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("migration_runs", run.id)) { stmt =>
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
        stmt.setLong(12, run.id)
      }
    }

  override def getRun(id: Long): IO[PersistenceError, Option[MigrationRunRow]] =
    val sql =
      """SELECT id, source_dir, output_dir, status, started_at, completed_at,
        |       total_files, processed_files, successful_conversions, failed_conversions,
        |       current_phase, error_message
        |FROM migration_runs
        |WHERE id = ?
        |""".stripMargin

    withConnection { conn =>
      queryOne(conn, sql)(_.setLong(1, id))(readRunRow(_, sql))
    }

  override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[MigrationRunRow]] =
    val sql =
      """SELECT id, source_dir, output_dir, status, started_at, completed_at,
        |       total_files, processed_files, successful_conversions, failed_conversions,
        |       current_phase, error_message
        |FROM migration_runs
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
    val sql = "DELETE FROM migration_runs WHERE id = ?"

    withConnection { conn =>
      executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("migration_runs", id)) { stmt =>
        stmt.setLong(1, id)
      }
    }

  override def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit] =
    if files.isEmpty then ZIO.unit
    else
      val sql =
        """INSERT INTO cobol_files (run_id, path, name, file_type, size, line_count, encoding, created_at)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          |""".stripMargin

      withConnection { conn =>
        withPreparedStatement(conn, sql) { stmt =>
          ZIO
            .foreachDiscard(files) { file =>
              executeBlocking(sql) {
                stmt.setLong(1, file.runId)
                stmt.setString(2, file.path)
                stmt.setString(3, file.name)
                stmt.setString(4, file.fileType.toString)
                stmt.setLong(5, file.size)
                stmt.setLong(6, file.lineCount)
                stmt.setString(7, file.encoding)
                stmt.setString(8, file.createdAt.toString)
                stmt.addBatch()
                ()
              }
            } *>
            executeBlocking(sql) {
              stmt.executeBatch()
              ()
            }
        }
      }

  override def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]] =
    val sql =
      """SELECT id, run_id, path, name, file_type, size, line_count, encoding, created_at
        |FROM cobol_files
        |WHERE run_id = ?
        |ORDER BY id ASC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql)(_.setLong(1, runId))(readFileRow(_, sql))
    }

  override def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO cobol_analyses (run_id, file_id, analysis_json, created_at)
        |VALUES (?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "cobol_analyses") { stmt =>
        stmt.setLong(1, analysis.runId)
        stmt.setLong(2, analysis.fileId)
        stmt.setString(3, analysis.analysisJson)
        stmt.setString(4, analysis.createdAt.toString)
      }
    }

  override def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]] =
    val sql =
      """SELECT id, run_id, file_id, analysis_json, created_at
        |FROM cobol_analyses
        |WHERE run_id = ?
        |ORDER BY id ASC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql)(_.setLong(1, runId))(readAnalysisRow)
    }

  override def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit] =
    if deps.isEmpty then ZIO.unit
    else
      val sql =
        """INSERT INTO dependencies (run_id, source_node, target_node, edge_type)
          |VALUES (?, ?, ?, ?)
          |""".stripMargin

      withConnection { conn =>
        withPreparedStatement(conn, sql) { stmt =>
          ZIO
            .foreachDiscard(deps) { dep =>
              executeBlocking(sql) {
                stmt.setLong(1, dep.runId)
                stmt.setString(2, dep.sourceNode)
                stmt.setString(3, dep.targetNode)
                stmt.setString(4, dep.edgeType)
                stmt.addBatch()
                ()
              }
            } *>
            executeBlocking(sql) {
              stmt.executeBatch()
              ()
            }
        }
      }

  override def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]] =
    val sql =
      """SELECT id, run_id, source_node, target_node, edge_type
        |FROM dependencies
        |WHERE run_id = ?
        |ORDER BY id ASC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql)(_.setLong(1, runId))(readDependencyRow)
    }

  override def saveProgress(p: PhaseProgressRow): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO phase_progress (run_id, phase, status, item_total, item_processed, error_count, updated_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "phase_progress") { stmt =>
        stmt.setLong(1, p.runId)
        stmt.setString(2, p.phase)
        stmt.setString(3, p.status)
        stmt.setInt(4, p.itemTotal)
        stmt.setInt(5, p.itemProcessed)
        stmt.setInt(6, p.errorCount)
        stmt.setString(7, p.updatedAt.toString)
      }
    }

  override def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]] =
    val sql =
      """SELECT id, run_id, phase, status, item_total, item_processed, error_count, updated_at
        |FROM phase_progress
        |WHERE run_id = ? AND phase = ?
        |LIMIT 1
        |""".stripMargin

    withConnection { conn =>
      queryOne(conn, sql) { stmt =>
        stmt.setLong(1, runId)
        stmt.setString(2, phase)
      }(readProgressRow)
    }

  override def updateProgress(p: PhaseProgressRow): IO[PersistenceError, Unit] =
    val sql =
      """UPDATE phase_progress
        |SET run_id = ?,
        |    phase = ?,
        |    status = ?,
        |    item_total = ?,
        |    item_processed = ?,
        |    error_count = ?,
        |    updated_at = ?
        |WHERE id = ?
        |""".stripMargin

    withConnection { conn =>
      executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("phase_progress", p.id)) { stmt =>
        stmt.setLong(1, p.runId)
        stmt.setString(2, p.phase)
        stmt.setString(3, p.status)
        stmt.setInt(4, p.itemTotal)
        stmt.setInt(5, p.itemProcessed)
        stmt.setInt(6, p.errorCount)
        stmt.setString(7, p.updatedAt.toString)
        stmt.setLong(8, p.id)
      }
    }

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
      maybeScript <- ZIO.attempt(Option(getClass.getClassLoader.getResourceAsStream("db/V1__init_schema.sql")))
                       .mapError(e => PersistenceError.SchemaInitFailed(e.getMessage))
      stream      <- ZIO.fromOption(maybeScript)
                       .orElseFail(PersistenceError.SchemaInitFailed("Schema resource db/V1__init_schema.sql not found"))
      script      <- ZIO.acquireReleaseWith(
                       ZIO.succeed(scala.io.Source.fromInputStream(stream, "UTF-8"))
                     )(src => ZIO.attempt(src.close()).orDie)(src => ZIO.attempt(src.mkString))
                       .mapError(e => PersistenceError.SchemaInitFailed(e.getMessage))
      statements   = script.split(";").map(_.trim).filter(_.nonEmpty).toList
      _           <- ZIO.acquireReleaseWith(acquireConnection)(closeConnection) { conn =>
                       ZIO.foreachDiscard(statements) { sql =>
                         withStatement(conn, sql)(stmt => executeBlocking(sql)(stmt.execute(sql)).unit)
                       }
                     }
    yield ()

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

  private def optionalString(rs: ResultSet, column: String): Option[String] =
    Option(rs.getString(column))

  private def parseRunStatus(raw: String, sql: String): IO[PersistenceError, RunStatus] =
    ZIO
      .fromOption(RunStatus.values.find(_.toString == raw))
      .orElseFail(PersistenceError.QueryFailed(sql, s"Invalid RunStatus: $raw"))

  private def parseFileType(raw: String, sql: String): IO[PersistenceError, FileType] =
    ZIO
      .fromOption(FileType.values.find(_.toString == raw))
      .orElseFail(PersistenceError.QueryFailed(sql, s"Invalid FileType: $raw"))

  private def readRunRow(rs: ResultSet, sql: String): IO[PersistenceError, MigrationRunRow] =
    for
      status <- parseRunStatus(rs.getString("status"), sql)
    yield MigrationRunRow(
      id = rs.getLong("id"),
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

  private def readFileRow(rs: ResultSet, sql: String): IO[PersistenceError, CobolFileRow] =
    for
      fileType <- parseFileType(rs.getString("file_type"), sql)
    yield CobolFileRow(
      id = rs.getLong("id"),
      runId = rs.getLong("run_id"),
      path = rs.getString("path"),
      name = rs.getString("name"),
      fileType = fileType,
      size = rs.getLong("size"),
      lineCount = rs.getLong("line_count"),
      encoding = rs.getString("encoding"),
      createdAt = Instant.parse(rs.getString("created_at")),
    )

  private def readAnalysisRow(rs: ResultSet): IO[PersistenceError, CobolAnalysisRow] =
    ZIO.succeed(
      CobolAnalysisRow(
        id = rs.getLong("id"),
        runId = rs.getLong("run_id"),
        fileId = rs.getLong("file_id"),
        analysisJson = rs.getString("analysis_json"),
        createdAt = Instant.parse(rs.getString("created_at")),
      )
    )

  private def readDependencyRow(rs: ResultSet): IO[PersistenceError, DependencyRow] =
    ZIO.succeed(
      DependencyRow(
        id = rs.getLong("id"),
        runId = rs.getLong("run_id"),
        sourceNode = rs.getString("source_node"),
        targetNode = rs.getString("target_node"),
        edgeType = rs.getString("edge_type"),
      )
    )

  private def readProgressRow(rs: ResultSet): IO[PersistenceError, PhaseProgressRow] =
    ZIO.succeed(
      PhaseProgressRow(
        id = rs.getLong("id"),
        runId = rs.getLong("run_id"),
        phase = rs.getString("phase"),
        status = rs.getString("status"),
        itemTotal = rs.getInt("item_total"),
        itemProcessed = rs.getInt("item_processed"),
        errorCount = rs.getInt("error_count"),
        updatedAt = Instant.parse(rs.getString("updated_at")),
      )
    )

  private def executeBlocking[A](sql: String)(thunk: => A): IO[PersistenceError, A] =
    ZIO
      .attemptBlocking(thunk)
      .mapError(e => PersistenceError.QueryFailed(sql, e.getMessage))
