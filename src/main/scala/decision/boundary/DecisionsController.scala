package decision.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import decision.control.DecisionInbox
import decision.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.DecisionId
import shared.web.DecisionsView

trait DecisionsController:
  def routes: Routes[Any, Response]

object DecisionsController:

  def routes: ZIO[DecisionsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[DecisionsController](_.routes)

  val live: ZLayer[DecisionInbox, Nothing, DecisionsController] =
    ZLayer.fromFunction(make)

  def make(decisionInbox: DecisionInbox): DecisionsController =
    new DecisionsController:
      override val routes: Routes[Any, Response] = Routes(
        Method.GET / "decisions"                              -> handler { (req: Request) =>
          listPage(req, decisionInbox).catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.GET / "decisions" / "fragment"                 -> handler { (req: Request) =>
          listFragment(req, decisionInbox).catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.POST / "decisions" / string("id") / "resolve"  -> handler { (id: String, req: Request) =>
          resolve(id, req, decisionInbox).catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.POST / "decisions" / string("id") / "escalate" -> handler { (id: String, _: Request) =>
          escalate(id, decisionInbox).catchAll(error => ZIO.succeed(persistErr(error)))
        },
      )

  private def listPage(req: Request, decisionInbox: DecisionInbox): IO[PersistenceError, Response] =
    val statusFilter  = req.queryParam("status") match
      case None    => Some("pending")
      case Some(v) => Some(v).map(_.trim).filter(_.nonEmpty)
    val sourceFilter  = req.queryParam("source").map(_.trim).filter(_.nonEmpty)
    val urgencyFilter = req.queryParam("urgency").map(_.trim).filter(_.nonEmpty)
    val query         = req.queryParam("query").map(_.trim).filter(_.nonEmpty)
    for
      items        <- decisionInbox.list(
                        DecisionFilter(
                          statuses = statusFilter.flatMap(parseStatus).map(Set(_)).getOrElse(Set.empty),
                          sourceKind = sourceFilter.flatMap(parseSourceKind),
                          urgency = urgencyFilter.flatMap(parseUrgency),
                          query = query,
                          limit = Int.MaxValue,
                        )
                      )
      pendingCount <- decisionInbox
                        .list(DecisionFilter(statuses = Set(DecisionStatus.Pending), limit = Int.MaxValue))
                        .map(_.size)
    yield htmlResponse(
      DecisionsView.page(items, statusFilter, sourceFilter, urgencyFilter, query, pendingCount)
    )

  private def listFragment(req: Request, decisionInbox: DecisionInbox): IO[PersistenceError, Response] =
    val statusFilter  = req.queryParam("status") match
      case None    => Some("pending")
      case Some(v) => Some(v).map(_.trim).filter(_.nonEmpty)
    val sourceFilter  = req.queryParam("source").map(_.trim).filter(_.nonEmpty)
    val urgencyFilter = req.queryParam("urgency").map(_.trim).filter(_.nonEmpty)
    val query         = req.queryParam("query").map(_.trim).filter(_.nonEmpty)
    decisionInbox
      .list(
        DecisionFilter(
          statuses = statusFilter.flatMap(parseStatus).map(Set(_)).getOrElse(Set.empty),
          sourceKind = sourceFilter.flatMap(parseSourceKind),
          urgency = urgencyFilter.flatMap(parseUrgency),
          query = query,
          limit = Int.MaxValue,
        )
      )
      .map(items => htmlResponse(DecisionsView.cardsFragment(items)))

  private def resolve(id: String, req: Request, decisionInbox: DecisionInbox): IO[PersistenceError, Response] =
    for
      form       <- parseForm(req)
      resolution <- ZIO
                      .fromOption(form.get("resolution").flatMap(_.headOption).flatMap(parseResolutionKind))
                      .orElseFail(PersistenceError.QueryFailed("decision_resolution", "Missing resolution"))
      summary     = form.get("summary").flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)
                      .getOrElse("Resolved from web inbox")
      _          <- decisionInbox.resolve(DecisionId(id), resolution, actor = "web", summary = summary)
    yield redirect("/decisions")

  private def escalate(id: String, decisionInbox: DecisionInbox): IO[PersistenceError, Response] =
    decisionInbox.escalate(DecisionId(id), "Escalated from web inbox").as(redirect("/decisions"))

  private def parseResolutionKind(raw: String): Option[DecisionResolutionKind] =
    raw.trim.toLowerCase match
      case "approved"        => Some(DecisionResolutionKind.Approved)
      case "reworkrequested" => Some(DecisionResolutionKind.ReworkRequested)
      case "acknowledged"    => Some(DecisionResolutionKind.Acknowledged)
      case "escalated"       => Some(DecisionResolutionKind.Escalated)
      case "expired"         => Some(DecisionResolutionKind.Expired)
      case _                 => None

  private def parseStatus(raw: String): Option[DecisionStatus] =
    raw.trim.toLowerCase match
      case "pending"   => Some(DecisionStatus.Pending)
      case "resolved"  => Some(DecisionStatus.Resolved)
      case "escalated" => Some(DecisionStatus.Escalated)
      case "expired"   => Some(DecisionStatus.Expired)
      case _           => None

  private def parseSourceKind(raw: String): Option[DecisionSourceKind] =
    raw.trim.toLowerCase match
      case "issue_review" | "issuereview"         => Some(DecisionSourceKind.IssueReview)
      case "governance"                           => Some(DecisionSourceKind.Governance)
      case "agent_escalation" | "agentescalation" => Some(DecisionSourceKind.AgentEscalation)
      case "manual"                               => Some(DecisionSourceKind.Manual)
      case _                                      => None

  private def parseUrgency(raw: String): Option[DecisionUrgency] =
    raw.trim.toLowerCase match
      case "low"      => Some(DecisionUrgency.Low)
      case "medium"   => Some(DecisionUrgency.Medium)
      case "high"     => Some(DecisionUrgency.High)
      case "critical" => Some(DecisionUrgency.Critical)
      case _          => None

  private def parseForm(req: Request): IO[PersistenceError, Map[String, List[String]]] =
    req.body.asString
      .map(_.split("&").toList.filter(_.nonEmpty))
      .map(_.flatMap { pair =>
        pair.split("=", 2).toList match
          case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
          case key :: Nil          => Some(urlDecode(key) -> "")
          case _                   => None
      }.groupMap(_._1)(_._2))
      .mapError(err => PersistenceError.QueryFailed("parse_decision_form", err.getMessage))

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def redirect(location: String): Response =
    Response(status = Status.SeeOther, headers = Headers(Header.Location(URL.decode(location).getOrElse(URL.root))))

  private def htmlResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Database unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Decision inbox query failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Decision inbox serialization failed: $cause").status(Status.InternalServerError)
