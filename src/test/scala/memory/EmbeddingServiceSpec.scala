package memory

import zio.*
import zio.test.*

import llm4zio.providers.HttpClient
import models.{ AIProvider, AIProviderConfig, GatewayConfig }

object EmbeddingServiceSpec extends ZIOSpecDefault:

  private val mockHttpLayer: ULayer[HttpClient] =
    ZLayer.succeed(
      new HttpClient:
        override def postJson(
          url: String,
          body: String,
          headers: Map[String, String],
          timeout: Duration,
        ): IO[llm4zio.core.LlmError, String] =
          if url.contains("/embeddings") then
            if body.contains("second") then ZIO.succeed("""{"data":[{"embedding":[0.3,0.4]}]}""")
            else ZIO.succeed("""{"data":[{"embedding":[0.1,0.2]}]}""")
          else ZIO.fail(llm4zio.core.LlmError.InvalidRequestError(s"Unexpected URL: $url"))
    )

  private val configLayer: ULayer[Ref[GatewayConfig]] =
    ZLayer.fromZIO(
      Ref.make(
        GatewayConfig(
          aiProvider = Some(
            AIProviderConfig(
              provider = AIProvider.OpenAi,
              baseUrl = Some("https://api.openai.com/v1"),
              apiKey = Some("test-key"),
            )
          )
        )
      )
    )

  private val embeddingLayer: ULayer[EmbeddingService] =
    configLayer ++ mockHttpLayer >>> EmbeddingService.live

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EmbeddingServiceSpec")(
      test("embed parses vector payload") {
        for
          service <- ZIO.service[EmbeddingService]
          vector  <- service.embed("first text")
        yield assertTrue(vector == Vector(0.1f, 0.2f))
      },
      test("embedBatch returns one embedding per text") {
        for
          service <- ZIO.service[EmbeddingService]
          vectors <- service.embedBatch(List("first text", "second text"))
        yield assertTrue(
          vectors.length == 2,
          vectors.head == Vector(0.1f, 0.2f),
          vectors(1) == Vector(0.3f, 0.4f),
        )
      },
    ).provideLayer(embeddingLayer)
