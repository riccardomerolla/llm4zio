package checkpoint.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import checkpoint.control.*
import shared.web.CheckpointsView

object CheckpointsController:

  def routes(service: CheckpointReviewService): Routes[Any, Response] =
    Routes(
      Method.GET / "checkpoints"                                                   -> handler { (req: Request) =>
        listPage(service).catchAll(error => ZIO.succeed(errorResponse(error)))
      },
      Method.GET / "checkpoints" / string("runId")                                 -> handler { (runId: String, req: Request) =>
        detailPage(runId, req, service).catchAll(error => ZIO.succeed(errorResponse(error)))
      },
      Method.GET / "checkpoints" / string("runId") / "snapshots" / string("step")  -> handler {
        (runId: String, step: String, _: Request) =>
          snapshotFragment(runId, step, service).catchAll(error => ZIO.succeed(errorResponse(error)))
      },
      Method.GET / "checkpoints" / string("runId") / "compare"                     -> handler { (runId: String, req: Request) =>
        comparisonFragment(runId, req, service).catchAll(error => ZIO.succeed(errorResponse(error)))
      },
      Method.POST / "checkpoints" / string("runId") / "actions" / string("action") -> handler {
        (runId: String, action: String, req: Request) =>
          handleAction(runId, action, req, service).catchAll(error => ZIO.succeed(errorResponse(error)))
      },
    )

  private def listPage(service: CheckpointReviewService): IO[CheckpointReviewError, Response] =
    service.listActiveRuns.map(items => htmlResponse(CheckpointsView.page(items)))

  private def detailPage(runId: String, req: Request, service: CheckpointReviewService)
    : IO[CheckpointReviewError, Response] =
    val selectedStep = req.queryParam("step").map(_.trim).filter(_.nonEmpty)
    val compareLeft  = req.queryParam("compare_left").map(_.trim).filter(_.nonEmpty)
    val compareRight = req.queryParam("compare_right").map(_.trim).filter(_.nonEmpty)
    val flash        = req.queryParam("flash").map(_.trim).filter(_.nonEmpty)
    service
      .getRunReview(runId, selectedStep, compareLeft, compareRight)
      .map(review => htmlResponse(CheckpointsView.detailPage(review, selectedStep, compareLeft, compareRight, flash)))

  private def snapshotFragment(
    runId: String,
    step: String,
    service: CheckpointReviewService,
  ): IO[CheckpointReviewError, Response] =
    service.getSnapshotReview(runId, step).flatMap {
      case Some(review) => ZIO.succeed(htmlResponse(CheckpointsView.snapshotFragment(runId, review)))
      case None         => ZIO.fail(CheckpointReviewError.NotFound(runId))
    }

  private def comparisonFragment(
    runId: String,
    req: Request,
    service: CheckpointReviewService,
  ): IO[CheckpointReviewError, Response] =
    val left  = req.queryParam("compare_left").map(_.trim).filter(_.nonEmpty)
    val right = req.queryParam("compare_right").map(_.trim).filter(_.nonEmpty)
    (left, right) match
      case (Some(leftStep), Some(rightStep)) =>
        service.compare(runId, leftStep, rightStep).map(result =>
          htmlResponse(CheckpointsView.comparisonFragment(result))
        )
      case _                                 =>
        ZIO.succeed(htmlResponse(CheckpointsView.comparisonFragment(None)))

  private def handleAction(
    runId: String,
    rawAction: String,
    req: Request,
    service: CheckpointReviewService,
  ): IO[CheckpointReviewError, Response] =
    for
      action <- ZIO
                  .fromOption(parseAction(rawAction))
                  .orElseFail(CheckpointReviewError.InvalidAction(runId, rawAction, "Unknown action"))
      form   <- parseForm(req)
      note    = form.get("note").flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)
      result <- service.act(runId, action, note)
    yield redirect(s"/checkpoints/$runId?flash=${encode(result.summary)}")

  private def parseAction(raw: String): Option[CheckpointOperatorAction] =
    raw.trim.toLowerCase match
      case "approve-continue" => Some(CheckpointOperatorAction.ApproveContinue)
      case "redirect"         => Some(CheckpointOperatorAction.Redirect)
      case "pause"            => Some(CheckpointOperatorAction.Pause)
      case "abort"            => Some(CheckpointOperatorAction.Abort)
      case "flag-full-review" => Some(CheckpointOperatorAction.FlagFullReview)
      case _                  => None

  private def parseForm(req: Request): IO[CheckpointReviewError, Map[String, List[String]]] =
    req.body.asString
      .map(_.split("&").toList.filter(_.nonEmpty))
      .map(_.flatMap { pair =>
        pair.split("=", 2).toList match
          case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
          case key :: Nil          => Some(urlDecode(key) -> "")
          case _                   => None
      }.groupMap(_._1)(_._2))
      .mapError(err => CheckpointReviewError.Persistence(err.getMessage))

  private def redirect(location: String): Response =
    Response(status = Status.SeeOther, headers = Headers(Header.Location(URL.decode(location).getOrElse(URL.root))))

  private def htmlResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def errorResponse(error: CheckpointReviewError): Response =
    error match
      case CheckpointReviewError.NotFound(runId)             =>
        Response.text(s"Checkpoint run not found: $runId").status(Status.NotFound)
      case CheckpointReviewError.InvalidAction(_, _, reason) =>
        Response.text(reason).status(Status.BadRequest)
      case CheckpointReviewError.Persistence(message)        =>
        Response.text(s"Checkpoint query failed: $message").status(Status.BadRequest)
      case CheckpointReviewError.State(message)              =>
        Response.text(s"Checkpoint state failed: $message").status(Status.BadRequest)
      case CheckpointReviewError.Control(message)            =>
        Response.text(s"Checkpoint control failed: $message").status(Status.BadRequest)
      case CheckpointReviewError.Workspace(message)          =>
        Response.text(s"Checkpoint workspace action failed: $message").status(Status.BadRequest)
      case CheckpointReviewError.Git(message)                =>
        Response.text(s"Checkpoint git diff failed: $message").status(Status.BadRequest)

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def encode(value: String): String =
    java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
