package config.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths, StandardOpenOption }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.http.*
import zio.json.*

import _root_.config.SettingsApplier
import _root_.config.control.ModelService
import _root_.config.entity.{ ConfigRepository, GatewayConfig }
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import llm4zio.core.{ LlmError, LlmService, Streaming }
import llm4zio.tools.ToolRegistry
import shared.errors.PersistenceError
import shared.ids.Ids.EventId
import shared.store.{ MemoryStoreModule, * }
import shared.web.{ ErrorHandlingMiddleware, HtmlViews, SettingsView }

trait SettingsController:
  def routes: Routes[Any, Response]

object SettingsController:

  def routes: ZIO[SettingsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[SettingsController](_.routes)

  val live
    : ZLayer[
      ConfigRepository & ActivityHub & Ref[GatewayConfig] & LlmService & ModelService & ConfigStoreModule.ConfigStoreService &
        DataStoreService & StoreConfig &
        MemoryStoreModule.MemoryEntriesStore & ToolRegistry,
      Nothing,
      SettingsController,
    ] =
    ZLayer.fromFunction(SettingsControllerLive.apply)

final case class SettingsControllerLive(
  repository: ConfigRepository,
  activityHub: ActivityHub,
  configRef: Ref[GatewayConfig],
  llmService: LlmService,
  modelService: ModelService,
  configStoreService: ConfigStoreModule.ConfigStoreService,
  dataStoreService: DataStoreService,
  storeConfig: StoreConfig,
  memoryEntriesStore: MemoryStoreModule.MemoryEntriesStore,
  toolRegistry: ToolRegistry,
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
    "ai.fallbackChain",
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
    "memory.enabled",
    "memory.maxContextMemories",
    "memory.summarizationThreshold",
    "memory.retentionDays",
    "prompts.reloading",
    "demo.enabled",
    "demo.issueCount",
    "demo.agentDelaySeconds",
    "demo.previousProvider",
  )

  private val apiConnectorKeys: List[String] = List(
    "connector.default.api.provider",
    "connector.default.api.model",
    "connector.default.api.baseUrl",
    "connector.default.api.apiKey",
    "connector.default.api.timeout",
    "connector.default.api.maxRetries",
    "connector.default.api.requestsPerMinute",
    "connector.default.api.burstSize",
    "connector.default.api.acquireTimeout",
    "connector.default.api.temperature",
    "connector.default.api.maxTokens",
    "connector.default.api.fallbackChain",
  )

  private val cliConnectorKeys: List[String] = List(
    "connector.default.cli.connector",
    "connector.default.cli.model",
    "connector.default.cli.timeout",
    "connector.default.cli.maxRetries",
    "connector.default.cli.turnLimit",
    "connector.default.cli.sandbox",
    "connector.default.cli.flags",
    "connector.default.cli.envVars",
  )

  private def loadConnectorsPage(flash: Option[String] = None): IO[PersistenceError, Response] =
    for
      rows    <- repository.getAllSettings
      settings = rows.map(r => r.key -> r.value).toMap
    yield html(HtmlViews.settingsConnectorsTab(settings, flash = flash))

  private def parseEnvVars(form: Map[String, String], prefix: String): String =
    val keys = form.toList.filter(_._1.startsWith(s"$prefix.key.")).sortBy(_._1)
    keys.flatMap {
      case (keyField, keyValue) =>
        val index    = keyField.stripPrefix(s"$prefix.key.")
        val valField = s"$prefix.value.$index"
        form.get(valField).filter(_.nonEmpty).map(v => s"${keyValue.trim}=$v")
    }.mkString(",")

  final private case class StoreDebugEntry(
    key: String,
    prefix: String,
    rawValue: Option[String],
    error: Option[String],
  ) derives JsonCodec

  final private case class StoreDebugResponse(
    dataStorePath: String,
    dataStoreInstanceId: String,
    dataStoreBytes: Long,
    dataStoreKeyCount: Int,
    dataStorePrefixCounts: Map[String, Int],
    dataEntries: List[StoreDebugEntry],
    configStorePath: Option[String],
    configStoreInstanceId: Option[String],
    configStoreBytes: Option[Long],
    configStoreKeyCount: Option[Int],
    configStorePrefixCounts: Option[Map[String, Int]],
    configEntries: Option[List[StoreDebugEntry]],
    appliedPrefix: Option[String],
  ) derives JsonCodec

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "settings"                                   -> handler {
      ZIO.succeed(Response(
        status = Status.Found,
        headers = Headers(Header.Location(URL.decode("/settings/connectors").getOrElse(URL.root))),
      ))
    },
    Method.POST / "settings"                                  -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form     <- parseForm(req)
          _        <- ZIO.foreachDiscard(settingsKeys) { key =>
                        val value = key match
                          case "gateway.dryRun" | "gateway.verbose" | "telegram.enabled" | "memory.enabled" |
                               "prompts.reloading" =>
                            if form.get(key).exists(_.equalsIgnoreCase("on")) then "true" else "false"
                          case _ =>
                            form.getOrElse(key, "")
                        if value.nonEmpty || key.startsWith("ai.") || key.startsWith("gateway.") || key.startsWith(
                            "telegram."
                          ) || key
                            .startsWith("memory.") || key.startsWith("prompts.")
                        then
                          repository.upsertSetting(key, value)
                        else ZIO.unit
                      }
          _        <- checkpointConfigStore
          rows     <- repository.getAllSettings
          saved     = rows.map(r => r.key -> r.value).toMap
          newConfig = SettingsApplier.toGatewayConfig(saved)
          _        <- configRef.set(newConfig)
          _        <- writeSettingsSnapshot(saved)
          now      <- Clock.instant
          _        <- activityHub.publish(
                        ActivityEvent(
                          id = EventId.generate,
                          eventType = ActivityEventType.ConfigChanged,
                          source = "settings",
                          summary = "Application settings updated",
                          createdAt = now,
                        )
                      )
        yield html(HtmlViews.settingsPage(saved, Some("Settings saved successfully.")))
      }
    },
    Method.POST / "api" / "store" / "reset-data"              -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _   <- resetDataStore
          now <- Clock.instant
          _   <- activityHub.publish(
                   ActivityEvent(
                     id = EventId.generate,
                     eventType = ActivityEventType.ConfigChanged,
                     source = "settings",
                     summary = "Operational data store reset",
                     createdAt = now,
                   )
                 )
        yield Response(
          status = Status.Ok,
          headers = Headers(Header.Custom("HX-Redirect", "/")),
          body = Body.fromString("Data store reset completed."),
        )
      }
    },
    Method.GET / "api" / "store" / "debug-data"               -> handler { (req: Request) =>
      val includeConfig = req.queryParam("includeConfig").exists(_.trim.equalsIgnoreCase("true"))
      val prefixFilter  = req.queryParam("prefix").map(_.trim).filter(_.nonEmpty)
      ErrorHandlingMiddleware.fromPersistence(debugDataStore(includeConfig, prefixFilter))
    },
    Method.POST / "api" / "settings" / "test-ai"              -> handler { (req: Request) =>
      testAIConnection(req)
    },
    Method.GET / "api" / "models"                             -> handler {
      modelService.listAvailableModels.map(models => Response.json(models.toJson))
    },
    Method.GET / "api" / "models" / "status"                  -> handler {
      modelService.probeProviders.map(status => Response.json(status.toJson))
    },
    Method.GET / "models"                                     -> handler {
      ZIO.succeed(Response(
        status = Status.Found,
        headers = Headers(Header.Location(URL.decode("/settings/connectors").getOrElse(URL.root))),
      ))
    },
    Method.GET / "settings" / "connectors"                    -> handler {
      ErrorHandlingMiddleware.fromPersistence(loadConnectorsPage())
    },
    Method.GET / "settings" / "ai"                            -> handler {
      // Backward-compatible redirect: /settings/ai -> /settings/connectors
      ZIO.succeed(Response(
        status = Status.Found,
        headers = Headers(Header.Location(URL.decode("/settings/connectors").getOrElse(URL.root))),
      ))
    },
    Method.GET / "settings" / "connectors" / "tools-fragment" -> handler { (_: Request) =>
      toolRegistry.list.map { tools =>
        htmlFragment(SettingsView.toolsFragment(tools))
      }
    },
    Method.GET / "settings" / "ai" / "tools-fragment"         -> handler { (_: Request) =>
      // Backward-compatible: keep old tools-fragment URL working
      toolRegistry.list.map { tools =>
        htmlFragment(SettingsView.toolsFragment(tools))
      }
    },
    Method.POST / "settings" / "connectors" / "api"           -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form     <- parseForm(req)
          _        <- ZIO.foreachDiscard(apiConnectorKeys) { key =>
                        val value = form.getOrElse(key, "")
                        repository.upsertSetting(key, value)
                      }
          // Dual-write ai.* keys for backward compat
          _        <- ZIO.foreachDiscard(apiConnectorKeys) { key =>
                        val shortKey  = key.stripPrefix("connector.default.api.")
                        val legacyKey = if shortKey == "provider" then "ai.provider" else s"ai.$shortKey"
                        val value     = form.getOrElse(key, "")
                        repository.upsertSetting(legacyKey, value)
                      }
          _        <- checkpointConfigStore
          rows     <- repository.getAllSettings
          saved     = rows.map(r => r.key -> r.value).toMap
          newConfig = SettingsApplier.toGatewayConfig(saved)
          _        <- configRef.set(newConfig)
          _        <- writeSettingsSnapshot(saved)
          now      <- Clock.instant
          _        <- activityHub.publish(
                        ActivityEvent(
                          id = EventId.generate,
                          eventType = ActivityEventType.ConfigChanged,
                          source = "settings.connectors.api",
                          summary = "API connector defaults updated",
                          createdAt = now,
                        )
                      )
        yield htmlFragment(SettingsView.apiDefaultCard(saved, Map.empty).render)
      }
    },
    Method.POST / "settings" / "connectors" / "cli"           -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form   <- parseForm(req)
          envVars = parseEnvVars(form, "connector.default.cli.envVars")
          _      <- ZIO.foreachDiscard(cliConnectorKeys) { key =>
                      val value =
                        if key == "connector.default.cli.envVars" then envVars
                        else form.getOrElse(key, "")
                      repository.upsertSetting(key, value)
                    }
          _      <- checkpointConfigStore
          rows   <- repository.getAllSettings
          saved   = rows.map(r => r.key -> r.value).toMap
          _      <- writeSettingsSnapshot(saved)
          now    <- Clock.instant
          _      <- activityHub.publish(
                      ActivityEvent(
                        id = EventId.generate,
                        eventType = ActivityEventType.ConfigChanged,
                        source = "settings.connectors.cli",
                        summary = "CLI connector defaults updated",
                        createdAt = now,
                      )
                    )
        yield htmlFragment(SettingsView.cliDefaultCard(saved, Map.empty).render)
      }
    },
    // Agent connector routes moved to AgentsController
    Method.POST / "settings" / "connectors"                   -> handler { (_: Request) =>
      // Backward compat: redirect old form POST
      ZIO.succeed(Response(
        status = Status.Found,
        headers = Headers(Header.Location(URL.decode("/settings/connectors").getOrElse(URL.root))),
      ))
    },
    Method.POST / "settings" / "ai"                           -> handler { (req: Request) =>
      // Backward-compatible: POST /settings/ai still works, saves ai.* keys
      ErrorHandlingMiddleware.fromPersistence {
        for
          form     <- parseForm(req)
          _        <- ZIO.foreachDiscard(settingsKeys.filter(_.startsWith("ai."))) { key =>
                        val value = form.getOrElse(key, "")
                        repository.upsertSetting(key, value)
                      }
          _        <- checkpointConfigStore
          rows     <- repository.getAllSettings
          saved     = rows.map(r => r.key -> r.value).toMap
          newConfig = SettingsApplier.toGatewayConfig(saved)
          _        <- configRef.set(newConfig)
          _        <- writeSettingsSnapshot(saved)
          now      <- Clock.instant
          _        <- activityHub.publish(
                        ActivityEvent(
                          id = EventId.generate,
                          eventType = ActivityEventType.ConfigChanged,
                          source = "settings.ai",
                          summary = "AI settings updated",
                          createdAt = now,
                        )
                      )
        yield html(HtmlViews.settingsConnectorsTab(saved, flash = Some("AI settings saved.")))
      }
    },
    Method.GET / "settings" / "gateway"                       -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        for
          rows    <- repository.getAllSettings
          settings = rows.map(r => r.key -> r.value).toMap
        yield html(HtmlViews.settingsGatewayTab(settings))
      }
    },
    Method.POST / "settings" / "gateway"                      -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form     <- parseForm(req)
          keys      =
            settingsKeys.filter(k =>
              k.startsWith("gateway.") || k.startsWith("telegram.") || k.startsWith("memory.") || k.startsWith(
                "prompts."
              )
            )
          _        <- ZIO.foreachDiscard(keys) { key =>
                        val value = key match
                          case "gateway.dryRun" | "gateway.verbose" | "telegram.enabled" | "memory.enabled" |
                               "prompts.reloading" =>
                            if form.get(key).exists(v => v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true")) then "true"
                            else "false"
                          case _ => form.getOrElse(key, "")
                        repository.upsertSetting(key, value)
                      }
          _        <- checkpointConfigStore
          rows     <- repository.getAllSettings
          saved     = rows.map(r => r.key -> r.value).toMap
          newConfig = SettingsApplier.toGatewayConfig(saved)
          _        <- configRef.set(newConfig)
          _        <- writeSettingsSnapshot(saved)
          now      <- Clock.instant
          _        <- activityHub.publish(
                        ActivityEvent(
                          id = EventId.generate,
                          eventType = ActivityEventType.ConfigChanged,
                          source = "settings.gateway",
                          summary = "Gateway settings updated",
                          createdAt = now,
                        )
                      )
        yield html(HtmlViews.settingsGatewayTab(saved, Some("Gateway settings saved.")))
      }
    },
    Method.GET / "settings" / "demo"                          -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        for
          rows    <- repository.getAllSettings
          settings = rows.map(r => r.key -> r.value).toMap
        yield html(HtmlViews.settingsDemoTab(settings))
      }
    },
    Method.POST / "settings" / "demo"                         -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val demoKeys = List(
          "demo.enabled",
          "demo.issueCount",
          "demo.agentDelaySeconds",
          "demo.repoBaseDir",
          "demo.previousProvider",
        )
        for
          form          <- parseForm(req)
          // Persist ai.provider=Mock when demo is being enabled
          enabled        = form.get("demo.enabled").exists(v => v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true"))
          // Store previous provider before overwriting (only on first enable)
          rows          <- repository.getAllSettings
          saved0         = rows.map(r => r.key -> r.value).toMap
          providerSwitch =
            if enabled && saved0.get("ai.provider").exists(_ != "Mock") then
              repository.upsertSetting("demo.previousProvider", saved0.getOrElse("ai.provider", "GeminiCli")) *>
                repository.upsertSetting("ai.provider", "Mock")
            else if !enabled && saved0.get("demo.previousProvider").exists(_.nonEmpty) then
              repository.upsertSetting("ai.provider", saved0("demo.previousProvider"))
            else
              ZIO.unit
          _             <- providerSwitch
          _             <- ZIO.foreachDiscard(demoKeys) { key =>
                             val value = key match
                               case "demo.enabled" =>
                                 if enabled then "true" else "false"
                               case _              => form.getOrElse(key, "")
                             repository.upsertSetting(key, value)
                           }
          _             <- checkpointConfigStore
          rows2         <- repository.getAllSettings
          saved          = rows2.map(r => r.key -> r.value).toMap
          newCfg         = SettingsApplier.toGatewayConfig(saved)
          _             <- configRef.set(newCfg)
          _             <- writeSettingsSnapshot(saved)
        yield html(HtmlViews.settingsDemoTab(saved, Some("Demo settings saved.")))
      }
    },
  )

  private def debugDataStore(includeConfig: Boolean, prefixFilter: Option[String]): IO[PersistenceError, Response] =
    for
      allDataKeys   <- dataStoreService.streamKeys[String]
                         .runCollect
                         .map(_.toList.sorted)
                         .mapError(err => PersistenceError.QueryFailed("debug_data_store", err.toString))
      dataKeys       = prefixFilter match
                         case Some(prefix) => allDataKeys.filter(_.startsWith(prefix))
                         case None         => allDataKeys
      dataEntries   <- ZIO.foreach(dataKeys)(debugDataEntryForKey)
      dataPrefixes   = countByPrefix(dataKeys)
      dataStoreSize <- computeDirectorySize(Paths.get(storeConfig.dataStorePath))
      configBlock   <-
        if includeConfig then
          for
            allConfigKeys <- configStoreService.streamKeys[String]
                               .runCollect
                               .map(_.toList.sorted)
                               .mapError(err => PersistenceError.QueryFailed("debug_config_store", err.toString))
            configKeys     = prefixFilter match
                               case Some(prefix) => allConfigKeys.filter(_.startsWith(prefix))
                               case None         => allConfigKeys
            configEntries <- ZIO.foreach(configKeys)(debugConfigEntryForKey)
            configPrefixes = countByPrefix(configKeys)
            configSize    <- computeDirectorySize(Paths.get(storeConfig.configStorePath))
          yield Some((configKeys.size, configEntries, configPrefixes, configSize))
        else ZIO.succeed(None)
    yield Response.json(
      StoreDebugResponse(
        dataStorePath = storeConfig.dataStorePath,
        dataStoreInstanceId = Integer.toHexString(java.lang.System.identityHashCode(dataStoreService)),
        dataStoreBytes = dataStoreSize,
        dataStoreKeyCount = dataKeys.size,
        dataStorePrefixCounts = dataPrefixes,
        dataEntries = dataEntries,
        configStorePath = configBlock.map(_ => storeConfig.configStorePath),
        configStoreInstanceId = configBlock.map(_ =>
          Integer.toHexString(java.lang.System.identityHashCode(configStoreService))
        ),
        configStoreBytes = configBlock.map(_._4),
        configStoreKeyCount = configBlock.map(_._1),
        configStorePrefixCounts = configBlock.map(_._3),
        configEntries = configBlock.map(_._2),
        appliedPrefix = prefixFilter,
      ).toJson
    )

  private def debugDataEntryForKey(key: String): UIO[StoreDebugEntry] =
    debugRawEntry(key = key, fetchFn = dataStoreService.fetchRawJson, decoder = decodeDataRaw)

  private def debugConfigEntryForKey(key: String): UIO[StoreDebugEntry] =
    debugRawEntry(key = key, fetchFn = configStoreService.fetchRawJson, decoder = decodeConfigRaw)

  private type FetchFn = String => IO[EclipseStoreError, Option[String]]

  private def debugRawEntry(
    key: String,
    fetchFn: FetchFn,
    decoder: (FetchFn, String) => UIO[(Option[String], Option[String])],
  ): UIO[StoreDebugEntry] =
    decoder(fetchFn, key).map {
      case (value, error) =>
        StoreDebugEntry(
          key = key,
          prefix = keyPrefix(key),
          rawValue = value,
          error = error,
        )
    }

  private def decodeDataRaw(fetchFn: FetchFn, key: String): UIO[(Option[String], Option[String])] =
    keyPrefix(key) match
      case "conv"       => fetchRaw(fetchFn, key)
      case "msg"        => fetchRaw(fetchFn, key)
      case "issue"      => fetchRaw(fetchFn, key)
      case "assignment" => fetchRaw(fetchFn, key)
      case "session"    => fetchRaw(fetchFn, key)
      case "activity"   => fetchRaw(fetchFn, key)
      case "run"        => fetchRaw(fetchFn, key)
      case "report"     => fetchRaw(fetchFn, key)
      case "artifact"   => fetchRaw(fetchFn, key)
      case "setting"    => fetchRaw(fetchFn, key)
      case other        => ZIO.succeed((None, Some(s"unknown data-store prefix: $other")))

  private def decodeConfigRaw(fetchFn: FetchFn, key: String): UIO[(Option[String], Option[String])] =
    keyPrefix(key) match
      case "setting"  => fetchRaw(fetchFn, key)
      case "workflow" => fetchRaw(fetchFn, key)
      case "agent"    => fetchRaw(fetchFn, key)
      case other      => ZIO.succeed((None, Some(s"unknown config-store prefix: $other")))

  private def fetchRaw(fetchFn: FetchFn, key: String): UIO[(Option[String], Option[String])] =
    fetchFn(key).map {
      case Some(value) => (Some(value), None)
      case None        => (None, Some("key present but no value returned by fetch"))
    }.catchAll(err =>
      ZIO.succeed(
        (
          None,
          Some(err.toString),
        )
      )
    )

  private def keyPrefix(key: String): String =
    val idx = key.indexOf(':')
    if idx <= 0 then "(none)" else key.substring(0, idx)

  private def countByPrefix(keys: List[String]): Map[String, Int] =
    keys.groupMapReduce(keyPrefix)(_ => 1)(_ + _).toList.sortBy(_._1).toMap

  private def computeDirectorySize(path: java.nio.file.Path): IO[PersistenceError, Long] =
    ZIO
      .attemptBlocking {
        if !Files.exists(path) then 0L
        else
          val stream = Files.walk(path)
          try
            stream.iterator.asScala
              .filter(Files.isRegularFile(_))
              .map(Files.size(_))
              .sum
          finally stream.close()
      }
      .mapError(err =>
        PersistenceError.QueryFailed("debug_data_store_size", Option(err.getMessage).getOrElse(err.toString))
      )

  private def resetDataStore: IO[PersistenceError, Unit] =
    for
      _ <- ZIO
             .attemptBlocking(Files.createDirectories(Paths.get(storeConfig.dataStorePath)))
             .mapError(err =>
               PersistenceError.QueryFailed("resetDataStore", Option(err.getMessage).getOrElse(err.toString))
             )
      _ <- safeReset("memoryEntries")(memoryEntriesStore.map.clear)
      _ <- safeReset("reloadRoots")(dataStoreService.checkpoint)
    yield ()

  private def safeReset(name: String)(effect: ZIO[Any, Any, Unit]): UIO[Unit] =
    effect.catchAll(err => ZIO.logWarning(s"data reset step '$name' failed: $err"))

  private def checkpointConfigStore: IO[PersistenceError, Unit] =
    configStoreService.checkpoint
      .mapError(err => PersistenceError.QueryFailed("config_checkpoint", err.toString))

  private def writeSettingsSnapshot(settings: Map[String, String]): IO[PersistenceError, Unit] =
    ZIO
      .attemptBlocking {
        val configStorePath = Paths.get(storeConfig.configStorePath)
        Files.createDirectories(configStorePath)
        val snapshot        = configStorePath.resolve("settings.snapshot.json")
        Files.writeString(
          snapshot,
          settings.toJson,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE,
        )
      }
      .mapError(err =>
        PersistenceError.QueryFailed("settings_snapshot", Option(err.getMessage).getOrElse(err.toString))
      )
      .unit

  private def testAIConnection(req: Request): UIO[Response] =
    val test =
      for
        form     <- parseForm(req)
        aiConfig <- ZIO.fromOption(SettingsApplier.toProviderConfig(form)).orElseFail("No AI provider configured")
        start    <- Clock.nanoTime
        _        <- Streaming.collect(llmService.executeStream("Say 'pong'")).unit
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
      case LlmError.TurnLimitError(limit)        => s"Turn limit exceeded${limit.map(l => s" (limit: $l)").getOrElse("")}"

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

  private def htmlFragment(bodyContent: String): Response =
    Response.text(bodyContent).contentType(MediaType.text.html)
