package web.controllers

import java.net.{ URLDecoder, URLEncoder }
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream

import db.*
import models.{ WorkflowDefinition, WorkflowRunState }
import orchestration.{ OrchestratorControlPlane, TaskExecutor, WorkflowService, WorkflowServiceError }
import web.views.{ TaskListItem, TasksView }

trait TasksController:
  def routes: Routes[Any, Response]

object TasksController:

  def routes: ZIO[TasksController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[TasksController](_.routes)

  val live
    : ZLayer[TaskRepository & WorkflowService & TaskExecutor & OrchestratorControlPlane, Nothing, TasksController] =
    ZLayer.fromFunction(TasksControllerLive.apply)

final case class TasksControllerLive(
  repository: TaskRepository,
  workflowService: WorkflowService,
  taskExecutor: TaskExecutor,
  controlPlane: OrchestratorControlPlane,
) extends TasksController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "tasks"                                   -> handler { (req: Request) =>
      handle {
        for
          runs      <- repository.listRuns(offset = 0, limit = 50)
          workflows <- workflowService.listWorkflows.mapError(workflowAsPersistence("listWorkflows"))
          taskItems <- toTaskListItems(runs, workflows)
          flash      = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
        yield html(TasksView.tasksList(taskItems, workflows, flash))
      }
    },
    Method.GET / "tasks" / "new"                           -> handler {
      ZIO.succeed(redirect("/tasks"))
    },
    Method.GET / "tasks" / long("id")                      -> handler { (id: Long, req: Request) =>
      handle {
        for
          run      <- repository.getRun(id).someOrFail(PersistenceError.NotFound("task_runs", id))
          workflow <- run.workflowId match
                        case Some(wid) =>
                          workflowService.getWorkflow(wid).mapError(workflowAsPersistence("getWorkflow"))
                        case None      => ZIO.none
          task     <- toTaskItem(run, workflow)
          flash     = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
        yield html(TasksView.taskDetail(task, flash))
      }
    },
    Method.GET / "api" / "tasks" / long("id") / "progress" -> handler { (id: Long, _: Request) =>
      handle {
        for
          stream <- progressStream(id)
        yield Response(
          status = Status.Ok,
          headers = Headers(
            Header.ContentType(MediaType.text.`event-stream`),
            Header.CacheControl.NoCache,
            Header.Custom("Connection", "keep-alive"),
          ),
          body = Body.fromCharSequenceStreamChunked(stream),
        )
      }
    },
    Method.POST / "tasks"                                  -> handler { (req: Request) =>
      handle {
        for
          form          <- parseForm(req)
          taskName      <- required(form, "name")
          description    = form.get("description").map(_.trim).filter(_.nonEmpty)
          workflowIdRaw <- required(form, "workflowId")
          workflowId    <- parseLongField("workflowId", workflowIdRaw)
          workflow      <- workflowService
                             .getWorkflow(workflowId)
                             .mapError(workflowAsPersistence("getWorkflow"))
                             .someOrFail(PersistenceError.NotFound("workflows", workflowId))
          _             <- ensureWorkflowHasSteps(workflow)
          now           <- Clock.instant
          runId         <- repository.createRun(
                             TaskRunRow(
                               id = 0L,
                               sourceDir = "",
                               outputDir = "",
                               status = RunStatus.Pending,
                               startedAt = now,
                               completedAt = None,
                               totalFiles = 0,
                               processedFiles = 0,
                               successfulConversions = 0,
                               failedConversions = 0,
                               currentPhase = workflow.steps.headOption,
                               errorMessage = None,
                               workflowId = Some(workflowId),
                             )
                           )
          _             <- ZIO.foreachDiscard(description) { value =>
                             repository.saveArtifact(
                               TaskArtifactRow(
                                 id = 0L,
                                 taskRunId = runId,
                                 stepName = "task",
                                 key = "task.description",
                                 value = value,
                                 createdAt = now,
                               )
                             )
                           }
          _             <- repository.saveArtifact(
                             TaskArtifactRow(
                               id = 0L,
                               taskRunId = runId,
                               stepName = "task",
                               key = "task.name",
                               value = taskName,
                               createdAt = now,
                             )
                           )
          _             <- repository.saveArtifact(
                             TaskArtifactRow(
                               id = 0L,
                               taskRunId = runId,
                               stepName = "task",
                               key = "task.steps",
                               value = workflow.steps.toJson,
                               createdAt = now,
                             )
                           )
          _             <- taskExecutor.start(runId, workflow)
        yield redirect(s"/tasks?flash=${urlEncode("Task created")}")
      }
    },
    Method.POST / "tasks" / long("id") / "cancel"          -> handler { (id: Long, _: Request) =>
      handle {
        for
          run <- repository.getRun(id).someOrFail(PersistenceError.NotFound("task_runs", id))
          now <- Clock.instant
          _   <- repository.updateRun(
                   run.copy(
                     status = RunStatus.Cancelled,
                     completedAt = Some(now),
                     errorMessage = Some("Cancelled by user"),
                   )
                 )
          _   <- controlPlane.updateRunState(id.toString, WorkflowRunState.Cancelled).ignore
          _   <- taskExecutor.cancel(id)
        yield redirect(s"/tasks/$id?flash=${urlEncode("Task cancelled")}")
      }
    },
    Method.POST / "tasks" / long("id") / "retry"           -> handler { (id: Long, _: Request) =>
      handle {
        for
          run        <- repository.getRun(id).someOrFail(PersistenceError.NotFound("task_runs", id))
          _          <- ZIO
                          .fail(PersistenceError.QueryFailed("retryTask", "Retry is available only for failed tasks"))
                          .when(run.status != RunStatus.Failed)
          workflowId <- ZIO
                          .fromOption(run.workflowId)
                          .orElseFail(PersistenceError.QueryFailed("retryTask", "Task has no workflow"))
          workflow   <- workflowService
                          .getWorkflow(workflowId)
                          .mapError(workflowAsPersistence("getWorkflow"))
                          .someOrFail(PersistenceError.NotFound("workflows", workflowId))
          _          <- ensureWorkflowHasSteps(workflow)
          now        <- Clock.instant
          _          <- repository.updateRun(
                          run.copy(
                            status = RunStatus.Pending,
                            currentPhase = workflow.steps.headOption,
                            completedAt = None,
                            errorMessage = None,
                            startedAt = now,
                          )
                        )
          _          <- taskExecutor.start(id, workflow)
        yield redirect(s"/tasks/$id?flash=${urlEncode("Task queued for retry")}")
      }
    },
  )

  private def toTaskListItems(
    runs: List[TaskRunRow],
    workflows: List[WorkflowDefinition],
  ): IO[PersistenceError, List[TaskListItem]] =
    val workflowsById = workflows.flatMap(w => w.id.map(_ -> w)).toMap
    ZIO.foreach(runs) { run =>
      toTaskItem(run, run.workflowId.flatMap(workflowsById.get))
    }

  private def toTaskItem(
    run: TaskRunRow,
    workflow: Option[WorkflowDefinition],
  ): IO[PersistenceError, TaskListItem] =
    for
      artifacts <- repository.getArtifactsByTask(run.id)
      taskName   = artifacts.find(_.key == "task.name").map(_.value).filter(_.trim.nonEmpty)
                     .orElse(workflow.map(_.name))
                     .getOrElse(s"Task #${run.id}")
      steps      = workflow.map(_.steps).getOrElse(Nil)
    yield TaskListItem(
      run = run,
      name = taskName,
      steps = steps,
      workflowName = workflow.map(_.name),
    )

  private case class ProgressSnapshot(html: String, terminal: Boolean)

  private def progressStream(taskId: Long): IO[PersistenceError, ZStream[Any, Nothing, String]] =
    renderProgressSnapshot(taskId).map { initial =>
      val runId   = taskId.toString
      val updates = ZStream.scoped {
        for queue <- controlPlane.subscribeToEvents(runId)
        yield ZStream.fromQueue(queue)
      }.flatten
        .mapZIO(_ => renderProgressSnapshot(taskId).orElseSucceed(initial))

      (ZStream.succeed(initial) ++ updates)
        .takeUntil(_.terminal)
        .map(snapshot => toSse("step-progress", snapshot.html))
    }

  private def renderProgressSnapshot(taskId: Long): IO[PersistenceError, ProgressSnapshot] =
    for
      run      <- repository.getRun(taskId).someOrFail(PersistenceError.NotFound("task_runs", taskId))
      workflow <- run.workflowId match
                    case Some(wid) =>
                      workflowService.getWorkflow(wid).mapError(workflowAsPersistence("getWorkflow"))
                    case None      => ZIO.none
      task     <- toTaskItem(run, workflow)
    yield ProgressSnapshot(
      html = TasksView.taskProgressContent(task).toString,
      terminal =
        run.status == RunStatus.Completed || run.status == RunStatus.Failed || run.status == RunStatus.Cancelled,
    )

  private def toSse(eventType: String, payload: String): String =
    val dataLines = payload.linesIterator.map(line => s"data: $line").mkString("\n")
    s"event: $eventType\n$dataLines\n\n"

  private def ensureWorkflowHasSteps(workflow: WorkflowDefinition): IO[PersistenceError, Unit] =
    if workflow.steps.nonEmpty then ZIO.unit
    else ZIO.fail(PersistenceError.QueryFailed("createTask", "Selected workflow has no steps"))

  private def parseLongField(field: String, raw: String): IO[PersistenceError, Long] =
    ZIO
      .attempt(raw.trim.toLong)
      .mapError(_ => PersistenceError.QueryFailed("parseForm", s"Invalid numeric field '$field': $raw"))

  private def parseForm(req: Request): IO[PersistenceError, Map[String, String]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap {
            _.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .toMap
      }
      .mapError(err => PersistenceError.QueryFailed("parseForm", err.getMessage))

  private def required(form: Map[String, String], field: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(form.get(field).map(_.trim).filter(_.nonEmpty))
      .orElseFail(PersistenceError.QueryFailed("parseForm", s"Missing field '$field'"))

  private def handle(effect: IO[PersistenceError, Response]): UIO[Response] =
    effect.catchAll(err => ZIO.succeed(mapPersistenceError(err)))

  private def mapPersistenceError(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, id)    =>
        Response.text(s"$entity with id $id not found").status(Status.NotFound)
      case PersistenceError.ConnectionFailed(cause) =>
        Response.text(s"Database unavailable: $cause").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)   =>
        Response.text(s"Request failed: $cause").status(Status.BadRequest)
      case PersistenceError.SchemaInitFailed(cause) =>
        Response.text(s"Database initialization failed: $cause").status(Status.InternalServerError)

  private def workflowAsPersistence(action: String)(error: WorkflowServiceError): PersistenceError =
    error match
      case WorkflowServiceError.PersistenceFailed(err)             => err
      case WorkflowServiceError.ValidationFailed(errors)           =>
        PersistenceError.QueryFailed(action, errors.mkString("; "))
      case WorkflowServiceError.StepsDecodingFailed(workflow, why) =>
        PersistenceError.QueryFailed(action, s"Invalid workflow '$workflow': $why")

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def html(bodyContent: String): Response =
    Response.text(bodyContent).contentType(MediaType.text.html)

  private def redirect(path: String): Response =
    Response(
      status = Status.SeeOther,
      headers = Headers(Header.Custom("Location", path)),
    )
