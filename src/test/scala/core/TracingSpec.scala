package core

import zio.*
import zio.test.*
import zio.test.Assertion.*

object TracingSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] = suite("TracingSpec")(
    suite("TracingService")(
      test("creates root span") {
        val effect =
          for
            _ <- TracingService.root("test-root-span") {
                   ZIO.succeed(42)
                 }
          yield ()

        effect.provide(TracingService.live()).as(assertCompletes)
      },
      test("creates child spans") {
        val effect =
          for
            _ <- TracingService.root("parent-span") {
                   for
                     _ <- TracingService.span("child-span-1")(ZIO.succeed(1))
                     _ <- TracingService.span("child-span-2")(ZIO.succeed(2))
                   yield ()
                 }
          yield ()

        effect.provide(TracingService.live()).as(assertCompletes)
      },
      test("sets span attributes") {
        val effect =
          for
            _ <- TracingService.root("attributed-span") {
                   for
                     _ <- TracingService.setAttribute("user.id", "123")
                     _ <- TracingService.setAttribute("operation", "test")
                     _ <- ZIO.succeed(42)
                   yield ()
                 }
          yield ()

        effect.provide(TracingService.live()).as(assertCompletes)
      },
      test("adds span events") {
        val effect =
          for
            _ <- TracingService.root("event-span") {
                   for
                     _ <- TracingService.addEvent("operation_started")
                     _ <- ZIO.succeed(42)
                     _ <- TracingService.addEvent("operation_completed")
                   yield ()
                 }
          yield ()

        effect.provide(TracingService.live()).as(assertCompletes)
      },
      test("adds events with attributes") {
        val effect =
          for
            _ <- TracingService.root("event-attrs-span") {
                   for
                     _ <- TracingService.addEvent("checkpoint", Map("step" -> "1", "status" -> "ok"))
                     _ <- ZIO.succeed(42)
                   yield ()
                 }
          yield ()

        effect.provide(TracingService.live()).as(assertCompletes)
      },
      test("records exceptions") {
        val exception = new RuntimeException("Test exception")
        val effect    =
          for
            _ <- TracingService.root("exception-span") {
                   for
                     _ <- ZIO.fail(exception).catchAll { err =>
                            TracingService.recordException(err)
                          }
                   yield ()
                 }
          yield ()

        effect.provide(TracingService.live()).as(assertCompletes)
      },
      test("nested spans create proper hierarchy") {
        val effect =
          for
            result <- TracingService.root("grandparent") {
                        TracingService.span("parent") {
                          TracingService.span("child") {
                            ZIO.succeed(100)
                          }
                        }
                      }
          yield result

        effect.provide(TracingService.live()).map(result => assertTrue(result == 100))
      },
      test("aspect-based tracing with @@ operator") {
        import TracingService.aspects as tracingAspects

        val effect = ZIO.succeed(42) @@ tracingAspects.root("aspect-root")

        effect.provide(TracingService.live()).map(result => assertTrue(result == 42))
      },
      test("combines multiple aspects") {
        import TracingService.aspects as tracingAspects

        val effect =
          (for
            _      <- TracingService.setAttribute("key", "value")
            result <- ZIO.succeed(123)
          yield result) @@ tracingAspects.root("multi-aspect") @@ tracingAspects.withAttribute("extra", "data")

        effect.provide(TracingService.live()).map(result => assertTrue(result == 123))
      },
    ),
    suite("Integration with real operations")(
      test("traces file operations") {
        val effect = TracingService.root("file-operation") {
          for
            _      <- TracingService.setAttribute("operation", "read")
            _      <- TracingService.addEvent("start_read")
            result <- ZIO.succeed("file content")
            _      <- TracingService.addEvent("end_read")
          yield result
        }

        effect.provide(TracingService.live()).map(result => assertTrue(result == "file content"))
      },
      test("traces with error handling") {
        val effect = TracingService.root("error-handling-span") {
          ZIO
            .fail(new RuntimeException("Expected failure"))
            .catchAll { err =>
              for
                _ <- TracingService.recordException(err)
                _ <- TracingService.setAttribute("error.handled", "true")
              yield "recovered"
            }
        }

        effect.provide(TracingService.live()).map(result => assertTrue(result == "recovered"))
      },
    ),
  )
