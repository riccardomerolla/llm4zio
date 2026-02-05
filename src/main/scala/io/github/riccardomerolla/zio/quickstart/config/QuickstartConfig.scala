package io.github.riccardomerolla.zio.quickstart.config

import io.github.riccardomerolla.zio.quickstart.domain.{Audience, GreetingTemplate}
import io.github.riccardomerolla.zio.quickstart.error.QuickstartError
import zio.{IO, Layer, ZIO, ZLayer}

final case class QuickstartConfig(
    template: GreetingTemplate,
    fallbackAudience: Audience
)

object QuickstartConfig:
  def make(template: String, fallbackAudience: String): IO[QuickstartError, QuickstartConfig] =
    for
      validatedTemplate <- ZIO
        .fromEither(GreetingTemplate.make(template))
        .mapError(identity[QuickstartError])
      validatedAudience <- ZIO
        .fromEither(Audience.fromString(fallbackAudience))
        .mapError(identity[QuickstartError])
    yield QuickstartConfig(validatedTemplate, validatedAudience)

  val default: IO[QuickstartError, QuickstartConfig] =
    make(
      template = "Hello, {name}! Welcome to your new ZIO project.",
      fallbackAudience = "friend"
    )

  def live(
      template: String,
      fallbackAudience: String
  ): Layer[QuickstartError, QuickstartConfig] =
    ZLayer.fromZIO(make(template, fallbackAudience))

  val defaultLayer: Layer[QuickstartError, QuickstartConfig] =
    ZLayer.fromZIO(default)
