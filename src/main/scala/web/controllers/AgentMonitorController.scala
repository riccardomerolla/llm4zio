package web.controllers

import zio.*
import zio.http.*
import zio.json.*

import models.ControlPlaneError
import orchestration.OrchestratorControlPlane
import web.views.AgentMonitor

trait AgentMonitorController:
  def routes: Routes[Any, Response]

object AgentMonitorController:

  def routes: ZIO[AgentMonitorController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[AgentMonitorController](_.routes)

  val live: ZLayer[OrchestratorControlPlane, Nothing, AgentMonitorController] =
    ZLayer.fromFunction(AgentMonitorControllerLive.apply)

final case class AgentMonitorControllerLive(
  controlPlane: OrchestratorControlPlane
) extends AgentMonitorController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "agent-monitor"                                                 -> handler {
      ZIO.succeed(html(AgentMonitor.page))
    },
    Method.GET / "api" / "agent-monitor" / "snapshot"                            -> handler {
      controlPlane
        .getAgentMonitorSnapshot
        .map(snapshot => Response.json(snapshot.toJson))
        .catchAll(err => ZIO.succeed(controlPlaneErrorResponse(err)))
    },
    Method.GET / "api" / "agent-monitor" / "history"                             -> handler { (req: Request) =>
      val limit = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(120)
      controlPlane
        .getAgentExecutionHistory(limit)
        .map(history => Response.json(history.toJson))
        .catchAll(err => ZIO.succeed(controlPlaneErrorResponse(err)))
    },
    Method.POST / "api" / "agent-monitor" / "agents" / string("name") / "pause"  -> handler {
      (name: String, _: Request) =>
        controlPlane
          .pauseAgentExecution(name)
          .map(_ => Response.json(Map("ok" -> "true", "agent" -> name, "action" -> "pause").toJson))
          .catchAll(err => ZIO.succeed(controlPlaneErrorResponse(err)))
    },
    Method.POST / "api" / "agent-monitor" / "agents" / string("name") / "resume" -> handler {
      (name: String, _: Request) =>
        controlPlane
          .resumeAgentExecution(name)
          .map(_ => Response.json(Map("ok" -> "true", "agent" -> name, "action" -> "resume").toJson))
          .catchAll(err => ZIO.succeed(controlPlaneErrorResponse(err)))
    },
    Method.POST / "api" / "agent-monitor" / "agents" / string("name") / "abort"  -> handler {
      (name: String, _: Request) =>
        controlPlane
          .abortAgentExecution(name)
          .map(_ => Response.json(Map("ok" -> "true", "agent" -> name, "action" -> "abort").toJson))
          .catchAll(err => ZIO.succeed(controlPlaneErrorResponse(err)))
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def controlPlaneErrorResponse(error: ControlPlaneError): Response =
    error match
      case ControlPlaneError.ActiveRunNotFound(runId)                   =>
        Response.text(s"Run not found: $runId").status(Status.NotFound)
      case ControlPlaneError.WorkflowRoutingFailed(step, reason)        =>
        Response.text(s"Workflow routing failed for $step: $reason").status(Status.BadRequest)
      case ControlPlaneError.ResourceAllocationFailed(runId, reason)    =>
        Response.text(s"Resource allocation failed for $runId: $reason").status(Status.TooManyRequests)
      case ControlPlaneError.EventBroadcastFailed(reason)               =>
        Response.text(s"Event broadcast failed: $reason").status(Status.InternalServerError)
      case ControlPlaneError.InvalidWorkflowTransition(runId, from, to) =>
        Response.text(s"Invalid workflow transition for $runId: $from -> $to").status(Status.BadRequest)
      case ControlPlaneError.AgentCapabilityMismatch(step, agent)       =>
        Response.text(s"Agent capability mismatch for $step: $agent").status(Status.BadRequest)
