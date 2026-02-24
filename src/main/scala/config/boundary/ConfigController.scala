package config.boundary

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path as JPath, Paths, StandardOpenOption }
import java.util.UUID

import zio.*
import zio.http.*
import zio.json.*

import _root_.config.control.ConfigValidator
import _root_.config.entity.*
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import shared.ids.Ids.EventId
import shared.web.HtmlViews

trait ConfigController:
  def routes: Routes[Any, Response]

object ConfigController:

  def routes: ZIO[ConfigController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[ConfigController](_.routes)

  val live: ZLayer[MigrationConfig & ConfigValidator & ActivityHub, Nothing, ConfigController] =
    ZLayer.fromZIO {
      for
        migrationConfig <- ZIO.service[MigrationConfig]
        validator       <- ZIO.service[ConfigValidator]
        activityHub     <- ZIO.service[ActivityHub]
        state           <- ConfigControllerState.initialize(migrationConfig)
      yield ConfigControllerLive(migrationConfig, validator, activityHub, state)
    }

final case class ConfigControllerState(
  configPathRef: Ref[JPath],
  formatRef: Ref[ConfigFormat],
  configRef: Ref[MigrationConfig],
  historyRef: Ref[List[ConfigHistoryEntry]],
  historyDir: JPath,
)

object ConfigControllerState:

  def initialize(initial: MigrationConfig): UIO[ConfigControllerState] =
    for
      cwd        <- ZIO.attemptBlocking(Paths.get("").toAbsolutePath.normalize()).orDie
      defaultConf = cwd.resolve("application.conf")
      defaultJson = cwd.resolve("application.json")
      target     <- ZIO.attemptBlocking {
                      if Files.exists(defaultConf) then defaultConf
                      else if Files.exists(defaultJson) then defaultJson
                      else initial.stateDir.resolve("migration.editor.conf")
                    }.orDie
      format      = if target.toString.endsWith(".json") then ConfigFormat.Json else ConfigFormat.Hocon
      _          <- ZIO.attemptBlocking(Files.createDirectories(target.getParent)).orDie
      _          <- ZIO.whenZIO(ZIO.attemptBlocking(!Files.exists(target)).orDie) {
                      val bootstrap = ConfigControllerLive.renderAsHocon(initial)
                      ZIO.attemptBlocking(
                        Files.writeString(
                          target,
                          bootstrap,
                          StandardCharsets.UTF_8,
                          StandardOpenOption.CREATE,
                          StandardOpenOption.TRUNCATE_EXISTING,
                        )
                      ).orDie.unit
                    }
      historyDir  = initial.stateDir.resolve("config-history")
      _          <- ZIO.attemptBlocking(Files.createDirectories(historyDir)).orDie
      pathRef    <- Ref.make(target)
      formatRef  <- Ref.make(format)
      configRef  <- Ref.make(initial)
      historyRef <- Ref.make(List.empty[ConfigHistoryEntry])
    yield ConfigControllerState(pathRef, formatRef, configRef, historyRef, historyDir)

final case class ConfigControllerLive(
  migrationConfig: MigrationConfig,
  validator: ConfigValidator,
  activityHub: ActivityHub,
  state: ConfigControllerState,
) extends ConfigController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "settings" / "advanced"                                   -> handler {
      ZIO.succeed(html(HtmlViews.settingsAdvancedTab))
    },
    Method.GET / "config"                                                  -> handler {
      ZIO.succeed(Response(
        status = Status.Found,
        headers = Headers(Header.Location(URL.decode("/settings/advanced").getOrElse(URL.root))),
      ))
    },
    Method.GET / "api" / "config" / "current"                              -> handler {
      currentDocumentResponse
    },
    Method.GET / "api" / "config" / "history"                              -> handler {
      state.historyRef.get.map(items => Response.json(items.toJson))
    },
    Method.POST / "api" / "config" / "validate"                            -> handler { (req: Request) =>
      parseBody[ConfigContentRequest](req)
        .flatMap(payload =>
          validator.validate(payload.content, payload.format).map(result => Response.json(result.toJson))
        )
        .catchAll(toBadRequest)
    },
    Method.POST / "api" / "config" / "diff"                                -> handler { (req: Request) =>
      parseBody[ConfigDiffRequest](req)
        .map(payload => Response.json(buildDiff(payload.original, payload.updated).toJson))
        .catchAll(toBadRequest)
    },
    Method.POST / "api" / "config" / "save"                                -> handler { (req: Request) =>
      parseBody[ConfigContentRequest](req).flatMap(saveConfig).catchAll(toBadRequest)
    },
    Method.POST / "api" / "config" / "reload"                              -> handler {
      reloadConfig
    },
    Method.POST / "api" / "config" / "history" / string("id") / "rollback" -> handler {
      (id: String, _: Request) => rollback(id)
    },
  )

  private def currentDocumentResponse: UIO[Response] =
    currentDocument.map(doc => Response.json(doc.toJson)).catchAll(toBadRequest)

  private def saveConfig(payload: ConfigContentRequest): UIO[Response] =
    validator.validateAndParse(payload.content, payload.format).either.flatMap {
      case Left(validation) =>
        Clock.instant.map { now =>
          val response = ConfigSaveResponse(
            saved = false,
            reloaded = false,
            message = "Validation failed",
            validation = validation,
            current = ConfigDocument("", payload.format, payload.content, now),
          )
          Response.json(response.toJson).status(Status.BadRequest)
        }
      case Right(parsed)    =>
        (for
          previousDoc <- currentDocument
          _           <- backupCurrent(previousDoc)
          nextPath    <- updateConfigPath(payload.format)
          _           <- writeText(nextPath, payload.content)
          now         <- Clock.instant
          _           <- state.formatRef.set(payload.format)
          _           <- state.configRef.set(parsed)
          _           <- activityHub.publish(
                           ActivityEvent(
                             id = EventId.generate,
                             eventType = ActivityEventType.ConfigChanged,
                             source = "config-editor",
                             summary = "Gateway configuration updated and hot reloaded",
                             createdAt = now,
                           )
                         )
          current     <- currentDocument
          ok           = ConfigSaveResponse(
                           saved = true,
                           reloaded = true,
                           message = "Configuration saved",
                           validation = ConfigValidationResult(valid = true, issues = Nil),
                           current = current,
                         )
        yield Response.json(ok.toJson)).catchAll(toBadRequest)
    }

  private def reloadConfig: UIO[Response] =
    (for
      path    <- state.configPathRef.get
      format  <- state.formatRef.get
      content <- readText(path)
      parsed  <- validator.validateAndParse(content, format).mapError(_.issues.map(_.message).mkString("; "))
      _       <- state.configRef.set(parsed)
      doc     <- currentDocument
    yield Response.json(doc.toJson)).catchAll(toBadRequest)

  private def rollback(id: String): UIO[Response] =
    (for
      entry   <- state.historyRef.get.flatMap(entries =>
                   ZIO.fromOption(entries.find(_.id == id)).orElseFail(s"Unknown history id: $id")
                 )
      content <- readText(Paths.get(entry.path))
      parsed  <- validator.validateAndParse(content, entry.format).mapError(_.issues.map(_.message).mkString("; "))
      path    <- updateConfigPath(entry.format)
      _       <- writeText(path, content)
      _       <- state.formatRef.set(entry.format)
      _       <- state.configRef.set(parsed)
      doc     <- currentDocument
    yield Response.json(doc.toJson)).catchAll(toBadRequest)

  private def currentDocument: IO[String, ConfigDocument] =
    for
      path         <- state.configPathRef.get
      format       <- state.formatRef.get
      content      <- readText(path)
      lastModified <- ZIO
                        .attemptBlocking(Files.getLastModifiedTime(path).toInstant)
                        .mapError(err => Option(err.getMessage).getOrElse("Cannot get file timestamp"))
    yield ConfigDocument(
      path = path.toAbsolutePath.toString,
      format = format,
      content = content,
      lastModified = lastModified,
    )

  private def backupCurrent(document: ConfigDocument): IO[String, Unit] =
    for
      now     <- Clock.instant
      ext      = document.format match
                   case ConfigFormat.Hocon => "conf"
                   case ConfigFormat.Json  => "json"
      id       = s"${now.toEpochMilli}-${UUID.randomUUID().toString.take(8)}"
      filename = s"$id.$ext"
      path     = state.historyDir.resolve(filename)
      _       <- writeText(path, document.content)
      size     = document.content.getBytes(StandardCharsets.UTF_8).length.toLong
      entry    = ConfigHistoryEntry(
                   id = id,
                   path = path.toAbsolutePath.toString,
                   savedAt = now,
                   format = document.format,
                   sizeBytes = size,
                 )
      _       <- state.historyRef.update(current => (entry :: current).take(100))
    yield ()

  private def updateConfigPath(format: ConfigFormat): IO[String, JPath] =
    for
      current <- state.configPathRef.get
      next     =
        format match
          case ConfigFormat.Hocon =>
            if current.toString.endsWith(".conf") then current else replaceExtension(current, ".conf")
          case ConfigFormat.Json  =>
            if current.toString.endsWith(".json") then current else replaceExtension(current, ".json")
      _       <- ZIO.attemptBlocking(Files.createDirectories(next.getParent)).mapError(err => err.getMessage)
      _       <- state.configPathRef.set(next)
    yield next

  private def replaceExtension(path: JPath, ext: String): JPath =
    val name     = path.getFileName.toString
    val baseName = name.lastIndexOf('.') match
      case -1 => name
      case n  => name.substring(0, n)
    path.getParent.resolve(baseName + ext)

  private def readText(path: JPath): IO[String, String] =
    ZIO
      .attemptBlocking(Files.readString(path, StandardCharsets.UTF_8))
      .mapError(err => Option(err.getMessage).getOrElse(s"Cannot read $path"))

  private def writeText(path: JPath, content: String): IO[String, Unit] =
    ZIO
      .attemptBlocking(
        Files.writeString(
          path,
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE,
        )
      )
      .mapError(err => Option(err.getMessage).getOrElse(s"Cannot write $path"))
      .unit

  private def parseBody[A: JsonDecoder](req: Request): IO[String, A] =
    req.body.asString.mapError(err => Option(err.getMessage).getOrElse("Cannot read request body")).flatMap { raw =>
      ZIO.fromEither(raw.fromJson[A]).mapError(parseErr => s"Invalid JSON payload: $parseErr")
    }

  private def buildDiff(original: String, updated: String): ConfigDiffResponse =
    val oldLines = original.replace("\r\n", "\n").split("\n", -1).toList
    val newLines = updated.replace("\r\n", "\n").split("\n", -1).toList
    val maxSize  = Math.max(oldLines.size, newLines.size)

    val lines = (0 until maxSize).toList.flatMap { i =>
      (oldLines.lift(i), newLines.lift(i)) match
        case (Some(a), Some(b)) if a == b => List(ConfigDiffLine("unchanged", a))
        case (Some(a), Some(b))           => List(ConfigDiffLine("removed", a), ConfigDiffLine("added", b))
        case (Some(a), None)              => List(ConfigDiffLine("removed", a))
        case (None, Some(b))              => List(ConfigDiffLine("added", b))
        case (None, None)                 => Nil
    }

    ConfigDiffResponse(
      lines = lines,
      added = lines.count(_.kind == "added"),
      removed = lines.count(_.kind == "removed"),
      unchanged = lines.count(_.kind == "unchanged"),
    )

  private def toBadRequest(error: String): UIO[Response] =
    ZIO.succeed(Response.text(error).status(Status.BadRequest))

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

object ConfigControllerLive:

  def renderAsHocon(config: MigrationConfig): String =
    val provider = config.resolvedProviderConfig
    s"""migration {
       |  source-dir = \"${config.sourceDir.toString}\"
       |  output-dir = \"${config.outputDir.toString}\"
       |  state-dir = \"${config.stateDir.toString}\"
       |  parallelism = ${config.parallelism}
       |  batch-size = ${config.batchSize}
       |  discovery-max-depth = ${config.discoveryMaxDepth}
       |
       |  ai {
       |    provider = \"${provider.provider.toString}\"
       |    model = \"${provider.model}\"
       |    timeout = ${provider.timeout.toSeconds}s
       |    max-retries = ${provider.maxRetries}
       |    requests-per-minute = ${provider.requestsPerMinute}
       |    burst-size = ${provider.burstSize}
       |    acquire-timeout = ${provider.acquireTimeout.toSeconds}s
       |  }
       |}
       |""".stripMargin
