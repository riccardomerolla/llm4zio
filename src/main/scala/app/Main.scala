package app

import java.nio.file.{ Path, Paths }

import zio.*
import zio.logging.backend.SLF4J

import _root_.config.ConfigLoader
import app.boundary.WebServer
import shared.store.StoreConfig

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private val defaultStateRoot: Path =
    Paths.get(sys.props.getOrElse("user.home", ".")).resolve(".llm4zio-gateway").resolve("data")

  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    for
      port       <- System.envOrElse("GATEWAY_PORT", "8080").map(_.toInt)
      host       <- System.envOrElse("GATEWAY_HOST", "0.0.0.0")
      statePath  <- System.envOrElse("GATEWAY_STATE", defaultStateRoot.toString)
      storeConfig = buildStoreConfig(Paths.get(statePath))
      baseConfig <- loadConfig
      config     <- ConfigLoader.validate(baseConfig).mapError(msg => new IllegalArgumentException(msg))
      _          <- ZIO.logInfo(s"Starting web server on http://$host:$port")
      _          <- ZIO.logInfo(s"Store root: ${Paths.get(storeConfig.dataStorePath).getParent.toAbsolutePath}")
      _          <- ZIO.logInfo(s"Config store: ${Paths.get(storeConfig.configStorePath).toAbsolutePath}")
      _          <- ZIO.logInfo(s"Data store: ${Paths.get(storeConfig.dataStorePath).toAbsolutePath}")
      _          <- WebServer.start(host, port).provide(ApplicationDI.webServerLayer(config, storeConfig))
    yield ()

  private def buildStoreConfig(root: Path): StoreConfig =
    StoreConfig(
      configStorePath = root.resolve("config-store").toString,
      dataStorePath = root.resolve("data-store").toString,
    )

  private def loadConfig =
    ConfigLoader
      .loadWithEnvOverrides
      .orElseSucceed(_root_.config.entity.GatewayConfig())
