package orchestration

import java.time.Instant

import zio.*
import zio.json.*

import db.*
import models.*

enum WorkflowServiceError derives JsonCodec:
  case ValidationFailed(errors: List[String])
  case PersistenceFailed(error: PersistenceError)
  case StepsDecodingFailed(workflowName: String, reason: String)

private case class WorkflowStoragePayload(
  steps: List[TaskStep],
  stepAgents: Map[String, String] = Map.empty,
  dynamicGraph: Option[WorkflowGraph] = None,
) derives JsonCodec

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

  val live: ZLayer[TaskRepository, Nothing, WorkflowService] =
    ZLayer.fromFunction(WorkflowServiceLive.apply)

final case class WorkflowServiceLive(
  repository: TaskRepository
) extends WorkflowService:
  override def createWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Long] =
    for
      validated <- validateWorkflow(workflow)
      now       <- Clock.instant
      id        <- repository
                     .createWorkflow(toRow(validated, now, now))
                     .mapError(WorkflowServiceError.PersistenceFailed.apply)
    yield id

  override def getWorkflow(id: Long): IO[WorkflowServiceError, Option[WorkflowDefinition]] =
    repository
      .getWorkflow(id)
      .mapError(WorkflowServiceError.PersistenceFailed.apply)
      .flatMap(ZIO.foreach(_)(fromRow))

  override def getWorkflowByName(name: String): IO[WorkflowServiceError, Option[WorkflowDefinition]] =
    repository
      .getWorkflowByName(name)
      .mapError(WorkflowServiceError.PersistenceFailed.apply)
      .flatMap(ZIO.foreach(_)(fromRow))

  override def listWorkflows: IO[WorkflowServiceError, List[WorkflowDefinition]] =
    repository
      .listWorkflows
      .mapError(WorkflowServiceError.PersistenceFailed.apply)
      .flatMap(rows => ZIO.foreach(rows)(fromRow))

  override def updateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Unit] =
    for
      validated <- validateWorkflow(workflow)
      id        <- ZIO
                     .fromOption(validated.id)
                     .orElseFail(
                       WorkflowServiceError.ValidationFailed(List("Workflow id is required for update"))
                     )
      existing  <- repository
                     .getWorkflow(id)
                     .mapError(WorkflowServiceError.PersistenceFailed.apply)
      previous  <- ZIO
                     .fromOption(existing)
                     .orElseFail(WorkflowServiceError.PersistenceFailed(PersistenceError.NotFound("workflows", id)))
      now       <- Clock.instant
      _         <- repository
                     .updateWorkflow(
                       toRow(
                         validated,
                         previous.createdAt,
                         now,
                       )
                     )
                     .mapError(WorkflowServiceError.PersistenceFailed.apply)
    yield ()

  override def deleteWorkflow(id: Long): IO[WorkflowServiceError, Unit] =
    repository.deleteWorkflow(id).mapError(WorkflowServiceError.PersistenceFailed.apply)

  private def validateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, WorkflowDefinition] =
    WorkflowValidator
      .validate(workflow)
      .fold(
        errors => ZIO.fail(WorkflowServiceError.ValidationFailed(errors)),
        valid => ZIO.succeed(valid),
      )

  private def fromRow(row: WorkflowRow): IO[WorkflowServiceError, WorkflowDefinition] =
    decodeStorage(row.name, row.steps).map {
      case (parsedSteps, parsedAgents, parsedGraph) =>
        WorkflowDefinition(
          id = row.id,
          name = row.name,
          description = row.description,
          steps = parsedSteps,
          stepAgents = parsedAgents.toList.map {
            case (step, agentName) =>
              WorkflowStepAgent(step, agentName)
          },
          isBuiltin = row.isBuiltin,
          dynamicGraph = parsedGraph,
        )
    }

  private def toRow(workflow: WorkflowDefinition, createdAt: Instant, updatedAt: Instant): WorkflowRow =
    val payload = WorkflowStoragePayload(
      steps = workflow.steps,
      stepAgents = workflow.stepAgents
        .collect {
          case WorkflowStepAgent(step, agent) if workflow.steps.contains(step) && agent.trim.nonEmpty =>
            step.toString -> agent.trim
        }
        .toMap,
      dynamicGraph = workflow.dynamicGraph,
    )
    WorkflowRow(
      id = workflow.id,
      name = workflow.name,
      description = workflow.description.filter(_.trim.nonEmpty),
      steps = payload.toJson,
      isBuiltin = workflow.isBuiltin,
      createdAt = createdAt,
      updatedAt = updatedAt,
    )

  private def decodeStorage(
    workflowName: String,
    raw: String,
  ): IO[WorkflowServiceError, (List[TaskStep], Map[TaskStep, String], Option[WorkflowGraph])] =
    raw.fromJson[WorkflowStoragePayload] match
      case Right(payload) =>
        for
          mapped <- decodeStepAgentMap(workflowName, payload.stepAgents)
        yield (payload.steps, mapped, payload.dynamicGraph)
      case Left(_)        =>
        // Backward compatibility with rows stored as raw JSON array of steps.
        ZIO
          .fromEither(
            raw.fromJson[List[TaskStep]].left.map(error =>
              WorkflowServiceError.StepsDecodingFailed(workflowName, error)
            )
          )
          .map(steps => (steps, Map.empty[TaskStep, String], Option.empty[WorkflowGraph]))

  private def decodeStepAgentMap(
    workflowName: String,
    raw: Map[String, String],
  ): IO[WorkflowServiceError, Map[TaskStep, String]] =
    ZIO.foldLeft(raw.toList)(Map.empty[TaskStep, String]) {
      case (acc, (stepRaw, agentRaw)) =>
        TaskStep.values.find(_.toString == stepRaw) match
          case Some(step) => ZIO.succeed(acc.updated(step, agentRaw))
          case None       =>
            ZIO.fail(
              WorkflowServiceError.StepsDecodingFailed(
                workflowName,
                s"Unknown step in stepAgents payload: $stepRaw",
              )
            )
    }
