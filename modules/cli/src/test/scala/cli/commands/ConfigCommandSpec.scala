package cli.commands

import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.*
import shared.errors.PersistenceError

object ConfigCommandSpec extends ZIOSpecDefault:

  final class InMemoryConfigRepo(ref: Ref[Map[String, String]]) extends ConfigRepository:

    private def toRow(key: String, value: String): SettingRow =
      SettingRow(key = key, value = value, updatedAt = Instant.EPOCH)

    override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
      ref.get.map(_.toList.sortBy(_._1).map(toRow.tupled))

    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
      ref.get.map(_.get(key).map(v => toRow(key, v)))

    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
      ref.update(_ + (key -> value))

    override def deleteSetting(key: String): IO[PersistenceError, Unit] =
      ref.update(_ - key)

    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
      ref.update(_.filterNot(_._1.startsWith(prefix)))

    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
      ZIO.succeed(0L)

    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
      ZIO.succeed(None)

    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
      ZIO.succeed(None)

    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
      ZIO.succeed(Nil)

    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
      ZIO.unit

    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
      ZIO.unit

    override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
      ZIO.succeed(0L)

    override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
      ZIO.succeed(None)

    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
      ZIO.succeed(None)

    override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] =
      ZIO.succeed(Nil)

    override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
      ZIO.unit

    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
      ZIO.unit

  def makeRepo(initial: Map[String, String] = Map.empty): UIO[ConfigRepository] =
    Ref.make(initial).map(new InMemoryConfigRepo(_))

  def spec = suite("ConfigCommand")(
    test("listSettings with data shows formatted aligned output") {
      for
        repo   <- makeRepo(Map("llm.model" -> "gpt-4", "llm.provider" -> "openai"))
        result <- ConfigCommand.listSettings.provide(ZLayer.succeed(repo))
      yield
        assertTrue(result.contains("llm.model")) &&
          assertTrue(result.contains("gpt-4")) &&
          assertTrue(result.contains("llm.provider")) &&
          assertTrue(result.contains("openai"))
    },
    test("listSettings with empty store shows no-settings message") {
      for
        repo   <- makeRepo()
        result <- ConfigCommand.listSettings.provide(ZLayer.succeed(repo))
      yield assertTrue(result == "No settings configured.")
    },
    test("setSetting updates the value and returns confirmation") {
      for
        repo   <- makeRepo()
        result <- ConfigCommand.setSetting("foo.key", "bar-value").provide(ZLayer.succeed(repo))
        stored <- repo.getSetting("foo.key")
      yield
        assertTrue(result == "Set foo.key = bar-value") &&
          assertTrue(stored.map(_.value).contains("bar-value"))
    },
  )
