package config.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths, StandardOpenOption }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.http.*
import zio.json.*
import zio.schema.Schema

import _root_.config.SettingsApplier
import _root_.config.control.ModelService
import _root_.config.entity.GatewayConfig
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import db.*
import io.github.riccardomerolla.zio.eclipsestore.schema.TypedStore
import io.github.riccardomerolla.zio.eclipsestore.service.{ LifecycleCommand, LifecycleStatus }
import llm4zio.core.{ LlmError, LlmService }
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
        DataStoreModule.DataStoreService & StoreConfig &
        MemoryStoreModule.MemoryEntriesStore,
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
  dataStoreService: DataStoreModule.DataStoreService,
  storeConfig: StoreConfig,
  memoryEntriesStore: MemoryStoreModule.MemoryEntriesStore,
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
  )

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
    Method.GET / "settings"                      -> handler {
      ZIO.succeed(Response(
        status = Status.Found,
        headers = Headers(Header.Location(URL.decode("/settings/ai").getOrElse(URL.root))),
      ))
    },
    Method.POST / "settings"                     -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form     <- parseForm(req)
          _        <- ZIO.foreachDiscard(settingsKeys) { key =>
                        val value = key match
                          case "gateway.dryRun" | "gateway.verbose" | "telegram.enabled" | "memory.enabled" =>
                            if form.get(key).exists(_.equalsIgnoreCase("on")) then "true" else "false"
                          case _                                                                            =>
                            form.getOrElse(key, "")
                        if value.nonEmpty || key.startsWith("ai.") || key.startsWith("gateway.") || key.startsWith(
                            "telegram."
                          ) || key
                            .startsWith("memory.")
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
    Method.POST / "api" / "store" / "reset-data" -> handler { (_: Request) =>
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
    Method.GET / "api" / "store" / "debug-data"  -> handler { (req: Request) =>
      val includeConfig = req.queryParam("includeConfig").exists(_.trim.equalsIgnoreCase("true"))
      val prefixFilter  = req.queryParam("prefix").map(_.trim).filter(_.nonEmpty)
      ErrorHandlingMiddleware.fromPersistence(debugDataStore(includeConfig, prefixFilter))
    },
    Method.POST / "api" / "settings" / "test-ai" -> handler { (req: Request) =>
      testAIConnection(req)
    },
    Method.GET / "api" / "models"                -> handler {
      modelService.listAvailableModels.map(models => Response.json(models.toJson))
    },
    Method.GET / "api" / "models" / "status"     -> handler {
      modelService.probeProviders.map(status => Response.json(status.toJson))
    },
    Method.GET / "models"                        -> handler {
      ZIO.succeed(Response(
        status = Status.Found,
        headers = Headers(Header.Location(URL.decode("/settings/ai").getOrElse(URL.root))),
      ))
    },
    Method.GET / "settings" / "ai"               -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        for
          rows    <- repository.getAllSettings
          settings = rows.map(r => r.key -> r.value).toMap
          models  <- modelService.listAvailableModels
          status  <- modelService.probeProviders
        yield html(HtmlViews.settingsAiTab(settings, models, status))
      }
    },
    Method.POST / "settings" / "ai"              -> handler { (req: Request) =>
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
          models   <- modelService.listAvailableModels
          status   <- modelService.probeProviders
        yield html(HtmlViews.settingsAiTab(saved, models, status, Some("AI settings saved.")))
      }
    },
    Method.GET / "settings" / "gateway"          -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        for
          rows    <- repository.getAllSettings
          settings = rows.map(r => r.key -> r.value).toMap
        yield html(HtmlViews.settingsGatewayTab(settings))
      }
    },
    Method.POST / "settings" / "gateway"         -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form     <- parseForm(req)
          keys      =
            settingsKeys.filter(k => k.startsWith("gateway.") || k.startsWith("telegram.") || k.startsWith("memory."))
          _        <- ZIO.foreachDiscard(keys) { key =>
                        val value = key match
                          case "gateway.dryRun" | "gateway.verbose" | "telegram.enabled" | "memory.enabled" =>
                            if form.get(key).exists(v => v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true")) then "true"
                            else "false"
                          case _                                                                            => form.getOrElse(key, "")
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
  )

  private def debugDataStore(includeConfig: Boolean, prefixFilter: Option[String]): IO[PersistenceError, Response] =
    for
      allDataKeys   <- dataStoreService.rawStore
                         .streamKeys[String]
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
            allConfigKeys <- configStoreService.rawStore
                               .streamKeys[String]
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
        dataStoreInstanceId = Integer.toHexString(java.lang.System.identityHashCode(dataStoreService.rawStore)),
        dataStoreBytes = dataStoreSize,
        dataStoreKeyCount = dataKeys.size,
        dataStorePrefixCounts = dataPrefixes,
        dataEntries = dataEntries,
        configStorePath = configBlock.map(_ => storeConfig.configStorePath),
        configStoreInstanceId = configBlock.map(_ =>
          Integer.toHexString(java.lang.System.identityHashCode(configStoreService.rawStore))
        ),
        configStoreBytes = configBlock.map(_._4),
        configStoreKeyCount = configBlock.map(_._1),
        configStorePrefixCounts = configBlock.map(_._3),
        configEntries = configBlock.map(_._2),
        appliedPrefix = prefixFilter,
      ).toJson
    )

  private def debugDataEntryForKey(key: String): UIO[StoreDebugEntry] =
    debugRawEntry(key = key, typedStore = dataStoreService.store, decoder = decodeDataRaw)

  private def debugConfigEntryForKey(key: String): UIO[StoreDebugEntry] =
    debugRawEntry(key = key, typedStore = configStoreService.store, decoder = decodeConfigRaw)

  private def debugRawEntry(
    key: String,
    typedStore: TypedStore,
    decoder: (TypedStore, String) => UIO[(Option[String], Option[String])],
  ): UIO[StoreDebugEntry] =
    decoder(typedStore, key).map {
      case (value, error) =>
        StoreDebugEntry(
          key = key,
          prefix = keyPrefix(key),
          rawValue = value,
          error = error,
        )
    }

  private def decodeDataRaw(typedStore: TypedStore, key: String): UIO[(Option[String], Option[String])] =
    keyPrefix(key) match
      case "conv"       => fetchAs[ConversationRow](typedStore, key)
      case "msg"        => fetchAs[ChatMessageRow](typedStore, key)
      case "issue"      => fetchAs[AgentIssueRow](typedStore, key)
      case "assignment" => fetchAs[AgentAssignmentRow](typedStore, key)
      case "session"    => fetchAs[SessionContextRow](typedStore, key)
      case "activity"   => fetchAs[ActivityEvent](typedStore, key)
      case "run"        => fetchAs[shared.store.TaskRunRow](typedStore, key)
      case "report"     => fetchAs[shared.store.TaskReportRow](typedStore, key)
      case "artifact"   => fetchAs[shared.store.TaskArtifactRow](typedStore, key)
      case "setting"    => fetchAs[String](typedStore, key)
      case other        => ZIO.succeed((None, Some(s"unknown data-store prefix: $other")))

  private def decodeConfigRaw(typedStore: TypedStore, key: String): UIO[(Option[String], Option[String])] =
    keyPrefix(key) match
      case "setting"  => fetchAs[String](typedStore, key)
      case "workflow" => fetchAs[shared.store.WorkflowRow](typedStore, key)
      case "agent"    => fetchAs[shared.store.CustomAgentRow](typedStore, key)
      case other      => ZIO.succeed((None, Some(s"unknown config-store prefix: $other")))

  private def fetchAs[V: Schema](typedStore: TypedStore, key: String): UIO[(Option[String], Option[String])] =
    typedStore.fetch[String, V](key).map {
      case Some(value) => (Some(value.toString), None)
      case None        => (None, Some("key present but no value returned by typed fetch"))
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
      _ <- safeReset("reloadRoots")(dataStoreService.rawStore.reloadRoots)
    yield ()

  private def safeReset(name: String)(effect: ZIO[Any, Any, Unit]): UIO[Unit] =
    effect.catchAll(err => ZIO.logWarning(s"data reset step '$name' failed: $err"))

  private def checkpointConfigStore: IO[PersistenceError, Unit] =
    for
      status <- configStoreService.rawStore
                  .maintenance(LifecycleCommand.Checkpoint)
                  .mapError(err => PersistenceError.QueryFailed("config_checkpoint", err.toString))
      _      <- status match
                  case LifecycleStatus.Failed(message) =>
                    ZIO.fail(PersistenceError.QueryFailed("config_checkpoint", s"checkpoint failed: $message"))
                  case _                               => ZIO.unit
    yield ()

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
