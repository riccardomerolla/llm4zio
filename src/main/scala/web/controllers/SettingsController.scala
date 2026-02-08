package web.controllers

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import db.*
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait SettingsController:
  def routes: Routes[Any, Response]

object SettingsController:

  def routes: ZIO[SettingsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[SettingsController](_.routes)

  val live: ZLayer[MigrationRepository, Nothing, SettingsController] =
    ZLayer.fromFunction(SettingsControllerLive.apply)

final case class SettingsControllerLive(
  repository: MigrationRepository
) extends SettingsController:

  private val settingsKeys: List[String] = List(
    "ai.provider",
    "ai.model",
    "ai.baseUrl",
    "ai.apiKey",
    "ai.timeout",
    "ai.maxRetries",
    "ai.requestsPerMinute",
    "ai.burstSize",
    "ai.acquireTimeout",
    "ai.temperature",
    "ai.maxTokens",
    "processing.parallelism",
    "processing.batchSize",
    "discovery.maxDepth",
    "discovery.excludePatterns",
    "features.enableCheckpointing",
    "features.verbose",
  )

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "settings"  -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        for
          rows    <- repository.getAllSettings
          settings = rows.map(r => r.key -> r.value).toMap
        yield html(HtmlViews.settingsPage(settings))
      }
    },
    Method.POST / "settings" -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form <- parseForm(req)
          _    <- ZIO.foreachDiscard(settingsKeys) { key =>
                    val value = key match
                      case "features.enableCheckpointing" | "features.verbose" =>
                        if form.get(key).exists(_.equalsIgnoreCase("on")) then "true" else "false"
                      case _                                                   =>
                        form.getOrElse(key, "")
                    if value.nonEmpty || key.startsWith("ai.") then repository.upsertSetting(key, value)
                    else ZIO.unit
                  }
          rows <- repository.getAllSettings
          saved = rows.map(r => r.key -> r.value).toMap
        yield html(HtmlViews.settingsPage(saved, Some("Settings saved successfully.")))
      }
    },
  )

  private def parseForm(req: Request): IO[PersistenceError, Map[String, String]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap { kv =>
            kv.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .toMap
      }
      .mapError(err => PersistenceError.QueryFailed("parseForm", err.getMessage))

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def html(bodyContent: String): Response =
    Response.text(bodyContent).contentType(MediaType.text.html)
