package web.controllers

import zio.*
import zio.http.*
import zio.test.*

import _root_.config.boundary.WorkflowsControllerLive
import _root_.config.entity.{ ConfigRepository, CustomAgentRow, SettingRow, WorkflowDefinition, WorkflowRow }
import orchestration.control.{ WorkflowService, WorkflowServiceError }
import shared.errors.PersistenceError

object WorkflowsControllerSpec extends ZIOSpecDefault:

  private val workflowService: WorkflowService = new WorkflowService:
    override def createWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Long] =
      ZIO.fail(WorkflowServiceError.ValidationFailed(List("create should not be called when steps are empty")))

    override def getWorkflow(id: Long): IO[WorkflowServiceError, Option[WorkflowDefinition]] = ZIO.none

    override def getWorkflowByName(name: String): IO[WorkflowServiceError, Option[WorkflowDefinition]] = ZIO.none

    override def listWorkflows: IO[WorkflowServiceError, List[WorkflowDefinition]] = ZIO.succeed(Nil)

    override def updateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Unit] =
      ZIO.fail(WorkflowServiceError.ValidationFailed(List("unused")))

    override def deleteWorkflow(id: Long): IO[WorkflowServiceError, Unit] =
      ZIO.fail(WorkflowServiceError.ValidationFailed(List("unused")))

  private val configRepository: ConfigRepository = new ConfigRepository:
    override def getAllSettings: IO[PersistenceError, List[SettingRow]] = ZIO.succeed(Nil)

    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] = ZIO.none

    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] = ZIO.unit

    override def deleteSetting(key: String): IO[PersistenceError, Unit] = ZIO.unit

    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] = ZIO.unit

    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
      ZIO.fail(PersistenceError.QueryFailed("createWorkflow", "unused"))

    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getWorkflow", "unused"))

    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getWorkflowByName", "unused"))

    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
      ZIO.fail(PersistenceError.QueryFailed("listWorkflows", "unused"))

    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
      ZIO.fail(PersistenceError.QueryFailed("updateWorkflow", "unused"))

    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
      ZIO.fail(PersistenceError.QueryFailed("deleteWorkflow", "unused"))

    override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
      ZIO.fail(PersistenceError.QueryFailed("createCustomAgent", "unused"))

    override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getCustomAgent", "unused"))

    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getCustomAgentByName", "unused"))

    override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] = ZIO.succeed(Nil)

    override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
      ZIO.fail(PersistenceError.QueryFailed("updateCustomAgent", "unused"))

    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
      ZIO.fail(PersistenceError.QueryFailed("deleteCustomAgent", "unused"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkflowsControllerSpec")(
    test("POST /workflows redirects back to new form when steps are empty") {
      val controller = WorkflowsControllerLive(workflowService, configRepository)
      val request    = Request.post(
        URL.decode("/workflows").toOption.get,
        Body.fromString("name=No+Steps&description=demo&orderedSteps=&stepAgentsJson=%7B%7D"),
      )

      for
        response    <- controller.routes.runZIO(request)
        locationText = response.headers.toString
      yield assertTrue(
        response.status == Status.SeeOther,
        locationText.contains("/workflows/new?flash="),
      )
    }
  )
