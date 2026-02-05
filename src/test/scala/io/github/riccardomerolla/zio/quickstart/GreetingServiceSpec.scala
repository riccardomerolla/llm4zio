package io.github.riccardomerolla.zio.quickstart

import io.github.riccardomerolla.zio.quickstart.config.QuickstartConfig
import io.github.riccardomerolla.zio.quickstart.error.QuickstartError
import io.github.riccardomerolla.zio.quickstart.service.GreetingService
import zio.{ZIO, ZLayer}
import zio.test.{ZIOSpecDefault, assertTrue}

object GreetingServiceSpec extends ZIOSpecDefault:
  private val baseConfig: ZLayer[Any, Nothing, QuickstartConfig] =
    ZLayer.fromZIO(
      QuickstartConfig
        .make("Welcome, {name}!", "friend")
        .foldZIO(error => ZIO.dieMessage(error.toString), ZIO.succeed(_))
    )

  private val serviceLayer: ZLayer[Any, Nothing, GreetingService] =
    ZLayer.make[GreetingService](
      baseConfig,
      GreetingService.live
    )

  override def spec =
    suite("GreetingService")(
      test("greets explicit audience") {
        for
          greeting <- GreetingService.greet(Some("ZIO"))
        yield assertTrue(greeting.rendered == "Welcome, ZIO!")
      },
      test("falls back to config when missing audience") {
        for
          greeting <- GreetingService.greet(None)
        yield assertTrue(greeting.rendered == "Welcome, friend!")
      },
      test("fails on invalid audience value") {
        for
          result <- GreetingService.greet(Some("   ")).either
        yield assertTrue(
          result match
            case Left(_: QuickstartError.InvalidAudience) => true
            case _                                       => false
        )
      }
    ).provideLayerShared(serviceLayer)
