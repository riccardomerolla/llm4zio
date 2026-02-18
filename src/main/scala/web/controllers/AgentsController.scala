package web.controllers

import java.net.{ URLDecoder, URLEncoder }
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*
import zio.json.*

import agents.AgentRegistry
import db.{ CustomAgentRow, PersistenceError, TaskRepository }
import models.*
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait AgentsController:
  def routes: Routes[Any, Response]

object AgentsController:

  def routes: ZIO[AgentsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[AgentsController](_.routes)

  val live: ZLayer[TaskRepository, Nothing, AgentsController] =
    ZLayer.fromFunction(AgentsControllerLive.apply)

final case class AgentsControllerLive(repository: TaskRepository) extends AgentsController:

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
    Method.GET / "api" / "agents"                                  -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          custom <- repository.listCustomAgents
          all     = AgentRegistry.allAgents(custom)
        yield Response.json(all.toJson)
      }
    },
    Method.GET / "agents"                                        -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          custom <- repository.listCustomAgents
          flash   = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
        yield html(HtmlViews.agentsPage(AgentRegistry.allAgents(custom), flash))
      }
    },
    Method.GET / "agents" / "new"                                -> handler { (_: Request) =>
      ZIO.succeed(html(HtmlViews.newCustomAgentPage()))
    },
    Method.POST / "agents"                                       -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form         <- parseForm(req)
          now          <- Clock.instant
          name         <- required(form, "name")
          displayName  <- required(form, "displayName")
          systemPrompt <- required(form, "systemPrompt")
          _            <- validateAgentNameFormat(name, "createCustomAgent")
          _            <- repository.createCustomAgent(
                            CustomAgentRow(
                              name = name,
                              displayName = displayName,
                              description = optional(form, "description"),
                              systemPrompt = systemPrompt,
                              tags = optional(form, "tags"),
                              enabled = true,
                              createdAt = now,
                              updatedAt = now,
                            )
                          )
        yield redirectToAgents(s"Custom agent '$displayName' created.")
      }
    },
    Method.GET / "agents" / string("name") / "edit"              -> handler { (name: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          agent <- repository
                     .getCustomAgentByName(name)
                     .someOrFail(PersistenceError.QueryFailed("custom_agents", s"Unknown custom agent: $name"))
          flash  = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
        yield html(
          HtmlViews.editCustomAgentPage(
            name = agent.name,
            values = customAgentFormValues(agent),
            flash = flash,
          )
        )
      }
    },
    Method.POST / "agents" / string("name") / "edit"             -> handler { (name: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          existing <- repository
                        .getCustomAgentByName(name)
                        .someOrFail(PersistenceError.QueryFailed("custom_agents", s"Unknown custom agent: $name"))
          form     <- parseForm(req)
          now      <- Clock.instant
          // Name is immutable in edit form, but we still parse and validate it.
          newName  <- required(form, "name")
          _        <- validateAgentNameFormat(newName, "updateCustomAgent")
          updated  <- ZIO.succeed(
                        existing.copy(
                          name = newName,
                          displayName =
                            form.get("displayName").map(_.trim).filter(_.nonEmpty).getOrElse(existing.displayName),
                          description = optional(form, "description"),
                          systemPrompt =
                            form.get("systemPrompt").map(_.trim).filter(_.nonEmpty).getOrElse(existing.systemPrompt),
                          tags = optional(form, "tags"),
                          updatedAt = now,
                        )
                      )
          _        <- repository.updateCustomAgent(updated)
        yield redirectToAgents(s"Custom agent '${updated.displayName}' updated.")
      }
    },
    Method.POST / "agents" / string("name") / "delete"           -> handler { (name: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          existing <- repository
                        .getCustomAgentByName(name)
                        .someOrFail(PersistenceError.QueryFailed("custom_agents", s"Unknown custom agent: $name"))
          id       <- ZIO
                        .fromOption(existing.id)
                        .orElseFail(PersistenceError.QueryFailed("custom_agents", s"Missing id for custom agent: $name"))
          _        <- repository.deleteCustomAgent(id)
          _        <- repository.deleteSettingsByPrefix(s"agent.${existing.name}.ai.")
        yield redirectToAgents(s"Custom agent '${existing.displayName}' deleted.")
      }
    },
    Method.GET / "agents" / string("name") / "config"            -> handler { (name: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          agent <- findAnyAgent(name)
          out   <- agent match
                     case Some(value) if value.usesAI =>
                       val prefix = s"agent.${value.name}.ai."
                       for
                         overrides <- repository.getSettingsByPrefix(prefix)
                         globals   <- repository.getSettingsByPrefix("ai.")
                         flash      = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
                       yield html(
                         HtmlViews.agentConfigPage(
                           agent = value,
                           overrideSettings = toBaseKeyMap(overrides, prefix),
                           globalSettings = toKeyMap(globals),
                           flash = flash,
                         )
                       )
                     case Some(_)                     =>
                       ZIO.succeed(
                         Response.text(
                           "Agent configuration is available only for AI-enabled agents."
                         ).status(Status.NotFound)
                       )
                     case None                        =>
                       ZIO.succeed(Response.text(s"Unknown agent: $name").status(Status.NotFound))
        yield out
      }
    },
    Method.POST / "agents" / string("name") / "config"           -> handler { (name: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          agent <- findAnyAgent(name)
          out   <- agent match
                     case Some(value) if value.usesAI =>
                       val prefix = s"agent.${value.name}.ai."
                       for
                         form <- parseForm(req)
                         _    <- repository.deleteSettingsByPrefix(prefix)
                         _    <- ZIO.foreachDiscard(aiSettingKeys) { key =>
                                   form.get(key).map(_.trim).filter(_.nonEmpty) match
                                     case Some(v) => repository.upsertSetting(s"$prefix${stripAiPrefix(key)}", v)
                                     case None    => ZIO.unit
                                 }
                       yield redirectToConfig(value.name, "Agent AI overrides saved.")
                     case Some(_)                     =>
                       ZIO.succeed(
                         Response.text(
                           "Agent configuration is available only for AI-enabled agents."
                         ).status(Status.NotFound)
                       )
                     case None                        =>
                       ZIO.succeed(Response.text(s"Unknown agent: $name").status(Status.NotFound))
        yield out
      }
    },
    Method.POST / "agents" / string("name") / "config" / "reset" -> handler { (name: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          agent <- findAnyAgent(name)
          out   <- agent match
                     case Some(value) if value.usesAI =>
                       val prefix = s"agent.${value.name}.ai."
                       repository.deleteSettingsByPrefix(prefix).as(
                         redirectToConfig(value.name, "Agent overrides reset to global defaults.")
                       )
                     case Some(_)                     =>
                       ZIO.succeed(
                         Response.text(
                           "Agent configuration is available only for AI-enabled agents."
                         ).status(Status.NotFound)
                       )
                     case None                        =>
                       ZIO.succeed(Response.text(s"Unknown agent: $name").status(Status.NotFound))
        yield out
      }
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def redirectToConfig(agentName: String, flash: String): Response =
    Response(
      status = Status.SeeOther,
      headers = Headers(Header.Custom("Location", s"/agents/$agentName/config?flash=${urlEncode(flash)}")),
    )

  private def redirectToAgents(flash: String): Response =
    Response(
      status = Status.SeeOther,
      headers = Headers(Header.Custom("Location", s"/agents?flash=${urlEncode(flash)}")),
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

  private def required(form: Map[String, String], field: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(form.get(field).map(_.trim).filter(_.nonEmpty))
      .orElseFail(PersistenceError.QueryFailed("parseForm", s"Missing field '$field'"))

  private def optional(form: Map[String, String], field: String): Option[String] =
    form.get(field).map(_.trim).filter(_.nonEmpty)

  private def customAgentFormValues(agent: CustomAgentRow): Map[String, String] =
    Map(
      "name"         -> agent.name,
      "displayName"  -> agent.displayName,
      "description"  -> agent.description.getOrElse(""),
      "systemPrompt" -> agent.systemPrompt,
      "tags"         -> agent.tags.getOrElse(""),
    )

  private def validateAgentNameFormat(name: String, sql: String): IO[PersistenceError, Unit] =
    val trimmed = name.trim
    if trimmed.matches("^[A-Za-z][A-Za-z0-9_-]*$") then ZIO.unit
    else
      ZIO.fail(
        PersistenceError.QueryFailed(
          sql,
          "Agent name must start with a letter and contain only letters, numbers, '_' or '-'.",
        )
      )

  private def findAnyAgent(name: String): IO[PersistenceError, Option[AgentInfo]] =
    for
      custom <- repository.listCustomAgents
    yield AgentRegistry.allAgents(custom).find(_.name.equalsIgnoreCase(name.trim))

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
