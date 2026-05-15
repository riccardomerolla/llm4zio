package decision.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import decision.control.DecisionInbox
import decision.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.DecisionId

/** Phase 3 R9: HTTP routes for the supervisor inbox + the
  * `/decisions/{id}/resolve` POST already referenced by HTMX forms in
  * DecisionsView. Channel-agnostic mirror of Telegram's inline-button
  * resolution flow — the supervisor sees the same Decision + QuickOption[]
  * payload, just rendered in HTML.
  */
trait DecisionsController:
  def routes: Routes[Any, Response]

object DecisionsController:

  def routes: ZIO[DecisionsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[DecisionsController](_.routes)

  val live: ZLayer[DecisionInbox, Nothing, DecisionsController] =
    ZLayer.fromFunction(DecisionsControllerLive.apply)

final case class DecisionsControllerLive(decisionInbox: DecisionInbox) extends DecisionsController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "decisions" / "inbox"                       -> handler {
      list.catchAll(err => ZIO.succeed(persistErr(err)))
    },
    Method.POST / "decisions" / string("id") / "resolve"     -> handler { (id: String, req: Request) =>
      resolve(id, req).catchAll(err => ZIO.succeed(persistErr(err)))
    },
    Method.POST / "decisions" / string("id") / "escalate"    -> handler { (id: String, req: Request) =>
      escalate(id, req).catchAll(err => ZIO.succeed(persistErr(err)))
    },
  )

  private def list: IO[PersistenceError, Response] =
    for
      pending    <- decisionInbox.list(DecisionFilter(statuses = Set(DecisionStatus.Pending), limit = Int.MaxValue))
      escalated  <- decisionInbox.list(DecisionFilter(statuses = Set(DecisionStatus.Escalated), limit = Int.MaxValue))
      decisions   = (pending ++ escalated).distinctBy(_.id.value)
    yield html(DecisionsView.inboxPage(decisions))

  private def resolve(id: String, req: Request): IO[PersistenceError, Response] =
    for
      form     <- parseForm(req)
      decisionId = DecisionId(id)
      decision <- decisionInbox.get(decisionId)
      key       = form.getOrElse("key", "").trim
      actor     = form.getOrElse("actor", "web-supervisor").trim
      option   <- ZIO
                    .fromOption(QuickOption.findByKey(decision.renderableQuickOptions, key))
                    .orElseFail(
                      PersistenceError.QueryFailed("decision_resolve", s"unknown option key '$key'")
                    )
      _        <- decisionInbox.resolve(
                    id = decisionId,
                    resolutionKind = option.resolution,
                    actor = actor,
                    summary = option.rationale,
                  )
      // Re-render the inbox after resolving
      pending   <- decisionInbox.list(DecisionFilter(statuses = Set(DecisionStatus.Pending), limit = Int.MaxValue))
      escalated <- decisionInbox.list(DecisionFilter(statuses = Set(DecisionStatus.Escalated), limit = Int.MaxValue))
      decisions  = (pending ++ escalated).distinctBy(_.id.value)
    yield html(
      DecisionsView.inboxPage(
        decisions,
        flash = Some(s"Decision ${decisionId.value} resolved as ${option.resolution}."),
      )
    )

  private def escalate(id: String, req: Request): IO[PersistenceError, Response] =
    for
      form      <- parseForm(req)
      reason     = form.getOrElse("reason", "Escalated from supervisor inbox").trim
      _         <- decisionInbox.escalate(DecisionId(id), reason)
      pending   <- decisionInbox.list(DecisionFilter(statuses = Set(DecisionStatus.Pending), limit = Int.MaxValue))
      escalated <- decisionInbox.list(DecisionFilter(statuses = Set(DecisionStatus.Escalated), limit = Int.MaxValue))
      decisions  = (pending ++ escalated).distinctBy(_.id.value)
    yield html(DecisionsView.inboxPage(decisions, flash = Some(s"Decision $id escalated.")))

  private def parseForm(req: Request): IO[PersistenceError, Map[String, String]] =
    req.body.asString
      .mapError(err => PersistenceError.QueryFailed("decisions_form", err.toString))
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap(_.split("=", 2) match
            case Array(k, v) => Some(decode(k) -> decode(v))
            case Array(k)    => Some(decode(k) -> "")
            case _           => None
          )
          .toMap
      }

  private def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def html(body: String): Response =
    Response.text(body).contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Decision inbox unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Decision action failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Decision serialization failed: $cause").status(Status.InternalServerError)
