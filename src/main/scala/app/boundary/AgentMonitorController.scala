package app.boundary

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.observability.LlmMetrics
import orchestration.entity.AgentMonitorService
import shared.errors.ControlPlaneError
import shared.web.AgentMonitorView

trait AgentMonitorController:
  def routes: Routes[Any, Response]

object AgentMonitorController:

  def routes: ZIO[AgentMonitorController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[AgentMonitorController](_.routes)

  val live: ZLayer[AgentMonitorService & LlmMetrics, Nothing, AgentMonitorController] =
    ZLayer.fromFunction(AgentMonitorControllerLive.apply)

final case class AgentMonitorControllerLive(
  controlPlane: AgentMonitorService,
  metrics: LlmMetrics,
) extends AgentMonitorController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "agent-monitor"                                                 -> handler {
      ZIO.succeed(
        Response(
          status = Status.MovedPermanently,
          headers = Headers(Header.Location(URL.decode("/").getOrElse(URL.root))),
        )
      )
    },
    Method.GET / "agent-monitor" / "stream"                                      -> handler {
      val stream: ZStream[Any, Nothing, String] =
        ZStream
          .repeatZIOWithSchedule(
            controlPlane.getAgentMonitorSnapshot.either,
            Schedule.spaced(2.seconds),
          )
          .collect { case Right(snapshot) => snapshot }
          .mapZIO { snapshot =>
            metrics.snapshot.map { llmMetrics =>
              val rows      = AgentMonitorView.fromSnapshot(snapshot)
              val stats     = AgentMonitorView.AgentGlobalStats.fromSnapshotAndMetrics(snapshot, llmMetrics)
              val tableHtml = AgentMonitorView.table(rows)
              val statsHtml = AgentMonitorView.statsHeader(stats)
              s"event: agent-stats\ndata: $statsHtml\n\nevent: agent-table\ndata: $tableHtml\n\n"
            }
          }
      ZIO.succeed(
        Response(
          status = Status.Ok,
          headers = Headers(
            Header.ContentType(MediaType.text.`event-stream`),
            Header.CacheControl.NoCache,
            Header.Custom("Connection", "keep-alive"),
          ),
          body = Body.fromCharSequenceStreamChunked(stream),
        )
      )
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
