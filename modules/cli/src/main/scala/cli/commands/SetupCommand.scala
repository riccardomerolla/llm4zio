package cli.commands

import java.nio.file.{ Files, Path, Paths }

import zio.*

import _root_.config.entity.ConfigRepository
import shared.errors.PersistenceError

object SetupCommand:

  /** Bootstrap the llm4zio state dir and seed default settings. Idempotent: running twice is a no-op for already-set
    * keys. The state dir is created implicitly by CliStoreModule once any config repo call happens, but we eagerly
    * mkdir to give the user a clear confirmation path.
    */
  def setup(
    statePath: Path,
    gatewayUrl: String,
  ): ZIO[ConfigRepository, PersistenceError | Throwable, String] =
    for
      _           <- ZIO.attempt(Files.createDirectories(statePath))
      existing    <- ConfigRepository.getSetting("gateway.url")
      wroteGateway = existing.isEmpty
      _           <- ZIO.when(wroteGateway)(ConfigRepository.upsertSetting("gateway.url", gatewayUrl))
    yield
      val lines = List(
        s"✓ State root: ${statePath.toAbsolutePath}",
        if wroteGateway then s"✓ Seeded gateway.url = $gatewayUrl"
        else s"• gateway.url already set to ${existing.map(_.value).getOrElse("")}",
        "",
        "Next steps:",
        "  llm4zio-cli login <provider> --key <api-key>",
        "  llm4zio-cli workspace init <path>",
        "  llm4zio-cli info",
      )
      lines.mkString("\n")

  /** Overload that accepts a raw string, used by the Main handler. */
  def setup(
    statePathRaw: Option[String],
    defaultStateRoot: Path,
    gatewayUrl: String,
  ): ZIO[ConfigRepository, PersistenceError | Throwable, String] =
    val resolved = statePathRaw.map(s => Paths.get(s)).getOrElse(defaultStateRoot)
    setup(resolved.toAbsolutePath.normalize, gatewayUrl)
