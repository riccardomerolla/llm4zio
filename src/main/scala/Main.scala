import java.nio.file.{ Path, Paths }

import zio.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text
import zio.logging.backend.SLF4J

import _root_.config.ConfigLoader
import di.ApplicationDI
import models.GatewayConfig
import store.StoreConfig
import web.WebServer

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private val defaultStateRoot: Path =
    Paths.get(sys.props.getOrElse("user.home", ".")).resolve(".llm4zio-gateway").resolve("data")

  private type ServeOpts = (Option[BigInt], Option[String], Option[String])

  private val portOpt      = Options.integer("port").optional ?? "HTTP server port (default: 8080)"
  private val hostOpt      = Options.text("host").optional ?? "HTTP server host (default: 0.0.0.0)"
  private val statePathOpt = Options.text("state").optional ??
    s"Store root path (default: ${defaultStateRoot.toAbsolutePath.toString})"

  private val serveCmd: Command[ServeOpts] =
    Command("serve", portOpt ++ hostOpt ++ statePathOpt)
      .withHelp("Start the web portal")

  private val cliApp = CliApp.make(
    name = "llm4zio-gateway",
    version = "1.0.0",
    summary = text("Self-hosted AI gateway"),
    command = serveCmd,
  ) { opts =>
    executeServe(
      port = opts._1.map(_.toInt).getOrElse(8080),
      host = opts._2.getOrElse("0.0.0.0"),
      storeConfig = buildStoreConfig(Paths.get(opts._3.getOrElse(defaultStateRoot.toString))),
    )
  }

  private def executeServe(port: Int, host: String, storeConfig: StoreConfig): ZIO[Any, Throwable, Unit] =
    for
      baseConfig <- loadConfig
      validated  <- ConfigLoader.validate(baseConfig).mapError(msg => new IllegalArgumentException(msg))
      _          <- ZIO.logInfo(s"Starting web server on http://$host:$port")
      _          <- ZIO.logInfo(s"Store root: ${Paths.get(storeConfig.dataStorePath).getParent.toAbsolutePath}")
      _          <- ZIO.logInfo(s"Config store: ${Paths.get(storeConfig.configStorePath).toAbsolutePath}")
      _          <- ZIO.logInfo(s"Data store: ${Paths.get(storeConfig.dataStorePath).toAbsolutePath}")
      _          <- WebServer.start(host, port).provide(ApplicationDI.webServerLayer(validated, storeConfig))
    yield ()

  private def buildStoreConfig(root: Path): StoreConfig =
    StoreConfig(
      configStorePath = root.resolve("config-store").toString,
      dataStorePath = root.resolve("data-store").toString,
    )

  private def loadConfig: ZIO[Any, Throwable, GatewayConfig] =
    ConfigLoader
      .loadWithEnvOverrides
      .orElseSucceed(GatewayConfig())

  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    for
      args <- ZIOAppArgs.getArgs
      _    <- cliApp.run(args.toList).orDie
    yield ()
