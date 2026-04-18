package demo.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import _root_.config.entity.ConfigRepository
import demo.control.DemoOrchestrator
import demo.entity.{ DemoConfig, DemoError }

trait DemoController:
  def routes: Routes[Any, Response]

object DemoController:

  def routes: ZIO[DemoController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[DemoController](_.routes)

  val live: ZLayer[DemoOrchestrator & ConfigRepository, Nothing, DemoController] =
    ZLayer.fromFunction(DemoControllerLive.apply)

final case class DemoControllerLive(
  orchestrator: DemoOrchestrator,
  configRepository: ConfigRepository,
) extends DemoController:

  override val routes: Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "demo" / "quick-start"    -> handler { (_: Request) =>
        quickStart.catchAll(err => ZIO.succeed(errorFragment(err.toString)))
      },
      Method.GET / "api" / "demo" / "status"          -> handler { (_: Request) =>
        orchestrator.status.map(s => htmlFragment(DemoView.statusFragment(s)))
      },
      Method.GET / "api" / "demo" / "cleanup-confirm" -> handler { (_: Request) =>
        orchestrator.status.map(s => htmlFragment(DemoView.cleanupConfirmFragment(s)))
      },
      Method.GET / "api" / "demo" / "cleanup-cancel"  -> handler { (_: Request) =>
        ZIO.succeed(htmlFragment(DemoView.cancelCleanupFragment))
      },
      Method.POST / "api" / "demo" / "cleanup"        -> handler { (req: Request) =>
        cleanupDemo(req).catchAll(err => ZIO.succeed(errorFragment(err.toString)))
      },
    )

  private def quickStart: IO[DemoError, Response] =
    for
      rows      <- configRepository.getAllSettings
                     .mapError(e => DemoError.SetupFailed(e.toString))
      settings   = rows.map(r => r.key -> r.value).toMap
      demoConfig = DemoConfig.fromSettings(settings)
      result    <- orchestrator.runQuickDemo(demoConfig)
    yield htmlFragment(DemoView.quickStartFragment(result))

  private def cleanupDemo(req: Request): IO[DemoError, Response] =
    for
      form        <- req.body.asString
                       .map(parseForm)
                       .mapError(e => DemoError.CleanupFailed(e.getMessage))
      projectId    = form.getOrElse("projectId", "")
      typedPath    = form.getOrElse("confirmPath", "")
      status      <- orchestrator.status
      expectedPath = status.map(_.workspacePath).getOrElse("")
      result      <-
        if typedPath.nonEmpty && typedPath == expectedPath then
          orchestrator.cleanup(projectId).as(htmlFragment(DemoView.cleanupFragment))
        else
          ZIO.succeed(htmlFragment(DemoView.cleanupConfirmErrorFragment(expectedPath, typedPath)))
    yield result

  private def parseForm(body: String): Map[String, String] =
    body
      .split("&")
      .toList
      .flatMap {
        _.split("=", 2).toList match
          case key :: value :: Nil =>
            Some(
              URLDecoder.decode(key, StandardCharsets.UTF_8) ->
                URLDecoder.decode(value, StandardCharsets.UTF_8)
            )
          case key :: Nil          => Some(URLDecoder.decode(key, StandardCharsets.UTF_8) -> "")
          case _                   => None
      }
      .toMap

  private def htmlFragment(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def errorFragment(message: String): Response =
    Response.text(
      s"""<div class="rounded-md bg-red-500/10 border border-red-500/30 p-3">
         |  <p class="text-sm text-red-400">Demo error: $message</p>
         |</div>""".stripMargin
    ).contentType(MediaType.text.html)
