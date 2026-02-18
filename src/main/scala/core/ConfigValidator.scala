package core

import zio.*

import _root_.config.ConfigLoader
import com.typesafe.config.ConfigFactory
import models.{ ConfigFormat, ConfigValidationIssue, ConfigValidationResult, GatewayConfig }

trait ConfigValidator:
  def validate(content: String, format: ConfigFormat): UIO[ConfigValidationResult]
  def validateAndParse(content: String, format: ConfigFormat): IO[ConfigValidationResult, GatewayConfig]

object ConfigValidator:

  def validate(content: String, format: ConfigFormat): URIO[ConfigValidator, ConfigValidationResult] =
    ZIO.serviceWithZIO[ConfigValidator](_.validate(content, format))

  def validateAndParse(content: String, format: ConfigFormat)
    : ZIO[ConfigValidator, ConfigValidationResult, GatewayConfig] =
    ZIO.serviceWithZIO[ConfigValidator](_.validateAndParse(content, format))

  val live: ULayer[ConfigValidator] =
    ZLayer.succeed(ConfigValidatorLive())

final case class ConfigValidatorLive() extends ConfigValidator:

  override def validate(content: String, format: ConfigFormat): UIO[ConfigValidationResult] =
    validateAndParse(content, format).fold(identity, _ => ConfigValidationResult(valid = true, issues = Nil))

  override def validateAndParse(content: String, format: ConfigFormat): IO[ConfigValidationResult, GatewayConfig] =
    parseGatewayConfig(content).flatMap { parsed =>
      ConfigLoader
        .validate(parsed)
        .mapError(msg => ConfigValidationResult(valid = false, issues = List(ConfigValidationIssue(msg))))
    }

  private def parseGatewayConfig(content: String): IO[ConfigValidationResult, GatewayConfig] =
    val parsedEither =
      for
        parsed <- scala.util
                    .Try(ConfigFactory.parseString(content).resolve())
                    .toEither
                    .left
                    .map(err => Option(err.getMessage).getOrElse(err.getClass.getSimpleName))
        root    = if parsed.hasPath("gateway") then parsed.getConfig("gateway") else parsed
      yield GatewayConfig(
        dryRun = booleanOf(root, default = false, "dryRun", "dry-run"),
        verbose = booleanOf(root, default = false, "verbose"),
      )

    ZIO.fromEither(parsedEither).mapError { msg =>
      ConfigValidationResult(valid = false, issues = List(ConfigValidationIssue(msg)))
    }

  private def booleanOf(root: com.typesafe.config.Config, default: Boolean, keys: String*): Boolean =
    keys.collectFirst { case key if root.hasPath(key) => root.getBoolean(key) }.getOrElse(default)
