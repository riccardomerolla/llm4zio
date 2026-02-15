package core

import zio.*
import zio.http.*
import zio.test.*

import models.AIError

object HttpAIClientSpec extends ZIOSpecDefault:

  private def response(
    status: Status,
    body: String,
    headers: Headers = Headers.empty,
  ): Response =
    Response(
      status = status,
      headers = headers,
      body = Body.fromString(body),
    )

  private def makeClient(execute: Request => Task[Response]): ULayer[HttpAIClient] =
    ZLayer.succeed(HttpAIClient.fromRequestExecutor(execute))

  def spec: Spec[Any, Any] = suite("HttpAIClientSpec")(
    test("returns body on 200 response") {
      val layer = makeClient(_ => ZIO.succeed(response(Status.Ok, """{"ok":true}""")))
      for
        result <- HttpAIClient.postJson(
                    "https://api.openai.com/v1/chat/completions",
                    """{"model":"gpt-4o"}""",
                    Map("Authorization" -> "Bearer token"),
                    5.seconds,
                  ).provide(layer)
      yield assertTrue(result == """{"ok":true}""")
    },
    test("maps 401 to AuthenticationFailed") {
      val layer = makeClient(_ => ZIO.succeed(response(Status.Unauthorized, "unauthorized")))
      for
        result <- HttpAIClient
                    .postJson(
                      "https://api.openai.com/v1/chat/completions",
                      """{"model":"gpt-4o"}""",
                      timeout = 5.seconds,
                    )
                    .provide(layer)
                    .either
      yield assertTrue(
        result.left.exists {
          case AIError.AuthenticationFailed(_) => true
          case _                               => false
        }
      )
    },
    test("maps 429 to RateLimitExceeded and reads Retry-After header") {
      val layer = makeClient(_ =>
        ZIO.succeed(
          response(
            Status.TooManyRequests,
            "rate limited",
            Headers(Header.Custom("Retry-After", "7")),
          )
        )
      )
      for
        result <- HttpAIClient
                    .postJson(
                      "https://api.openai.com/v1/chat/completions",
                      """{"model":"gpt-4o"}""",
                      timeout = 30.seconds,
                    )
                    .provide(layer)
                    .either
      yield assertTrue(
        result.left.exists {
          case AIError.RateLimitExceeded(d) => d.toSeconds == 7L
          case _                            => false
        }
      )
    },
    test("maps 500 to ProviderUnavailable") {
      val layer = makeClient(_ => ZIO.succeed(response(Status.InternalServerError, "upstream error")))
      for
        result <- HttpAIClient
                    .postJson(
                      "https://api.openai.com/v1/chat/completions",
                      """{"model":"gpt-4o"}""",
                      timeout = 5.seconds,
                    )
                    .provide(layer)
                    .either
      yield assertTrue(
        result.left.exists {
          case AIError.ProviderUnavailable(_, _) => true
          case _                                 => false
        }
      )
    },
    test("times out long-running requests") {
      val layer = makeClient(_ => ZIO.never)
      for
        fiber  <- HttpAIClient
                    .postJson(
                      "https://api.openai.com/v1/chat/completions",
                      """{"model":"gpt-4o"}""",
                      timeout = 100.millis,
                    )
                    .provide(layer)
                    .either
                    .fork
        _      <- TestClock.adjust(200.millis)
        result <- fiber.join
      yield assertTrue(
        result.left.exists {
          case AIError.Timeout(_) => true
          case _                  => false
        }
      )
    },
  )
