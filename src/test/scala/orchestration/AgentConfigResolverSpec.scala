package orchestration

import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.{ AIProvider, AIProviderConfig }
import db.*
import orchestration.control.AgentConfigResolverLive

object AgentConfigResolverSpec extends ZIOSpecDefault:

  private val now = Instant.EPOCH

  private def row(key: String, value: String): SettingRow =
    SettingRow(key = key, value = value, updatedAt = now)

  final private case class SettingsTaskRepo(rows: List[SettingRow]) extends TaskRepository:
    override def createRun(run: TaskRunRow): IO[PersistenceError, Long]                           =
      ZIO.fail(PersistenceError.QueryFailed("createRun", "unused"))
    override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]                           =
      ZIO.fail(PersistenceError.QueryFailed("updateRun", "unused"))
    override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]                       =
      ZIO.fail(PersistenceError.QueryFailed("getRun", "unused"))
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]]        =
      ZIO.fail(PersistenceError.QueryFailed("listRuns", "unused"))
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                  =
      ZIO.fail(PersistenceError.QueryFailed("deleteRun", "unused"))
    override def saveReport(report: TaskReportRow): IO[PersistenceError, Long]                    =
      ZIO.fail(PersistenceError.QueryFailed("saveReport", "unused"))
    override def getReport(reportId: Long): IO[PersistenceError, Option[TaskReportRow]]           =
      ZIO.fail(PersistenceError.QueryFailed("getReport", "unused"))
    override def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[TaskReportRow]]     =
      ZIO.fail(PersistenceError.QueryFailed("getReportsByTask", "unused"))
    override def saveArtifact(artifact: TaskArtifactRow): IO[PersistenceError, Long]              =
      ZIO.fail(PersistenceError.QueryFailed("saveArtifact", "unused"))
    override def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[TaskArtifactRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getArtifactsByTask", "unused"))
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           = ZIO.succeed(rows)
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                =
      ZIO.succeed(rows.find(_.key == key))
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            =
      ZIO.fail(PersistenceError.QueryFailed("upsertSetting", "unused"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentConfigResolverSpec")(
    test("agent provider override accepts lowercase/hyphen format") {
      val resolver = AgentConfigResolverLive(
        repository = SettingsTaskRepo(
          List(
            row("agent.task-planner.ai.provider", "gemini-api"),
            row("agent.task-planner.ai.apiKey", "g-key"),
          )
        ),
        startupConfig = AIProviderConfig(provider = AIProvider.LmStudio, model = "openai/gpt-oss-20b"),
      )

      for
        resolved <- resolver.resolveConfig("task-planner")
      yield assertTrue(
        resolved.provider == AIProvider.GeminiApi,
        resolved.apiKey.contains("g-key"),
      )
    },
    test("provider switch without explicit model uses provider-specific default model") {
      val resolver = AgentConfigResolverLive(
        repository = SettingsTaskRepo(
          List(
            row("ai.provider", "LmStudio"),
            row("ai.model", "openai/gpt-oss-20b"),
            row("ai.baseUrl", "http://localhost:1234/v1"),
            row("agent.task-planner.ai.provider", "GeminiApi"),
            row("agent.task-planner.ai.apiKey", "g-key"),
          )
        ),
        startupConfig = AIProviderConfig(
          provider = AIProvider.LmStudio,
          model = "openai/gpt-oss-20b",
          baseUrl = Some("http://localhost:1234"),
        ),
      )

      for
        resolved <- resolver.resolveConfig("task-planner")
      yield assertTrue(
        resolved.provider == AIProvider.GeminiApi,
        resolved.model == "gemini-2.5-flash",
        resolved.baseUrl.contains("https://generativelanguage.googleapis.com"),
        resolved.apiKey.contains("g-key"),
      )
    },
  )
