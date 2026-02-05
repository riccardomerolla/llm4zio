package io.github.riccardomerolla.zio.quickstart.service

import io.github.riccardomerolla.zio.quickstart.config.QuickstartConfig
import io.github.riccardomerolla.zio.quickstart.domain.{Audience, Greeting}
import io.github.riccardomerolla.zio.quickstart.error.QuickstartError
import zio.{IO, ULayer, ZIO, ZLayer}

trait GreetingService:
  def greet(target: Option[String]): IO[QuickstartError, Greeting]

final case class GreetingServiceLive(
    config: QuickstartConfig
) extends GreetingService:
  override def greet(target: Option[String]): IO[QuickstartError, Greeting] =
    for
      audience <- resolveAudience(target)
      _ <- ZIO.logInfo(s"Preparing greeting for ${audience.asString}")
      greeting <- ZIO.succeed(Greeting.fromTemplate(config.template, audience))
    yield greeting

  private def resolveAudience(target: Option[String]): IO[QuickstartError, Audience] =
    target match
      case Some(value) =>
        ZIO.fromEither(Audience.fromString(value)).mapError(identity[QuickstartError])
      case None =>
        ZIO.succeed(config.fallbackAudience)

object GreetingService:
  def greet(target: Option[String]): ZIO[GreetingService, QuickstartError, Greeting] =
    ZIO.serviceWithZIO[GreetingService](_.greet(target))

  val live: ZLayer[QuickstartConfig, Nothing, GreetingService] =
    ZLayer.fromFunction(GreetingServiceLive.apply)

  def inMemory(greeting: Greeting): ULayer[GreetingService] =
    ZLayer.succeed(new GreetingService:
      override def greet(target: Option[String]): IO[QuickstartError, Greeting] =
        ZIO.succeed(greeting)
    )
