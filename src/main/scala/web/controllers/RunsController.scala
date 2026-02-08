package web.controllers

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*

import db.*
import models.*
import orchestration.MigrationOrchestrator
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait RunsController:
  def routes: Routes[Any, Response]

object RunsController:

  def routes: ZIO[RunsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[RunsController](_.routes)

  val live: ZLayer[MigrationOrchestrator & MigrationRepository, Nothing, RunsController] =
    ZLayer.fromFunction(RunsControllerLive.apply)

  def toSseData(update: ProgressUpdate): String =
    s"data: ${update.toJson}\n\n"

final case class RunsControllerLive(
  orchestrator: MigrationOrchestrator,
  repository: MigrationRepository,
) extends RunsController:

  private val knownPhases = List("discovery", "analysis", "mapping", "transformation", "validation", "documentation")

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "runs"                           -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          page     <- parseIntQuery(req, "page", 1)
          pageSize <- parseIntQuery(req, "pageSize", 20)
          runs     <- orchestrator.listRuns(page, pageSize)
        yield html(HtmlViews.runsList(runs, page, pageSize))
      }
    },
    Method.GET / "runs" / "new"                   -> handler {
      html(HtmlViews.runForm)
    },
    Method.GET / "runs" / long("id")              -> handler { (runId: Long, _: Request) =>
      ErrorHandlingMiddleware.handle {
        for
          run       <- orchestrator
                         .getRunStatus(runId)
                         .someOrFail(PersistenceError.NotFound("migration_runs", runId))
          phaseRows <- ZIO.foreach(knownPhases)(phase => repository.getProgress(runId, phase)).map(_.flatten)
        yield html(HtmlViews.runDetail(run, phaseRows))
      }
    },
    Method.POST / "runs"                          -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromOrchestrator {
        for
          form        <- parseForm(req)
          sourceDir   <- required(form, "sourceDir")
          outputDir   <- required(form, "outputDir")
          dryRun       = form.get("dryRun").exists(_.equalsIgnoreCase("on"))
          migrationCfg = MigrationConfig(
                           sourceDir = Paths.get(sourceDir),
                           outputDir = Paths.get(outputDir),
                           dryRun = dryRun,
                         )
          runId       <- orchestrator.startMigration(migrationCfg)
          redirectUrl <- ZIO
                           .fromEither(URL.decode(s"/runs/$runId"))
                           .orElseSucceed(URL.root / "runs" / runId.toString)
        yield Response.redirect(redirectUrl)
      }
    },
    Method.DELETE / "runs" / long("id")           -> handler { (runId: Long, _: Request) =>
      ErrorHandlingMiddleware.fromOrchestrator {
        orchestrator.cancelMigration(runId).as(Response.status(Status.NoContent))
      }
    },
    Method.GET / "runs" / long("id") / "progress" -> handler { (runId: Long, _: Request) =>
      val response =
        for
          queue     <- orchestrator.subscribeToProgress(runId)
          liveEvents = ZStream
                         .fromQueue(queue)
                         .map(RunsController.toSseData)
          heartbeat  = ZStream.repeatWithSchedule(": heartbeat\n\n", Schedule.spaced(15.seconds))
          stream     = liveEvents.mergeHaltLeft(heartbeat)
        yield Response(
          body = Body.fromCharSequenceStreamChunked(stream),
          headers =
            Headers(Header.Custom("Content-Type", "text/event-stream"), Header.Custom("Cache-Control", "no-cache")),
        )

      response
    },
  )

  private def parseIntQuery(req: Request, key: String, default: Int): IO[PersistenceError, Int] =
    ZIO.succeed(req.queryParam(key).flatMap(_.toIntOption).getOrElse(default))

  private def parseForm(req: Request): IO[OrchestratorError, Map[String, String]] =
    req.body.asString.map { body =>
      body
        .split("&")
        .toList
        .flatMap { kv =>
          kv.split("=", 2).toList match
            case key :: value :: Nil =>
              Some(urlDecode(key) -> urlDecode(value))
            case key :: Nil          =>
              Some(urlDecode(key) -> "")
            case _                   => None
        }
        .toMap
    }.mapError(err => OrchestratorError.Interrupted(err.getMessage))

  private def required(form: Map[String, String], key: String): IO[OrchestratorError, String] =
    ZIO
      .fromOption(form.get(key).map(_.trim).filter(_.nonEmpty))
      .orElseFail(OrchestratorError.Interrupted(s"Missing required form field: $key"))

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
