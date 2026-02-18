package web.controllers

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import _root_.config.SettingsApplier
import db.*
import llm4zio.core.{ LlmError, LlmService }
import models.{ ActivityEvent, ActivityEventType, GatewayConfig }
import web.views.{ HtmlViews, SettingsView }
import web.{ ActivityHub, ErrorHandlingMiddleware }

trait SettingsController:
  def routes: Routes[Any, Response]

object SettingsController:

  def routes: ZIO[SettingsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[SettingsController](_.routes)

  val live: ZLayer[TaskRepository & ActivityHub & Ref[GatewayConfig] & LlmService, Nothing, SettingsController] =
    ZLayer.fromFunction(SettingsControllerLive.apply)

final case class SettingsControllerLive(
  repository: TaskRepository,
  activityHub: ActivityHub,
  configRef: Ref[GatewayConfig],
  llmService: LlmService,
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
    "gateway.name",
    "gateway.dryRun",
    "gateway.verbose",
    "telegram.enabled",
    "telegram.botToken",
    "telegram.mode",
    "telegram.secretToken",
    "telegram.webhookUrl",
    "telegram.polling.interval",
    "telegram.polling.batchSize",
    "telegram.polling.timeout",
  )

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "settings"                      -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        for
          rows    <- repository.getAllSettings
          settings = rows.map(r => r.key -> r.value).toMap
        yield html(HtmlViews.settingsPage(settings))
      }
    },
    Method.POST / "settings"                     -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form     <- parseForm(req)
          _        <- ZIO.foreachDiscard(settingsKeys) { key =>
                        val value = key match
                          case "gateway.dryRun" | "gateway.verbose" | "telegram.enabled" =>
                            if form.get(key).exists(_.equalsIgnoreCase("on")) then "true" else "false"
                          case _                                                         =>
                            form.getOrElse(key, "")
                        if value.nonEmpty || key.startsWith("ai.") || key.startsWith("gateway.") || key.startsWith("telegram.")
                        then
                          repository.upsertSetting(key, value)
                        else ZIO.unit
                      }
          rows     <- repository.getAllSettings
          saved     = rows.map(r => r.key -> r.value).toMap
          newConfig = SettingsApplier.toGatewayConfig(saved)
          _        <- configRef.set(newConfig)
          now      <- Clock.instant
          _        <- activityHub.publish(
                        ActivityEvent(
                          eventType = ActivityEventType.ConfigChanged,
                          source = "settings",
                          summary = "Application settings updated",
                          createdAt = now,
                        )
                      )
        yield html(HtmlViews.settingsPage(saved, Some("Settings saved successfully.")))
      }
    },
    Method.POST / "api" / "settings" / "test-ai" -> handler { (req: Request) =>
      testAIConnection(req)
    },
  )

  private def testAIConnection(req: Request): UIO[Response] =
    val test =
      for
        form     <- parseForm(req)
        aiConfig <- ZIO.fromOption(SettingsApplier.toAIProviderConfig(form)).orElseFail("No AI provider configured")
        start    <- Clock.nanoTime
        _        <- llmService.execute("Say 'pong'")
        end      <- Clock.nanoTime
        latency   = (end - start) / 1_000_000 // Convert nanos to millis
      yield (aiConfig.model, latency)

    test
      .fold(
        error => {
          val errorMessage = error match
            case e: LlmError => formatLlmError(e)
            case msg: String => msg
            case _           => "Unknown error"
          SettingsView.testConnectionError(errorMessage)
        },
        {
          case (model, latency) =>
            SettingsView.testConnectionSuccess(model, latency)
        },
      )
      .map { htmlString =>
        Response.text(htmlString).contentType(MediaType.text.html)
      }

  private def formatLlmError(error: LlmError): String =
    error match
      case LlmError.ProviderError(message, _)    => message
      case LlmError.RateLimitError(_)            => "Rate limited: Too many requests"
      case LlmError.AuthenticationError(message) => s"Authentication failed: $message"
      case LlmError.InvalidRequestError(message) => s"Invalid request: $message"
      case LlmError.TimeoutError(duration)       => s"Request timed out after ${duration.toSeconds}s"
      case LlmError.ParseError(message, _)       => s"Parse error: $message"
      case LlmError.ToolError(toolName, message) => s"Tool error ($toolName): $message"
      case LlmError.ConfigError(message)         => s"Configuration error: $message"

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
