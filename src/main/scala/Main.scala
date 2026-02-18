import java.nio.file.{ Path, Paths }

import zio.*
import zio.Console.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text
import zio.logging.backend.SLF4J

import _root_.config.ConfigLoader
import di.ApplicationDI
import models.MigrationConfig
import web.WebServer

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private type ServeOpts = (Option[BigInt], Option[String], Option[String])

  private val portOpt   = Options.integer("port").optional ?? "HTTP server port (default: 8080)"
  private val hostOpt   = Options.text("host").optional ?? "HTTP server host (default: 0.0.0.0)"
  private val dbPathOpt = Options.text("db").optional ?? "SQLite DB path (default: ./gateway.db)"

  private val serveCmd: Command[ServeOpts] =
    Command("serve", portOpt ++ hostOpt ++ dbPathOpt)
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
      dbPath = Paths.get(opts._3.getOrElse("./gateway.db")),
    )
  }

  private def executeServe(port: Int, host: String, dbPath: Path): ZIO[Any, Throwable, Unit] =
    for
      baseConfig <- loadConfig
      validated  <- ConfigLoader.validate(baseConfig).mapError(msg => new IllegalArgumentException(msg))
      _          <- printLine(s"Starting web server on http://$host:$port")
      _          <- WebServer.start(host, port).provide(ApplicationDI.webServerLayer(validated, dbPath))
    yield ()

  private def loadConfig: ZIO[Any, Throwable, MigrationConfig] =
    ConfigLoader
      .loadWithEnvOverrides
      .orElseSucceed(MigrationConfig(sourceDir = Paths.get("."), outputDir = Paths.get("./workspace/output")))

  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    for
      args <- ZIOAppArgs.getArgs
      _    <- cliApp.run(args.toList).orDie
    yield ()
