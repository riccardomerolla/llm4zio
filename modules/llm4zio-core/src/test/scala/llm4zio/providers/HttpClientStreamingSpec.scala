package llm4zio.providers

import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.LlmError

/** Real loopback server + real zio-http client: proves `postJsonStream` delivers chunks as the server emits them,
  * instead of buffering the whole body first (the `batched` client materializes streaming bodies before returning).
  */
object HttpClientStreamingSpec extends ZIOSpecDefault:

  /** POST /stream emits "first", then holds the body open until `gate` completes, then emits "second". */
  private def routes(gate: Promise[Nothing, Unit]) =
    Routes(
      Method.POST / "stream" -> handler { (_: Request) =>
        Response(
          status = Status.Ok,
          body = Body.fromStreamChunked(
            ZStream.fromChunk(Chunk.fromArray("first\n".getBytes)) ++
              ZStream.execute(gate.await) ++
              ZStream.fromChunk(Chunk.fromArray("second\n".getBytes))
          ),
        )
      }
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("HttpClient streaming (loopback)")(
    test("postJsonStream delivers the first chunk while the server still holds the body open") {
      for
        gate   <- Promise.make[Nothing, Unit]
        port   <- Server.install(routes(gate))
        client <- ZIO.service[HttpClient]
        first  <- client
                    .postJsonStream(s"http://localhost:$port/stream", "{}", Map.empty, timeout = 15.seconds)
                    .take(1)
                    .runCollect
                    .timeoutFail(LlmError.TimeoutError(5.seconds))(5.seconds)
        _      <- gate.succeed(())
      yield assertTrue(first.toList == List("first"))
    },
    test("postJsonStream delivers the whole body and completes once the server finishes") {
      for
        gate   <- Promise.make[Nothing, Unit]
        port   <- Server.install(routes(gate))
        client <- ZIO.service[HttpClient]
        _      <- gate.succeed(())
        lines  <- client
                    .postJsonStream(s"http://localhost:$port/stream", "{}", Map.empty, timeout = 15.seconds)
                    .runCollect
      yield assertTrue(lines.toList == List("first", "second"))
    },
  ).provide(
    HttpClient.live,
    HttpClient.reliableClient,
    Server.defaultWithPort(0),
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
