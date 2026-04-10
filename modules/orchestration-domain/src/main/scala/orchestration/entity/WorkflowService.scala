package orchestration.entity

import zio.*
import zio.json.*

import _root_.config.entity.WorkflowDefinition
import shared.errors.PersistenceError

enum WorkflowServiceError derives JsonCodec:
  case ValidationFailed(errors: List[String])
  case PersistenceFailed(error: PersistenceError)
  case StepsDecodingFailed(workflowName: String, reason: String)

trait WorkflowService:
  def createWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Long]
  def getWorkflow(id: Long): IO[WorkflowServiceError, Option[WorkflowDefinition]]
  def getWorkflowByName(name: String): IO[WorkflowServiceError, Option[WorkflowDefinition]]
  def listWorkflows: IO[WorkflowServiceError, List[WorkflowDefinition]]
  def updateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Unit]
  def deleteWorkflow(id: Long): IO[WorkflowServiceError, Unit]

object WorkflowService:
  def createWorkflow(workflow: WorkflowDefinition): ZIO[WorkflowService, WorkflowServiceError, Long] =
    ZIO.serviceWithZIO[WorkflowService](_.createWorkflow(workflow))

  def getWorkflow(id: Long): ZIO[WorkflowService, WorkflowServiceError, Option[WorkflowDefinition]] =
    ZIO.serviceWithZIO[WorkflowService](_.getWorkflow(id))

  def getWorkflowByName(name: String): ZIO[WorkflowService, WorkflowServiceError, Option[WorkflowDefinition]] =
    ZIO.serviceWithZIO[WorkflowService](_.getWorkflowByName(name))

  def listWorkflows: ZIO[WorkflowService, WorkflowServiceError, List[WorkflowDefinition]] =
    ZIO.serviceWithZIO[WorkflowService](_.listWorkflows)

  def updateWorkflow(workflow: WorkflowDefinition): ZIO[WorkflowService, WorkflowServiceError, Unit] =
    ZIO.serviceWithZIO[WorkflowService](_.updateWorkflow(workflow))

  def deleteWorkflow(id: Long): ZIO[WorkflowService, WorkflowServiceError, Unit] =
    ZIO.serviceWithZIO[WorkflowService](_.deleteWorkflow(id))
