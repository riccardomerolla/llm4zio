package web.controllers

import java.net.{ URLDecoder, URLEncoder }
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*
import zio.json.*

import agents.AgentRegistry
import db.{ PersistenceError, TaskRepository }
import models.{ AgentInfo, TaskStep, WorkflowDefinition, WorkflowStepAgent, WorkflowValidator }
import orchestration.{ WorkflowService, WorkflowServiceError }
import web.views.HtmlViews

trait WorkflowsController:
  def routes: Routes[Any, Response]

object WorkflowsController:

  def routes: ZIO[WorkflowsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[WorkflowsController](_.routes)

  val live: ZLayer[WorkflowService & TaskRepository, Nothing, WorkflowsController] =
    ZLayer.fromFunction(WorkflowsControllerLive.apply)

final case class WorkflowsControllerLive(
  service: WorkflowService,
  repository: TaskRepository,
) extends WorkflowsController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "workflows"                       -> handler { (req: Request) =>
      handle {
        for
          existing <- service.listWorkflows
          workflows = withBuiltInDefault(existing)
          agents   <- availableAgents
          flash     = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
        yield html(HtmlViews.workflowsList(workflows, agents, flash))
      }
    },
    Method.GET / "workflows" / "new"               -> handler { (_: Request) =>
      handle {
        for
          agents <- availableAgents
        yield html(
          HtmlViews.workflowForm(
            title = "Create Workflow",
            action = "/workflows",
            workflow = WorkflowDefinition(
              name = "",
              steps = Nil,
              stepAgents = Nil,
              isBuiltin = false,
            ),
            availableAgents = agents,
          )
        )
      }
    },
    Method.GET / "workflows" / long("id")          -> handler { (id: Long, _: Request) =>
      handle {
        for
          workflow <- service
                        .getWorkflow(id)
                        .someOrFail(WorkflowServiceError.PersistenceFailed(PersistenceError.NotFound("workflows", id)))
        yield html(HtmlViews.workflowDetail(workflow))
      }
    },
    Method.GET / "workflows" / long("id") / "edit" -> handler { (id: Long, req: Request) =>
      handle {
        for
          workflow <- service
                        .getWorkflow(id)
                        .someOrFail(WorkflowServiceError.PersistenceFailed(PersistenceError.NotFound("workflows", id)))
          _        <- {
            if workflow.isBuiltin then
              ZIO.fail(
                WorkflowServiceError.ValidationFailed(
                  List("Built-in workflows cannot be edited")
                )
              )
            else ZIO.unit
          }
          agents   <- availableAgents
          flash     = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
        yield html(
          HtmlViews.workflowForm(
            title = s"Edit Workflow: ${workflow.name}",
            action = s"/workflows/$id",
            workflow = workflow,
            availableAgents = agents,
            flash = flash,
          )
        )
      }
    },
    Method.POST / "workflows"                      -> handler { (req: Request) =>
      handle {
        for
          form       <- parseForm(req)
          name       <- required(form, "name")
          description = optional(form, "description")
          steps      <- parseOrderedSteps(form)
          stepAgents <- parseStepAgents(form, steps)
          workflow    = WorkflowDefinition(
                          name = name,
                          description = description,
                          steps = steps,
                          stepAgents = stepAgents,
                          isBuiltin = false,
                        )
          _          <- validateForForm(workflow)
          _          <- service.createWorkflow(workflow)
        yield redirectToList("Workflow created")
      }
    },
    Method.POST / "workflows" / long("id")         -> handler { (id: Long, req: Request) =>
      handle {
        for
          existing   <- service
                          .getWorkflow(id)
                          .someOrFail(WorkflowServiceError.PersistenceFailed(PersistenceError.NotFound("workflows", id)))
          _          <- {
            if existing.isBuiltin then
              ZIO.fail(WorkflowServiceError.ValidationFailed(List("Built-in workflows cannot be edited")))
            else ZIO.unit
          }
          form       <- parseForm(req)
          name       <- required(form, "name")
          steps      <- parseOrderedSteps(form)
          stepAgents <- parseStepAgents(form, steps)
          workflow    = existing.copy(
                          name = name,
                          description = optional(form, "description"),
                          steps = steps,
                          stepAgents = stepAgents,
                        )
          _          <- validateForForm(workflow)
          _          <- service.updateWorkflow(workflow)
        yield redirectToList("Workflow updated")
      }
    },
    Method.DELETE / "workflows" / long("id")       -> handler { (id: Long, _: Request) =>
      handle {
        for
          existing <- service
                        .getWorkflow(id)
                        .someOrFail(WorkflowServiceError.PersistenceFailed(PersistenceError.NotFound("workflows", id)))
          _        <- {
            if existing.isBuiltin then
              ZIO.fail(WorkflowServiceError.ValidationFailed(List("Built-in workflows cannot be deleted")))
            else ZIO.unit
          }
          _        <- service.deleteWorkflow(id)
        yield Response.status(Status.NoContent)
      }
    },
  )

  private def withBuiltInDefault(workflows: List[WorkflowDefinition]): List[WorkflowDefinition] =
    val hasDefault = workflows.exists(_.name.equalsIgnoreCase(WorkflowDefinition.default.name))
    val all        = if hasDefault then workflows else WorkflowDefinition.default :: workflows
    all.sortBy(w => (!w.isBuiltin, w.name.toLowerCase))

  private def parseOrderedSteps(form: Map[String, String]): IO[WorkflowServiceError, List[TaskStep]] =
    val raw = form.getOrElse("orderedSteps", "")
    if raw.trim.isEmpty then
      ZIO.succeed(
        TaskStep.values.toList.filter(step =>
          form.get(s"step.${step.toString}").exists(_.trim.nonEmpty)
        )
      )
    else
      ZIO.foreach(raw.split(",").toList.map(_.trim).filter(_.nonEmpty)) { value =>
        parseStep(value)
      }

  private def parseStepAgents(
    form: Map[String, String],
    selectedSteps: List[TaskStep],
  ): IO[WorkflowServiceError, List[WorkflowStepAgent]] =
    for
      fromJson   <- parseStepAgentsJson(form.get("stepAgentsJson").map(_.trim).filter(_.nonEmpty))
      fromFields  = TaskStep.values.toList.flatMap { step =>
                      form.get(s"agent.${step.toString}").map(_.trim).filter(_.nonEmpty).map(step -> _)
                    }.toMap
      merged      = fromJson ++ fromFields
      selectedSet = selectedSteps.toSet
    yield TaskStep.values.toList.flatMap { step =>
      if selectedSet.contains(step) then merged.get(step).map(agent => WorkflowStepAgent(step, agent)) else None
    }

  private def parseStepAgentsJson(
    raw: Option[String]
  ): IO[WorkflowServiceError, Map[TaskStep, String]] =
    raw match
      case None      => ZIO.succeed(Map.empty)
      case Some(txt) =>
        ZIO
          .fromEither(txt.fromJson[Map[String, String]].left.map(error =>
            WorkflowServiceError.ValidationFailed(List(s"Invalid step agents payload: $error"))
          ))
          .flatMap { values =>
            ZIO.foldLeft(values.toList)(Map.empty[TaskStep, String]) {
              case (acc, (stepRaw, agentRaw)) =>
                parseStep(stepRaw).map { step =>
                  val trimmed = agentRaw.trim
                  if trimmed.nonEmpty then acc.updated(step, trimmed) else acc
                }
            }
          }

  private def parseStep(raw: String): IO[WorkflowServiceError, TaskStep] =
    TaskStep.values.find(_.toString == raw) match
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(WorkflowServiceError.ValidationFailed(List(s"Unknown migration step: $raw")))

  private def validateForForm(workflow: WorkflowDefinition): IO[WorkflowServiceError, WorkflowDefinition] =
    WorkflowValidator.validate(workflow) match
      case Right(valid) => ZIO.succeed(valid)
      case Left(errs)   => ZIO.fail(WorkflowServiceError.ValidationFailed(errs))

  private def parseForm(req: Request): IO[WorkflowServiceError, Map[String, String]] =
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
      .mapError(err => WorkflowServiceError.ValidationFailed(List(s"Invalid form payload: ${err.getMessage}")))

  private def required(form: Map[String, String], field: String): IO[WorkflowServiceError, String] =
    ZIO
      .fromOption(form.get(field).map(_.trim).filter(_.nonEmpty))
      .orElseFail(WorkflowServiceError.ValidationFailed(List(s"Missing field '$field'")))

  private def optional(form: Map[String, String], field: String): Option[String] =
    form.get(field).map(_.trim).filter(_.nonEmpty)

  private def availableAgents: IO[WorkflowServiceError, List[AgentInfo]] =
    repository
      .listCustomAgents
      .map(AgentRegistry.allAgents)
      .map(_.filter(_.usesAI))
      .map(_.sortBy(_.displayName.toLowerCase))
      .mapError(WorkflowServiceError.PersistenceFailed.apply)

  private def handle(effect: IO[WorkflowServiceError, Response]): UIO[Response] =
    effect.catchAll(err => ZIO.succeed(mapError(err)))

  private def mapError(error: WorkflowServiceError): Response =
    error match
      case WorkflowServiceError.ValidationFailed(errors)                  =>
        Response.text(errors.mkString("; ")).status(Status.BadRequest)
      case WorkflowServiceError.StepsDecodingFailed(workflowName, reason) =>
        Response.text(s"Invalid stored workflow '$workflowName': $reason").status(Status.InternalServerError)
      case WorkflowServiceError.PersistenceFailed(persistence)            =>
        persistence match
          case PersistenceError.NotFound(entity, id)    =>
            Response.text(s"$entity with id $id not found").status(Status.NotFound)
          case PersistenceError.ConnectionFailed(cause) =>
            Response.text(s"Database unavailable: $cause").status(Status.ServiceUnavailable)
          case PersistenceError.QueryFailed(_, cause)   =>
            Response.text(s"Database query failed: $cause").status(Status.InternalServerError)
          case PersistenceError.SchemaInitFailed(cause) =>
            Response.text(s"Database initialization failed: $cause").status(Status.InternalServerError)

  private def redirectToList(flash: String): Response =
    Response(
      status = Status.SeeOther,
      headers = Headers(Header.Custom("Location", s"/workflows?flash=${urlEncode(flash)}")),
    )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
