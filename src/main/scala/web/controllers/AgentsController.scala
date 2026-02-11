package web.controllers

import java.net.{ URLDecoder, URLEncoder }
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import agents.AgentRegistry
import db.{ MigrationRepository, PersistenceError }
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait AgentsController:
  def routes: Routes[Any, Response]

object AgentsController:

  def routes: ZIO[AgentsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[AgentsController](_.routes)

  val live: ZLayer[MigrationRepository, Nothing, AgentsController] =
    ZLayer.fromFunction(AgentsControllerLive.apply)

final case class AgentsControllerLive(repository: MigrationRepository) extends AgentsController:

  private val aiSettingKeys: List[String] = List(
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
  )

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "agents"                                        -> handler {
      html(HtmlViews.agentsPage(AgentRegistry.builtInAgents))
    },
    Method.GET / "agents" / string("name") / "config"            -> handler { (name: String, req: Request) =>
      AgentRegistry.findByName(name) match
        case Some(agent) if agent.usesAI =>
          ErrorHandlingMiddleware.fromPersistence {
            val prefix = s"agent.${agent.name}.ai."
            for
              overrides <- repository.getSettingsByPrefix(prefix)
              globals   <- repository.getSettingsByPrefix("ai.")
              flash      = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
            yield html(
              HtmlViews.agentConfigPage(
                agent = agent,
                overrideSettings = toBaseKeyMap(overrides, prefix),
                globalSettings = toKeyMap(globals),
                flash = flash,
              )
            )
          }
        case Some(_)                     =>
          ZIO.succeed(
            Response.text("Agent configuration is available only for AI-enabled agents.").status(Status.NotFound)
          )
        case None                        =>
          ZIO.succeed(Response.text(s"Unknown agent: $name").status(Status.NotFound))
    },
    Method.POST / "agents" / string("name") / "config"           -> handler { (name: String, req: Request) =>
      AgentRegistry.findByName(name) match
        case Some(agent) if agent.usesAI =>
          ErrorHandlingMiddleware.fromPersistence {
            val prefix = s"agent.${agent.name}.ai."
            for
              form <- parseForm(req)
              _    <- repository.deleteSettingsByPrefix(prefix)
              _    <- ZIO.foreachDiscard(aiSettingKeys) { key =>
                        form.get(key).map(_.trim).filter(_.nonEmpty) match
                          case Some(value) => repository.upsertSetting(s"$prefix${stripAiPrefix(key)}", value)
                          case None        => ZIO.unit
                      }
            yield redirectToConfig(agent.name, "Agent AI overrides saved.")
          }
        case Some(_)                     =>
          ZIO.succeed(
            Response.text("Agent configuration is available only for AI-enabled agents.").status(Status.NotFound)
          )
        case None                        =>
          ZIO.succeed(Response.text(s"Unknown agent: $name").status(Status.NotFound))
    },
    Method.POST / "agents" / string("name") / "config" / "reset" -> handler { (name: String, _: Request) =>
      AgentRegistry.findByName(name) match
        case Some(agent) if agent.usesAI =>
          ErrorHandlingMiddleware.fromPersistence {
            val prefix = s"agent.${agent.name}.ai."
            repository.deleteSettingsByPrefix(prefix).as(
              redirectToConfig(agent.name, "Agent overrides reset to global defaults.")
            )
          }
        case Some(_)                     =>
          ZIO.succeed(
            Response.text("Agent configuration is available only for AI-enabled agents.").status(Status.NotFound)
          )
        case None                        =>
          ZIO.succeed(Response.text(s"Unknown agent: $name").status(Status.NotFound))
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def redirectToConfig(agentName: String, flash: String): Response =
    Response(
      status = Status.SeeOther,
      headers = Headers(Header.Custom("Location", s"/agents/$agentName/config?flash=${urlEncode(flash)}")),
    )

  private def toKeyMap(rows: List[db.SettingRow]): Map[String, String] =
    rows.map(row => row.key -> row.value).toMap

  private def toBaseKeyMap(rows: List[db.SettingRow], prefix: String): Map[String, String] =
    rows.flatMap { row =>
      row.key.stripPrefix(prefix) match
        case suffix if suffix.nonEmpty => Some(s"ai.$suffix" -> row.value)
        case _                         => None
    }.toMap

  private def parseForm(req: Request): IO[PersistenceError, Map[String, String]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap {
            _.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .toMap
      }
      .mapError(err => PersistenceError.QueryFailed("parseForm", err.getMessage))

  private def stripAiPrefix(key: String): String =
    key.stripPrefix("ai.")

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
