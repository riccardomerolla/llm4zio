package cli.commands

import java.nio.file.{ Files, Path }

import zio.*
import zio.http.*

import _root_.config.entity.ConfigRepository
import shared.errors.PersistenceError

object InfoCommand:

  final case class Info(
    statePath: Path,
    stateExists: Boolean,
    gatewayUrl: String,
    gatewayReachable: Option[Boolean],
    providers: List[ProviderStatus],
    cliVersion: String,
  )

  final case class ProviderStatus(scope: String, provider: Option[String], apiKey: Option[String]):
    def render: String =
      val p  = provider.getOrElse("<unset>")
      val k  = apiKey.map(LoginCommand.maskKey).getOrElse("<unset>")
      s"  [$scope] provider=$p  key=$k"

  /** Gather state path, gateway reachability, provider-key status. */
  def info(
    statePath: Path,
    gatewayUrl: String,
    cliVersion: String,
  ): ZIO[ConfigRepository & Client, PersistenceError | Throwable, Info] =
    for
      stateExists <- ZIO.attempt(Files.isDirectory(statePath))
      rows        <- ConfigRepository.getAllSettings
      providers    = extractProviders(rows.map(r => r.key -> r.value).toMap)
      reachable   <- probeGateway(gatewayUrl).foldCause(_ => Some(false), _ => Some(true))
    yield Info(statePath, stateExists, gatewayUrl, reachable, providers, cliVersion)

  def render(info: Info): String =
    val gatewayLine = info.gatewayReachable match
      case Some(true)  => s"  ${info.gatewayUrl}  ✓ reachable"
      case Some(false) => s"  ${info.gatewayUrl}  ✗ unreachable"
      case None        => s"  ${info.gatewayUrl}  (not probed)"

    val providerBlock =
      if info.providers.isEmpty then "  (no provider credentials configured — run `llm4zio-cli login`)"
      else info.providers.map(_.render).mkString("\n")

    s"""|llm4zio-cli v${info.cliVersion}
        |
        |State:
        |  ${info.statePath.toAbsolutePath}  ${if info.stateExists then "✓ exists" else "✗ missing"}
        |
        |Gateway:
        |$gatewayLine
        |
        |Providers:
        |$providerBlock""".stripMargin

  private def probeGateway(gatewayUrl: String): ZIO[Client, Throwable, String] =
    for
      url      <- ZIO.fromEither(URL.decode(s"$gatewayUrl/api/health"))
                    .mapError(msg => new IllegalArgumentException(s"Invalid gateway URL: $msg"))
      response <- Client.batched(Request.get(url))
      body     <- response.body.asString
      _        <- ZIO
                    .fail(new RuntimeException(s"status ${response.status.code}"))
                    .when(!response.status.isSuccess)
    yield body

  private def extractProviders(settings: Map[String, String]): List[ProviderStatus] =
    val global = ProviderStatus(
      scope = "default",
      provider = settings.get("connector.default.provider"),
      apiKey = settings.get("connector.default.apiKey"),
    )
    val agentPattern = """agent\.([^.]+)\.connector\.(provider|apiKey)""".r
    val perAgent     = settings.keys.toList
      .flatMap {
        case agentPattern(name, _) => Some(name)
        case _                     => None
      }
      .distinct
      .sorted
      .map { name =>
        ProviderStatus(
          scope = s"agent=$name",
          provider = settings.get(s"agent.$name.connector.provider"),
          apiKey = settings.get(s"agent.$name.connector.apiKey"),
        )
      }
    val all = global :: perAgent
    all.filter(p => p.provider.isDefined || p.apiKey.isDefined) match
      case Nil => Nil
      case xs  => xs
