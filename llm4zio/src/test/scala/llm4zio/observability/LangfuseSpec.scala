package llm4zio.observability

import zio.*
import zio.test.*

import llm4zio.core.LlmError
import llm4zio.providers.HttpClient

object LangfuseSpec extends ZIOSpecDefault:

  private final class RecordingHttpClient(calls: Ref[List[(String, String, Map[String, String])]]) extends HttpClient:
    override def postJson(
      url: String,
      body: String,
      headers: Map[String, String],
      timeout: Duration,
    ): ZIO[Any, LlmError, String] =
      calls.update(current => (url, body, headers) :: current).as("{}")

  def spec = suite("Langfuse")(
    test("disabled client is no-op") {
      val event = LangfuseEvent(correlationId = "c1", traceName = "trace", input = "in", output = Some("out"))
      for
        _ <- LangfuseClient.disabled.track(event)
      yield assertTrue(true)
    },
    test("http client sends trace payload") {
      for
        calls <- Ref.make(List.empty[(String, String, Map[String, String])])
        client = LangfuseClient.http(
                   LangfuseConfig(
                     baseUrl = "https://langfuse.example",
                     publicKey = "pk",
                     secretKey = "sk",
                     enabled = true,
                   ),
                   new RecordingHttpClient(calls),
                 )
        _ <- client.track(
               LangfuseEvent(
                 correlationId = "corr-1",
                 traceName = "llm.execute",
                 input = "prompt",
                 output = Some("response"),
                 metadata = Map("provider" -> "openai"),
               )
             )
        sent <- calls.get
      yield assertTrue(
        sent.nonEmpty,
        sent.head._1.contains("/api/public/traces"),
        sent.head._2.contains("llm.execute"),
        sent.head._3.contains("Authorization"),
      )
    },
  )
