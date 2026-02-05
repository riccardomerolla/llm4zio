package io.github.riccardomerolla.zio.quickstart.app

import io.github.riccardomerolla.zio.quickstart.config.QuickstartConfig
import io.github.riccardomerolla.zio.quickstart.service.GreetingService
import zio.{ZIO, ZIOAppDefault}

object QuickstartApp extends ZIOAppDefault:
  override def run =
    val program =
      for
        greeting <- GreetingService.greet(Some("ZIO developer"))
        _ <- ZIO.logInfo(greeting.rendered)
      yield ()

    program
      .tapError(e => ZIO.logError(s"Failed to build greeting: $e"))
      .provide(
        QuickstartConfig.defaultLayer,
        GreetingService.live
      )
