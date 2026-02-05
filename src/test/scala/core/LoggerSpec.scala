package core

import java.nio.file.Files

import zio.*
import zio.test.*

object LoggerSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] = suite("LoggerSpec")(
    suite("Simple Logger")(
      test("logs info message") {
        Logger.info("Test info message").as(assertCompletes)
      },
      test("logs debug message") {
        Logger.debug("Test debug message").as(assertCompletes)
      },
      test("logs warn message") {
        Logger.warn("Test warn message").as(assertCompletes)
      },
      test("logs error message") {
        Logger.error("Test error message").as(assertCompletes)
      },
      test("logs error with exception") {
        val exception = new RuntimeException("Test exception")
        Logger.error("Test error with exception", exception).as(assertCompletes)
      },
    ),
    suite("Logger Service")(
      test("creates console logger layer") {
        val effect =
          for
            _ <- Logger.service.info("Test message")
          yield assertCompletes

        effect.provide(Logger.console)
      },
      test("creates file logger layer") {
        val tempFile = Files.createTempFile("test-log", ".json")
        try
          val effect =
            for
              _ <- Logger.service.info("Test file log")
              _ <- TestClock.adjust(100.millis) // Allow write to complete
            yield assertCompletes

          effect.provide(Logger.file(tempFile.toString))
        finally
          Files.deleteIfExists(tempFile)
          ()
      },
      test("logger with context") {
        val effect =
          for
            logger       <- ZIO.service[Logger]
            contextLogger = logger.withRunId("test-run-123")
            _            <- contextLogger.info("Test with context")
          yield assertCompletes

        effect.provide(Logger.console)
      },
    ),
  )
