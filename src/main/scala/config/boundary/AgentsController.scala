package config.boundary

import java.net.{ URLDecoder, URLEncoder }
import java.nio.charset.StandardCharsets
import java.time.{ Duration, Instant, LocalDate, ZoneOffset }

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream

import _root_.config.entity.{ AgentChannelBinding, AgentInfo, ConfigRepository }
import agent.control.{ AgentMatching, BuiltInAgentSynchronizer }
import agent.entity.api.*
import agent.entity.{ Agent as RegistryAgent, AgentEvent, AgentPermissions, AgentRepository, TrustLevel }
import db.CustomAgentRow
import llm4zio.core.{ LlmError, LlmService }
import orchestration.control.{ AgentRegistry, OrchestratorControlPlane }
import prompts.PromptLoader
import shared.errors.PersistenceError
import shared.ids.Ids.AgentId
import shared.web.{ ErrorHandlingMiddleware, HtmlViews }
import workspace.entity.{ RunStatus, WorkspaceRepository, WorkspaceRun }

trait AgentsController:
  def routes: Routes[Any, Response]

object AgentsController:

  def routes: ZIO[AgentsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[AgentsController](_.routes)

  val live
    : ZLayer[
      ConfigRepository & LlmService & AgentRepository & WorkspaceRepository & OrchestratorControlPlane & PromptLoader,
      Nothing,
      AgentsController,
    ] =
    ZLayer.fromFunction(AgentsControllerLive.apply)

final case class AgentsControllerLive(
  repository: ConfigRepository,
  llmService: LlmService,
  agentRepository: AgentRepository,
  workspaceRepository: WorkspaceRepository,
  controlPlane: OrchestratorControlPlane,
  promptLoader: PromptLoader,
) extends AgentsController:

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
    "ai.fallbackChain",
  )

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "api" / "agents"                                        -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _      <- ensureRegistryMigrated
          agents <- agentRepository.list().mapError(mapAgentRepoError)
        yield Response.json(agents.toJson)
      }
    },
    Method.GET / "api" / "agents" / string("id") / "metrics"             -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _             <- ensureRegistryMigrated
          agent         <- agentRepository.get(AgentId(id)).mapError(mapAgentRepoError)
          workspaceRuns <- loadWorkspaceRuns(agent)
          now           <- Clock.instant
        yield Response.json(computeMetrics(workspaceRuns, now).toJson)
      }
    },
    Method.GET / "api" / "agents" / "metrics"                            -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _      <- ensureRegistryMigrated
          agents <- agentRepository.list().mapError(mapAgentRepoError)
          now    <- Clock.instant
          rows   <- ZIO.foreach(agents.filter(_.enabled)) { agent =>
                      loadWorkspaceRuns(agent).map(runs =>
                        AgentMetricsOverviewItem(
                          agentId = agent.id.value,
                          agentName = agent.name,
                          metrics = computeMetrics(runs, now).summary,
                        )
                      )
                    }
        yield Response.json(rows.sortBy(_.agentName.toLowerCase).toJson)
      }
    },
    Method.GET / "api" / "agents" / string("id") / "metrics" / "history" -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _             <- ensureRegistryMigrated
          agent         <- agentRepository.get(AgentId(id)).mapError(mapAgentRepoError)
          workspaceRuns <- loadWorkspaceRuns(agent)
          days          <- parsePeriodDays(req)
          now           <- Clock.instant
        yield Response.json(computeHistory(workspaceRuns, days, now).toJson)
      }
    },
    Method.GET / "api" / "agents" / "capabilities"                       -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _           <- ensureRegistryMigrated
          agents      <- agentRepository.list().mapError(mapAgentRepoError)
          capabilities = agents.flatMap(_.capabilities).map(_.trim.toLowerCase).filter(_.nonEmpty).distinct.sorted
        yield Response.json(capabilities.toJson)
      }
    },
    Method.GET / "api" / "agents" / "match"                              -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _            <- ensureRegistryMigrated
          agents       <- agentRepository.list().mapError(mapAgentRepoError)
          capabilities  = parseCapabilities(req)
          workspaces   <- workspaceRepository.list.mapError(mapAgentRepoError)
          allRuns      <-
            ZIO.foreach(
              workspaces
            )(ws => workspaceRepository.listRuns(ws.id).mapError(mapAgentRepoError)).map(_.flatten)
          activeByAgent = AgentMatching.activeRunsByAgent(allRuns)
          ranked        = AgentMatching.rankAgents(agents, capabilities, activeByAgent)
          response      = ranked.map(result =>
                            AgentMatchSuggestion(
                              agentId = result.agent.id.value,
                              agentName = result.agent.name,
                              capabilities = result.agent.capabilities,
                              score = result.score,
                              overlapCount = result.overlapCount,
                              requiredCount = result.requiredCount,
                              activeRuns = result.activeRuns,
                            )
                          )
        yield Response.json(response.toJson)
      }
    },
    Method.GET / "api" / "agents" / string("id")                         -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _             <- ensureRegistryMigrated
          agent         <- agentRepository.get(AgentId(id)).mapError(mapAgentRepoError)
          workspaceRuns <- loadWorkspaceRuns(agent)
          now           <- Clock.instant
          metrics        = computeMetrics(workspaceRuns, now)
          history        = computeHistory(workspaceRuns, 30, now)
        yield Response.json(
          AgentDetailResponse(
            agent = agent,
            metrics = metrics.summary,
            history = history,
            activeRuns = metrics.activeRuns,
          ).toJson
        )
      }
    },
    Method.POST / "api" / "agents"                                       -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _      <- ensureRegistryMigrated
          body   <- req.body.asString.mapError(err => PersistenceError.QueryFailed("agent_create", err.getMessage))
          upsert <- ZIO
                      .fromEither(body.fromJson[AgentUpsertRequest])
                      .mapError(err => PersistenceError.QueryFailed("agent_create", err))
          dup    <- agentRepository.findByName(upsert.name).mapError(mapAgentRepoError)
          _      <- ZIO
                      .fail(PersistenceError.QueryFailed("agent_create", s"Agent '${upsert.name}' already exists"))
                      .when(dup.isDefined)
          now    <- Clock.instant
          agent  <- buildAgentFromUpsert(AgentId.generate, upsert, now, createdAt = now)
          _      <- agentRepository.append(AgentEvent.Created(agent, now)).mapError(mapAgentRepoError)
        yield Response.json(agent.toJson).status(Status.Created)
      }
    },
    Method.PUT / "api" / "agents" / string("id")                         -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _        <- ensureRegistryMigrated
          existing <- agentRepository.get(AgentId(id)).mapError(mapAgentRepoError)
          body     <- req.body.asString.mapError(err => PersistenceError.QueryFailed("agent_update", err.getMessage))
          upsert   <- ZIO
                        .fromEither(body.fromJson[AgentUpsertRequest])
                        .mapError(err => PersistenceError.QueryFailed("agent_update", err))
          byName   <- agentRepository.findByName(upsert.name).mapError(mapAgentRepoError)
          _        <- ZIO
                        .fail(PersistenceError.QueryFailed("agent_update", s"Agent '${upsert.name}' already exists"))
                        .when(byName.exists(_.id != existing.id))
          now      <- Clock.instant
          updated  <- buildAgentFromUpsert(existing.id, upsert, now, createdAt = existing.createdAt)
          _        <- agentRepository.append(AgentEvent.Updated(updated, now)).mapError(mapAgentRepoError)
        yield Response.json(updated.toJson)
      }
    },
    Method.DELETE / "api" / "agents" / string("id")                      -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _   <- ensureRegistryMigrated
          now <- Clock.instant
          _   <- agentRepository
                   .append(AgentEvent.Disabled(AgentId(id), Some("Deleted via API"), now))
                   .mapError(mapAgentRepoError)
          _   <- agentRepository.append(AgentEvent.Deleted(AgentId(id), now)).mapError(mapAgentRepoError)
        yield Response(status = Status.NoContent)
      }
    },
    Method.GET / "api" / "agents" / string("id") / "runs"                -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _             <- ensureRegistryMigrated
          agent         <- agentRepository.get(AgentId(id)).mapError(mapAgentRepoError)
          workspaceRuns <- loadWorkspaceRuns(agent)
        yield Response.json(toRunHistory(workspaceRuns).toJson)
      }
    },
    Method.GET / "agents" / "registry" / "new"                           -> handler { (_: Request) =>
      ZIO.succeed(redirectPermanent("/agents/new"))
    },
    Method.GET / "agents" / "registry" / string("id")                    -> handler { (id: String, _: Request) =>
      ZIO.succeed(redirectPermanent(s"/agents/$id"))
    },
    Method.GET / "agents" / "registry"                                   -> handler { (_: Request) =>
      ZIO.succeed(redirectPermanent("/agents"))
    },
    Method.GET / "agents"                                                -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _              <- ensureRegistryMigrated
          registryAgents <- agentRepository.list().mapError(mapAgentRepoError)
          customAgents   <- repository.listCustomAgents
          bindings       <- repository.listAgentChannelBindings
          workspaceRuns  <- loadAllWorkspaceRuns
          now            <- Clock.instant
          flash           = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
          cards           = buildAgentCards(
                              registryAgents = registryAgents,
                              customAgents = customAgents,
                              bindings = bindings,
                              runs = workspaceRuns,
                              now = now,
                            )
        yield html(HtmlViews.agentsPage(cards, flash))
      }
    },
    Method.GET / "agents" / "new"                                        -> handler { (_: Request) =>
      ZIO.succeed(
        html(
          HtmlViews.newAgentPage(
            values = Map(
              "agentSource"       -> "custom",
              "enabled"           -> "true",
              "trustLevel"        -> TrustLevel.Standard.toString,
              "timeout"           -> "PT30M",
              "maxConcurrentRuns" -> "1",
              "cliTool"           -> "gemini",
            )
          )
        )
      )
    },
    Method.POST / "agents"                                               -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _          <- ensureRegistryMigrated
          form       <- parseForm(req)
          source      = form.get("agentSource").map(_.trim.toLowerCase).getOrElse("custom")
          now        <- Clock.instant
          _          <- source match
                          case "registry" =>
                            for
                              upsert <- upsertFromForm(form)
                              dup    <- agentRepository.findByName(upsert.name).mapError(mapAgentRepoError)
                              _      <- ZIO
                                          .fail(
                                            PersistenceError.QueryFailed(
                                              "agent_create",
                                              s"Agent '${upsert.name}' already exists",
                                            )
                                          )
                                          .when(dup.isDefined)
                              agent  <- buildAgentFromUpsert(AgentId.generate, upsert, now, now)
                              _      <- agentRepository.append(AgentEvent.Created(agent, now)).mapError(mapAgentRepoError)
                            yield ()
                          case _          =>
                            for
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
                            yield ()
          displayName =
            form.get("displayName").map(_.trim).filter(_.nonEmpty).getOrElse(form.getOrElse("name", "agent"))
        yield redirectToAgents(s"Agent '$displayName' created.")
      }
    },
    Method.GET / "agents" / string("id")                                 -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _             <- ensureRegistryMigrated
          agent         <- agentRepository.get(AgentId(id)).mapError(mapAgentRepoError)
          workspaceRuns <- loadWorkspaceRuns(agent)
          now           <- Clock.instant
          metrics        = computeMetrics(workspaceRuns, now)
          runs           = toRunHistory(workspaceRuns)
          history        = computeHistory(workspaceRuns, 30, now)
          flash          = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
        yield html(HtmlViews.agentDetailPage(agent, metrics.summary, runs, metrics.activeRuns, history, flash))
      }
    },
    Method.GET / "agents" / string("slug") / "edit"                      -> handler { (slug: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _             <- ensureRegistryMigrated
          registryAgent <- findRegistryAgentById(slug)
          response      <- registryAgent match
                             case Some(agent) =>
                               val values = valuesFromAgent(agent)
                               ZIO.succeed(
                                 html(HtmlViews.agentRegistryFormPage(
                                   "Edit Agent",
                                   s"/agents/${agent.id.value}/edit",
                                   values,
                                 ))
                               )
                             case None        =>
                               for
                                 agent <- repository
                                            .getCustomAgentByName(slug)
                                            .someOrFail(
                                              PersistenceError.QueryFailed("custom_agents", s"Unknown custom agent: $slug")
                                            )
                                 flash  = req.queryParam("flash").map(urlDecode).filter(_.nonEmpty)
                               yield html(
                                 HtmlViews.editCustomAgentPage(
                                   name = agent.name,
                                   values = customAgentFormValues(agent),
                                   flash = flash,
                                 )
                               )
        yield response
      }
    },
    Method.POST / "agents" / string("slug") / "edit"                     -> handler { (slug: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _             <- ensureRegistryMigrated
          registryAgent <- findRegistryAgentById(slug)
          _             <- registryAgent match
                             case Some(existing) =>
                               for
                                 form    <- parseForm(req)
                                 upsert  <- upsertFromForm(form)
                                 byName  <- agentRepository.findByName(upsert.name).mapError(mapAgentRepoError)
                                 _       <- ZIO
                                              .fail(
                                                PersistenceError.QueryFailed(
                                                  "agent_update",
                                                  s"Agent '${upsert.name}' already exists",
                                                )
                                              )
                                              .when(byName.exists(_.id != existing.id))
                                 now     <- Clock.instant
                                 updated <- buildAgentFromUpsert(existing.id, upsert, now, existing.createdAt)
                                 _       <- agentRepository.append(AgentEvent.Updated(updated, now)).mapError(mapAgentRepoError)
                               yield ()
                             case None           =>
                               for
                                 existing <- repository
                                               .getCustomAgentByName(slug)
                                               .someOrFail(
                                                 PersistenceError.QueryFailed(
                                                   "custom_agents",
                                                   s"Unknown custom agent: $slug",
                                                 )
                                               )
                                 form     <- parseForm(req)
                                 now      <- Clock.instant
                                 newName  <- required(form, "name")
                                 _        <- validateAgentNameFormat(newName, "updateCustomAgent")
                                 updated  <- ZIO.succeed(
                                               existing.copy(
                                                 name = newName,
                                                 displayName = form
                                                   .get("displayName")
                                                   .map(_.trim)
                                                   .filter(_.nonEmpty)
                                                   .getOrElse(existing.displayName),
                                                 description = optional(form, "description"),
                                                 systemPrompt = form
                                                   .get("systemPrompt")
                                                   .map(_.trim)
                                                   .filter(_.nonEmpty)
                                                   .getOrElse(existing.systemPrompt),
                                                 tags = optional(form, "tags"),
                                                 updatedAt = now,
                                               )
                                             )
                                 _        <- repository.updateCustomAgent(updated)
                               yield ()
        yield redirectToAgents("Agent updated.")
      }
    },
    Method.POST / "agents" / string("id") / "disable"                    -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _        <- ensureRegistryMigrated
          existing <- agentRepository.get(AgentId(id)).mapError(mapAgentRepoError)
          now      <- Clock.instant
          _        <-
            if existing.enabled then
              agentRepository
                .append(AgentEvent.Disabled(existing.id, Some("Disabled from UI"), now))
                .mapError(mapAgentRepoError)
            else
              agentRepository.append(AgentEvent.Enabled(existing.id, now)).mapError(mapAgentRepoError)
        yield redirectToAgent("Agent status updated.", id)
      }
    },
    Method.POST / "agents" / string("id") / "pause"                      -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _     <- ensureRegistryMigrated
          agent <- agentRepository.get(AgentId(id)).mapError(mapAgentRepoError)
          _     <- controlPlane.pauseAgentExecution(agent.name).mapError(mapControlPlaneError)
        yield redirectToAgents(s"Paused ${agent.name}.")
      }
    },
    Method.POST / "agents" / string("id") / "resume"                     -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _     <- ensureRegistryMigrated
          agent <- agentRepository.get(AgentId(id)).mapError(mapAgentRepoError)
          _     <- controlPlane.resumeAgentExecution(agent.name).mapError(mapControlPlaneError)
        yield redirectToAgents(s"Resumed ${agent.name}.")
      }
    },
    Method.POST / "agents" / string("id") / "abort"                      -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _     <- ensureRegistryMigrated
          agent <- agentRepository.get(AgentId(id)).mapError(mapAgentRepoError)
          _     <- controlPlane.abortAgentExecution(agent.name).mapError(mapControlPlaneError)
        yield redirectToAgents(s"Aborted ${agent.name}.")
      }
    },
    Method.POST / "agents" / string("name") / "delete"                   -> handler { (name: String, _: Request) =>
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
    Method.GET / "agents" / string("name") / "config"                    -> handler { (name: String, req: Request) =>
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
    Method.POST / "agents" / string("name") / "config"                   -> handler { (name: String, req: Request) =>
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
    Method.POST / "agents" / string("name") / "config" / "reset"         -> handler { (name: String, _: Request) =>
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
    Method.POST / "agents" / string("name") / "bindings"                 -> handler { (name: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          agent <-
            findAnyAgent(name).someOrFail(PersistenceError.QueryFailed("agent_bindings", s"Unknown agent: $name"))
          form  <- parseForm(req)
          _     <- repository.upsertAgentChannelBinding(
                     AgentChannelBinding(
                       agentId = AgentId(agent.name),
                       channelName = form.getOrElse("channelName", "").trim,
                       accountId = form.get("accountId").map(_.trim).filter(_.nonEmpty),
                     )
                   )
        yield redirectToAgents(s"Channel binding saved for ${agent.displayName}.")
      }
    },
    Method.POST / "agents" / string("name") / "bindings" / "remove"      -> handler { (name: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          agent <-
            findAnyAgent(name).someOrFail(PersistenceError.QueryFailed("agent_bindings", s"Unknown agent: $name"))
          form  <- parseForm(req)
          _     <- repository.deleteAgentChannelBinding(
                     AgentChannelBinding(
                       agentId = AgentId(agent.name),
                       channelName = form.getOrElse("channelName", "").trim,
                       accountId = form.get("accountId").map(_.trim).filter(_.nonEmpty),
                     )
                   )
        yield redirectToAgents(s"Channel binding removed for ${agent.displayName}.")
      }
    },
    Method.POST / "api" / "agent" / "invoke"                             -> handler { (req: Request) =>
      invokeAgent(req)
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

  private def redirectToAgent(flash: String, id: String): Response =
    Response(
      status = Status.SeeOther,
      headers = Headers(Header.Custom("Location", s"/agents/$id?flash=${urlEncode(flash)}")),
    )

  private def redirectPermanent(path: String): Response =
    Response(
      status = Status.MovedPermanently,
      headers = Headers(Header.Location(URL.decode(path).getOrElse(URL.root))),
    )

  private def mapAgentRepoError(error: shared.errors.PersistenceError): PersistenceError =
    error match
      case shared.errors.PersistenceError.NotFound(entity, id)               =>
        PersistenceError.QueryFailed(entity, s"Not found: $id")
      case shared.errors.PersistenceError.QueryFailed(op, cause)             =>
        PersistenceError.QueryFailed(op, cause)
      case shared.errors.PersistenceError.SerializationFailed(entity, cause) =>
        PersistenceError.QueryFailed(entity, cause)
      case shared.errors.PersistenceError.StoreUnavailable(msg)              =>
        PersistenceError.QueryFailed("store", msg)

  private def mapControlPlaneError(error: shared.errors.ControlPlaneError): PersistenceError =
    PersistenceError.QueryFailed("agent_control", error.toString)

  private def ensureRegistryMigrated: IO[PersistenceError, Unit] =
    for
      _             <- BuiltInAgentSynchronizer.sync.provide(ZLayer.succeed(agentRepository)).mapError(mapAgentRepoError)
      existing      <- agentRepository.list(includeDeleted = true).mapError(mapAgentRepoError)
      existingByName = existing.map(agent => agent.name.trim.toLowerCase -> agent).toMap
      custom        <- repository.listCustomAgents
      now           <- Clock.instant
      customSeed     = seedCustomAgents(custom)
      toCreate       = customSeed.filterNot(agent => existingByName.contains(agent.name.trim.toLowerCase))
      _             <- ZIO.foreachDiscard(toCreate)(agent =>
                         agentRepository.append(AgentEvent.Created(agent, now)).mapError(mapAgentRepoError)
                       )
    yield ()

  private def seedCustomAgents(custom: List[CustomAgentRow]): List[RegistryAgent] =
    custom.map { row =>
      RegistryAgent(
        id = AgentId.generate,
        name = row.name,
        description = row.description.getOrElse(s"Custom agent ${row.displayName}"),
        cliTool = inferCliTool(row.name),
        capabilities = row.tags.toList.flatMap(_.split(",").toList.map(_.trim).filter(_.nonEmpty)),
        defaultModel = None,
        systemPrompt = Some(row.systemPrompt),
        maxConcurrentRuns = 1,
        envVars = Map.empty,
        timeout = Duration.ofMinutes(30),
        enabled = row.enabled,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
      )
    }

  private def inferCliTool(name: String): String =
    val lower = name.trim.toLowerCase
    if lower.contains("claude") then "claude"
    else if lower.contains("opencode") then "opencode"
    else if lower.contains("gemini") then "gemini"
    else if lower.contains("codex") then "codex"
    else "gemini"

  private def parseCapabilities(req: Request): List[String] =
    val raw = req.queryParam("capabilities").getOrElse("")
    raw
      .split(",")
      .toList
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .distinct

  private def findRegistryAgentById(id: String): IO[PersistenceError, Option[RegistryAgent]] =
    agentRepository
      .get(AgentId(id))
      .map(Some(_))
      .catchAll {
        case shared.errors.PersistenceError.NotFound(_, _) => ZIO.none
        case other                                         => ZIO.fail(mapAgentRepoError(other))
      }

  private def loadAllWorkspaceRuns: IO[PersistenceError, List[WorkspaceRun]] =
    for
      workspaces <- workspaceRepository.list.mapError(mapAgentRepoError)
      allRuns    <-
        ZIO.foreach(workspaces)(ws => workspaceRepository.listRuns(ws.id).mapError(mapAgentRepoError)).map(_.flatten)
    yield allRuns

  private def loadWorkspaceRuns(agent: RegistryAgent): IO[PersistenceError, List[WorkspaceRun]] =
    for
      allRuns <- loadAllWorkspaceRuns
      runs     = allRuns
                   .filter(_.agentName.equalsIgnoreCase(agent.name))
                   .sortBy(_.updatedAt)(Ordering[Instant].reverse)
    yield runs

  private def buildAgentCards(
    registryAgents: List[RegistryAgent],
    customAgents: List[CustomAgentRow],
    bindings: List[AgentChannelBinding],
    runs: List[WorkspaceRun],
    now: Instant,
  ): List[shared.web.AgentsView.AgentCard] =
    val infoByNameLower = (registryAgents.map(toAgentInfo) ++ AgentRegistry.allAgents(customAgents))
      .groupBy(_.name.trim.toLowerCase)
      .view
      .mapValues(_.head)
      .toMap
    val registryByName  = registryAgents.groupBy(_.name.trim.toLowerCase).view.mapValues(_.head).toMap
    val names           = (infoByNameLower.keySet ++ registryByName.keySet).toList.sorted
    names.map { nameLower =>
      val info             = infoByNameLower.getOrElse(nameLower, toAgentInfo(registryByName(nameLower)))
      val registryAgent    = registryByName.get(nameLower)
      val agentRuns        = runs.filter(_.agentName.equalsIgnoreCase(info.name))
      val metrics          = computeMetrics(agentRuns, now)
      val bindingsForAgent = bindings.filter(_.agentId.value.equalsIgnoreCase(info.name))
      shared.web.AgentsView.AgentCard(
        info = info,
        registryAgent = registryAgent,
        metrics = metrics.summary,
        activeRuns = metrics.activeRuns,
        bindings = bindingsForAgent,
      )
    }

  private def toRunHistory(runs: List[WorkspaceRun]): List[AgentRunHistoryItem] =
    runs.map(run =>
      AgentRunHistoryItem(
        runId = run.id,
        workspaceId = run.workspaceId,
        issueRef = run.issueRef,
        status = run.status.toString,
        updatedAt = run.updatedAt,
      )
    )

  private def computeMetrics(runs: List[WorkspaceRun], now: Instant): AgentMetricsView =
    val completed      = runs.count(_.status == RunStatus.Completed)
    val failed         = runs.count(_.status == RunStatus.Failed)
    val active         = runs.filter(run => isActive(run.status))
    val terminalRuns   = runs.filter(run => run.status == RunStatus.Completed || run.status == RunStatus.Failed)
    val durations      =
      terminalRuns.map(run => math.max(0L, Duration.between(run.createdAt, run.updatedAt).getSeconds))
    val successDenom   = completed + failed
    val successRate    = if successDenom == 0 then 0.0 else completed.toDouble / successDenom.toDouble
    val cutoff7d       = now.minus(Duration.ofDays(7))
    val cutoff30d      = now.minus(Duration.ofDays(30))
    val runs7d         = runs.count(run => !run.createdAt.isBefore(cutoff7d))
    val runs30d        = runs.count(run => !run.createdAt.isBefore(cutoff30d))
    val issuesResolved = runs.count(run => run.status == RunStatus.Completed && run.issueRef.trim.startsWith("#"))
    val activeRuns     = active
      .map(run =>
        AgentActiveRun(
          runId = run.id,
          workspaceId = run.workspaceId,
          issueRef = run.issueRef,
          conversationId = run.conversationId,
          status = run.status.toString,
          startedAt = run.createdAt,
        )
      )
      .sortBy(_.startedAt)(Ordering[Instant].reverse)
    AgentMetricsView(
      summary = AgentMetricsSummary(
        totalRuns = runs.size,
        completedRuns = completed,
        failedRuns = failed,
        activeRuns = active.size,
        successRate = successRate,
        totalRuns7d = runs7d,
        totalRuns30d = runs30d,
        averageDurationSeconds = if durations.isEmpty then 0L else durations.sum / durations.size,
        issuesResolvedCount = issuesResolved,
      ),
      activeRuns = activeRuns,
    )

  private def computeHistory(
    runs: List[WorkspaceRun],
    days: Int,
    now: Instant,
  ): List[AgentMetricsHistoryPoint] =
    val fromDate = LocalDate.ofInstant(now.minus(Duration.ofDays((days - 1).toLong)), ZoneOffset.UTC)
    val grouped  = runs.groupBy(run => LocalDate.ofInstant(run.createdAt, ZoneOffset.UTC))
    (0 until days).toList.map(offset => fromDate.plusDays(offset.toLong)).map { date =>
      val dayRuns         = grouped.getOrElse(date, Nil)
      val completed       = dayRuns.count(_.status == RunStatus.Completed)
      val failed          = dayRuns.count(_.status == RunStatus.Failed)
      val successDenom    = completed + failed
      val successRate     = if successDenom == 0 then 0.0 else completed.toDouble / successDenom.toDouble
      val dayTerminalRuns = dayRuns.filter(run => run.status == RunStatus.Completed || run.status == RunStatus.Failed)
      val dayDurations    =
        dayTerminalRuns.map(run => math.max(0L, Duration.between(run.createdAt, run.updatedAt).getSeconds))
      AgentMetricsHistoryPoint(
        date = date.toString,
        totalRuns = dayRuns.size,
        completedRuns = completed,
        failedRuns = failed,
        successRate = successRate,
        averageDurationSeconds = if dayDurations.isEmpty then 0L else dayDurations.sum / dayDurations.size,
        issuesResolvedCount =
          dayRuns.count(run => run.status == RunStatus.Completed && run.issueRef.trim.startsWith("#")),
      )
    }

  private def parsePeriodDays(req: Request): IO[PersistenceError, Int] =
    val raw = req.queryParam("period").map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("30d")
    raw.stripSuffix("d").toIntOption match
      case Some(days) if days > 0 && days <= 365 => ZIO.succeed(days)
      case _                                     =>
        ZIO.fail(PersistenceError.QueryFailed("agent_metrics", s"Invalid period '$raw'. Expected like 30d"))

  private def isActive(status: RunStatus): Boolean =
    status == RunStatus.Pending || status.isInstanceOf[RunStatus.Running]

  private def buildAgentFromUpsert(
    id: AgentId,
    request: AgentUpsertRequest,
    now: Instant,
    createdAt: Instant,
  ): IO[PersistenceError, RegistryAgent] =
    for
      timeout <-
        ZIO
          .attempt(Duration.parse(request.timeout.trim))
          .mapError(err =>
            PersistenceError.QueryFailed("agent_upsert", s"Invalid timeout '${request.timeout}': ${err.getMessage}")
          )
      _       <- validateAgentNameFormat(request.name, "agent_upsert")
      _       <- ZIO
                   .fail(PersistenceError.QueryFailed("agent_upsert", "maxConcurrentRuns must be >= 1"))
                   .when(request.maxConcurrentRuns < 1)
      _       <- ZIO
                   .fail(PersistenceError.QueryFailed("agent_upsert", "maxEstimatedTokens must be >= 1"))
                   .when(request.maxEstimatedTokens.exists(_ < 1))
      _       <- ZIO
                   .fail(PersistenceError.QueryFailed("agent_upsert", "timeout must be between PT1S and PT24H"))
                   .when(timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofHours(24)) > 0)
      _       <- ZIO
                   .fail(PersistenceError.QueryFailed("agent_upsert", "description cannot be empty"))
                   .when(request.description.trim.isEmpty)
      _       <- ZIO
                   .fail(PersistenceError.QueryFailed("agent_upsert", "cliTool cannot be empty"))
                   .when(request.cliTool.trim.isEmpty)
      _       <- ZIO.foreachDiscard(request.envVars.keys) { key =>
                   ZIO
                     .fail(
                       PersistenceError.QueryFailed(
                         "agent_upsert",
                         s"Invalid env var name '$key'. Use [A-Za-z_][A-Za-z0-9_]*",
                       )
                     )
                     .when(!key.trim.matches("^[A-Za-z_][A-Za-z0-9_]*$"))
                 }
    yield RegistryAgent(
      id = id,
      name = request.name.trim,
      description = request.description.trim,
      cliTool = request.cliTool.trim,
      capabilities = request.capabilities.map(_.trim).filter(_.nonEmpty).distinct,
      defaultModel = request.defaultModel.map(_.trim).filter(_.nonEmpty),
      systemPrompt = request.systemPrompt.map(_.trim).filter(_.nonEmpty),
      maxConcurrentRuns = request.maxConcurrentRuns,
      envVars = request.envVars.collect { case (k, v) if k.trim.nonEmpty => k.trim -> v },
      dockerMemoryLimit = request.dockerMemoryLimit.map(_.trim).filter(_.nonEmpty),
      dockerCpuLimit = request.dockerCpuLimit.map(_.trim).filter(_.nonEmpty),
      timeout = timeout,
      enabled = request.enabled,
      createdAt = createdAt,
      updatedAt = now,
      deletedAt = None,
      trustLevel = request.trustLevel,
      permissions = AgentPermissions.defaults(
        trustLevel = request.trustLevel,
        cliTool = request.cliTool,
        timeout = timeout,
        maxEstimatedTokens = request.maxEstimatedTokens,
      ),
    )

  private def valuesFromAgent(agent: RegistryAgent): Map[String, String] =
    Map(
      "name"               -> agent.name,
      "description"        -> agent.description,
      "cliTool"            -> agent.cliTool,
      "capabilities"       -> agent.capabilities.mkString(","),
      "defaultModel"       -> agent.defaultModel.getOrElse(""),
      "systemPrompt"       -> agent.systemPrompt.getOrElse(""),
      "maxConcurrentRuns"  -> agent.maxConcurrentRuns.toString,
      "timeout"            -> agent.timeout.toString,
      "envVars"            -> agent.envVars.toList.sortBy(_._1).map((k, v) => s"$k=$v").mkString("\n"),
      "dockerMemoryLimit"  -> agent.dockerMemoryLimit.getOrElse(""),
      "dockerCpuLimit"     -> agent.dockerCpuLimit.getOrElse(""),
      "trustLevel"         -> agent.trustLevel.toString,
      "maxEstimatedTokens" -> agent.permissions.resources.maxEstimatedTokens.map(_.toString).getOrElse(""),
      "enabled"            -> agent.enabled.toString,
    )

  private def upsertFromForm(form: Map[String, String]): IO[PersistenceError, AgentUpsertRequest] =
    for
      name        <- required(form, "name")
      description <- required(form, "description")
      cliTool     <- required(form, "cliTool")
      timeout      = form.getOrElse("timeout", "PT30M").trim
      maxRuns     <- ZIO
                       .fromOption(form.get("maxConcurrentRuns").map(_.trim).filter(_.nonEmpty))
                       .orElseSucceed("1")
                       .flatMap(raw =>
                         ZIO
                           .fromOption(raw.toIntOption)
                           .orElseFail(PersistenceError.QueryFailed("parseForm", "maxConcurrentRuns must be numeric"))
                       )
      trustLevel  <- parseTrustLevel(form.get("trustLevel"))
      maxTokens   <- parseOptionalLong(form.get("maxEstimatedTokens"), "maxEstimatedTokens")
    yield AgentUpsertRequest(
      name = name,
      description = description,
      cliTool = cliTool,
      capabilities = form.get("capabilities").toList.flatMap(_.split(",").toList.map(_.trim).filter(_.nonEmpty)),
      defaultModel = optional(form, "defaultModel"),
      systemPrompt = optional(form, "systemPrompt"),
      maxConcurrentRuns = maxRuns,
      envVars = parseEnvVars(form.get("envVars")),
      dockerMemoryLimit = optional(form, "dockerMemoryLimit"),
      dockerCpuLimit = optional(form, "dockerCpuLimit"),
      trustLevel = trustLevel,
      maxEstimatedTokens = maxTokens,
      timeout = timeout,
      enabled = form.get("enabled").exists(v => v == "on" || v.equalsIgnoreCase("true")),
    )

  private def parseTrustLevel(raw: Option[String]): IO[PersistenceError, TrustLevel] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None       => ZIO.succeed(TrustLevel.Standard)
      case Some(text) =>
        TrustLevel.values.find(_.toString.equalsIgnoreCase(text)) match
          case Some(level) => ZIO.succeed(level)
          case None        =>
            ZIO.fail(
              PersistenceError.QueryFailed(
                "parseForm",
                s"trustLevel must be one of: ${TrustLevel.values.map(_.toString).mkString(", ")}",
              )
            )

  private def parseOptionalLong(raw: Option[String], fieldName: String): IO[PersistenceError, Option[Long]] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None        => ZIO.succeed(None)
      case Some(value) =>
        ZIO
          .fromOption(value.toLongOption)
          .orElseFail(PersistenceError.QueryFailed("parseForm", s"$fieldName must be numeric"))
          .map(Some(_))

  private def parseEnvVars(raw: Option[String]): Map[String, String] =
    raw.toList
      .flatMap(_.split("\n").toList)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { line =>
        line.split("=", 2).toList match
          case key :: value :: Nil => Some(key.trim -> value.trim)
          case _                   => None
      }
      .toMap

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
      _        <- ensureRegistryMigrated
      registry <- agentRepository.list().mapError(mapAgentRepoError)
      custom   <- repository.listCustomAgents
      merged    = (registry.map(toAgentInfo) ++ AgentRegistry.allAgents(custom)).groupBy(_.name.toLowerCase).values.map(
                    _.head
                  ).toList
    yield merged.find { agent =>
      agent.name.equalsIgnoreCase(name.trim) || agent.handle.equalsIgnoreCase(name.trim)
    }

  private def toAgentInfo(agent: RegistryAgent): AgentInfo =
    val builtInNames = AgentRegistry.builtInAgents.map(_.name.toLowerCase).toSet
    AgentInfo(
      name = agent.name,
      handle = agent.name.trim.toLowerCase.replaceAll("[^a-z0-9_-]+", "-"),
      displayName = agent.name,
      description = agent.description,
      agentType = if builtInNames.contains(agent.name.toLowerCase) then _root_.config.entity.AgentType.BuiltIn
      else _root_.config.entity.AgentType.Custom,
      usesAI = true,
      tags = agent.capabilities,
    )

  final private case class AgentInvokeRequest(
    message: String,
    agentName: String,
    sessionId: String,
  ) derives JsonCodec

  final private case class AgentInvokeEvent(
    agentName: String,
    delta: String,
    done: Boolean = false,
  ) derives JsonCodec

  private def invokeAgent(req: Request): UIO[Response] =
    val effect =
      for
        body         <- req.body.asString.mapError(err => PersistenceError.QueryFailed("agent_invoke", err.getMessage))
        invokeReq    <- ZIO.fromEither(body.fromJson[AgentInvokeRequest]).mapError(err =>
                          PersistenceError.QueryFailed("agent_invoke", s"Invalid JSON body: $err")
                        )
        targetAgent  <- findAnyAgent(invokeReq.agentName)
                          .someOrFail(
                            PersistenceError.QueryFailed("agent_invoke", s"Unknown agent '${invokeReq.agentName}'")
                          )
        customPrompt <- repository
                          .getCustomAgentByName(targetAgent.name)
                          .map(_.map(_.systemPrompt).filter(_.trim.nonEmpty))
        prompt       <- buildAgentPrompt(customPrompt, invokeReq.message, invokeReq.sessionId, targetAgent.name)
        stream        =
          llmService
            .executeStream(prompt)
            .map(chunk =>
              s"data: ${AgentInvokeEvent(agentName = targetAgent.name, delta = chunk.delta).toJson}\n\n"
            )
            .catchAll(err =>
              ZStream.succeed(
                s"data: ${AgentInvokeEvent(targetAgent.name, delta = formatLlmError(err), done = true).toJson}\n\n"
              )
            ) ++
            ZStream.succeed(
              s"data: ${AgentInvokeEvent(targetAgent.name, delta = "", done = true).toJson}\n\n"
            )
      yield Response(
        status = Status.Ok,
        headers = Headers(
          Header.ContentType(MediaType.text.`event-stream`),
          Header.CacheControl.NoCache,
          Header.Custom("Connection", "keep-alive"),
        ),
        body = Body.fromCharSequenceStreamChunked(stream),
      )

    effect.catchAll(err => ZIO.succeed(Response.text(err.toString).status(Status.BadRequest)))

  private def buildAgentPrompt(
    systemPrompt: Option[String],
    message: String,
    sessionId: String,
    agentName: String,
  ): IO[PersistenceError, String] =
    val custom = systemPrompt.map(_.trim).filter(_.nonEmpty).map(v => s"$v\n\n").getOrElse("")
    promptLoader
      .load(
        "agent-execution",
        Map(
          "custom"    -> custom,
          "agentName" -> agentName,
          "sessionId" -> sessionId,
          "message"   -> message,
        ),
      )
      .mapError(err => PersistenceError.QueryFailed("agent_invoke_prompt", err.message))

  private def formatLlmError(error: LlmError): String =
    error match
      case LlmError.ProviderError(message, _)    => message
      case LlmError.RateLimitError(_)            => "Rate limited"
      case LlmError.AuthenticationError(message) => s"Authentication failed: $message"
      case LlmError.InvalidRequestError(message) => s"Invalid request: $message"
      case LlmError.TimeoutError(duration)       => s"Timed out after ${duration.toSeconds}s"
      case LlmError.ParseError(message, _)       => s"Parse error: $message"
      case LlmError.ToolError(toolName, message) => s"Tool error ($toolName): $message"
      case LlmError.ConfigError(message)         => s"Configuration error: $message"
      case LlmError.TurnLimitError(limit)        => s"Turn limit exceeded${limit.map(l => s" (limit: $l)").getOrElse("")}"

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
