package activity.boundary

import java.time.Instant

import zio.*
import zio.http.*

import activity.entity.{ ActivityEventType, ActivityRepository }
import shared.web.{ ActivityView, ErrorHandlingMiddleware }

trait ActivityController:
  def routes: Routes[Any, Response]

object ActivityController:

  def routes: ZIO[ActivityController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[ActivityController](_.routes)

  val live: ZLayer[ActivityRepository, Nothing, ActivityController] =
    ZLayer.fromFunction(ActivityControllerLive.apply)

final case class ActivityControllerLive(
  activityRepository: ActivityRepository
) extends ActivityController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "activity"                                    -> handler {
      ZIO.succeed(
        Response(
          status = Status.MovedPermanently,
          headers = Headers(Header.Location(URL.decode("/").getOrElse(URL.root))),
        )
      )
    },
    Method.GET / "api" / "activity" / "events"                 -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val eventType = req.queryParam("type").flatMap(parseEventType)
        val since     = req.queryParam("since").flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
        val limit     = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(50)
        activityRepository.listEvents(eventType, since, limit).map { events =>
          htmlResponse(ActivityView.eventsFragment(events))
        }
      }
    },
    Method.GET / "api" / "activity" / "events" / "latest-card" -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        activityRepository.listEvents(limit = 1).map {
          case event :: _ => htmlResponse(ActivityView.singleEventFragment(event))
          case Nil        => htmlResponse("")
        }
      }
    },
  )

  private def htmlResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def parseEventType(raw: String): Option[ActivityEventType] = raw.toLowerCase match
    case "run_started"        => Some(ActivityEventType.RunStarted)
    case "run_completed"      => Some(ActivityEventType.RunCompleted)
    case "run_failed"         => Some(ActivityEventType.RunFailed)
    case "run_state_changed"  => Some(ActivityEventType.RunStateChanged)
    case "agent_assigned"     => Some(ActivityEventType.AgentAssigned)
    case "message_sent"       => Some(ActivityEventType.MessageSent)
    case "config_changed"     => Some(ActivityEventType.ConfigChanged)
    case "analysis_started"   => Some(ActivityEventType.AnalysisStarted)
    case "analysis_completed" => Some(ActivityEventType.AnalysisCompleted)
    case "analysis_failed"    => Some(ActivityEventType.AnalysisFailed)
    case _                    => None
