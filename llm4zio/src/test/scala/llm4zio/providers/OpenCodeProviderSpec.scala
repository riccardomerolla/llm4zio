package llm4zio.providers

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import llm4zio.core.*

object OpenCodeProviderSpec extends ZIOSpecDefault:

  case class Person(name: String, age: Int) derives JsonCodec

  private final case class CapturedRequest(
    url: String,
    body: String,
    headers: Map[String, String],
  )

  private final class MockHttpClient(
    postHandler: CapturedRequest => IO[LlmError, String],
    getHandler: (String, Map[String, String]) => IO[LlmError, String] = (_, _) => ZIO.succeed("{}"),
    capturedRef: Ref[List[CapturedRequest]],
  ) extends HttpClient:
    def captured: UIO[List[CapturedRequest]] = capturedRef.get

    override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      getHandler(url, headers)

    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      capturedRef.update(reqs => CapturedRequest(url, body, headers) :: reqs) *> postHandler(CapturedRequest(url, body, headers))

  private def mockHttpClient(
    postHandler: CapturedRequest => IO[LlmError, String],
    getHandler: (String, Map[String, String]) => IO[LlmError, String] = (_, _) => ZIO.succeed("{}"),
  ): UIO[MockHttpClient] =
    Ref.make(List.empty[CapturedRequest]).map(ref => new MockHttpClient(postHandler, getHandler, ref))

  private def config(
    baseUrl: Option[String] = Some("http://localhost:4096"),
    apiKey: Option[String] = Some("test-key"),
    model: String = "opencode-default",
  ): LlmConfig =
    LlmConfig(
      provider = LlmProvider.OpenCode,
      model = model,
      baseUrl = baseUrl,
      apiKey = apiKey,
      timeout = 5.seconds,
    )

  private def standardResponse(content: String = "Test response from OpenCode"): String =
    OpenCodeCompletionResponse(
      id = Some("chatcmpl-123"),
      choices = List(
        OpenCodeChoice(
          index = 0,
          message = Some(ChatMessage(role = "assistant", content = content)),
          finish_reason = Some("stop"),
        )
      ),
      usage = Some(OpenCodeTokenUsage(prompt_tokens = Some(10), completion_tokens = Some(5), total_tokens = Some(15))),
      model = Some("opencode-default"),
    ).toJson

  private def sseResponse: String =
    List(
      "data: " + OpenCodeCompletionResponse(
        choices = List(OpenCodeChoice(index = 0, delta = Some(OpenCodeDelta(content = Some("Hello"))), finish_reason = None)),
        usage = None,
        model = Some("opencode-default"),
      ).toJson,
      "",
      "data: " + OpenCodeCompletionResponse(
        choices = List(OpenCodeChoice(index = 0, delta = Some(OpenCodeDelta(content = Some(" world"))), finish_reason = Some("stop"))),
        usage = Some(OpenCodeTokenUsage(prompt_tokens = Some(8), completion_tokens = Some(3), total_tokens = Some(11))),
        model = Some("opencode-default"),
      ).toJson,
      "",
      "data: [DONE]",
    ).mkString("\n")

  def spec = suite("OpenCodeProvider")(
    test("execute maps successful API response") {
      for
        http <- mockHttpClient(req => ZIO.succeed(standardResponse()))
        provider = OpenCodeProvider.make(config(), http)
        response <- provider.execute("test prompt")
      yield assertTrue(
        response.content == "Test response from OpenCode",
        response.usage.exists(_.total == 15),
        response.metadata.get("provider").contains("opencode"),
        response.metadata.get("id").contains("chatcmpl-123"),
      )
    },
    test("executeStream parses SSE chunks") {
      for
        http <- mockHttpClient(_ => ZIO.succeed(sseResponse))
        provider = OpenCodeProvider.make(config(), http)
        chunks <- provider.executeStream("stream please").runCollect
      yield assertTrue(
        chunks.length == 2,
        chunks.head.delta == "Hello",
        chunks(1).delta == " world",
        chunks(1).finishReason.contains("stop"),
      )
    },
    test("executeStructured parses JSON body") {
      val structured = standardResponse("""{"name":"Alice","age":30}""")

      val schema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(
          "name" -> Json.Obj("type" -> Json.Str("string")),
          "age" -> Json.Obj("type" -> Json.Str("integer")),
        ),
      )

      for
        http <- mockHttpClient(_ => ZIO.succeed(structured))
        provider = OpenCodeProvider.make(config(), http)
        person <- provider.executeStructured[Person]("Give person", schema)
      yield assertTrue(person == Person("Alice", 30))
    },
    test("execute fails when apiKey missing") {
      for
        http <- mockHttpClient(_ => ZIO.succeed(standardResponse()))
        provider = OpenCodeProvider.make(config(apiKey = None), http)
        result <- provider.execute("test").exit
      yield assertTrue(result.isFailure)
    },
    test("execute fails when baseUrl missing") {
      for
        http <- mockHttpClient(_ => ZIO.succeed(standardResponse()))
        provider = OpenCodeProvider.make(config(baseUrl = None), http)
        result <- provider.execute("test").exit
      yield assertTrue(result.isFailure)
    },
    test("request construction sets OpenAI-compatible endpoint and auth header") {
      for
        http <- mockHttpClient(_ => ZIO.succeed(standardResponse()))
        provider = OpenCodeProvider.make(config(), http)
        _ <- provider.execute("test prompt")
        captured <- http.captured
        last <- ZIO.fromOption(captured.headOption)
      yield assertTrue(
        last.url == "http://localhost:4096/chat/completions",
        last.headers.get("Authorization").contains("Bearer test-key"),
        last.body.contains("\"model\":\"opencode-default\""),
        last.body.contains("\"messages\""),
      )
    },
    test("isAvailable checks health endpoint and auth") {
      for
        http <- mockHttpClient(
                  _ => ZIO.succeed(standardResponse()),
                  getHandler = (url, headers) =>
                    if url.endsWith("/models") && headers.get("Authorization").contains("Bearer test-key") then ZIO.succeed("{}")
                    else ZIO.fail(LlmError.ProviderError("bad", None)),
                )
        provider = OpenCodeProvider.make(config(), http)
        available <- provider.isAvailable
      yield assertTrue(available)
    },
    test("isAvailable is false without api key") {
      for
        http <- mockHttpClient(_ => ZIO.succeed(standardResponse()))
        provider = OpenCodeProvider.make(config(apiKey = None), http)
        available <- provider.isAvailable
      yield assertTrue(!available)
    },
    test("executeStructured fails with ParseError on invalid JSON output") {
      val bad = standardResponse("not json")
      val schema = Json.Obj("type" -> Json.Str("object"))

      for
        http <- mockHttpClient(_ => ZIO.succeed(bad))
        provider = OpenCodeProvider.make(config(), http)
        result <- provider.executeStructured[Person]("x", schema).exit
      yield assertTrue(result.isFailure)
    },
    test("stream fails on invalid SSE payload") {
      for
        http <- mockHttpClient(_ => ZIO.succeed("data: {not-json}\n\n"))
        provider = OpenCodeProvider.make(config(), http)
        result <- provider.executeStream("x").runCollect.exit
      yield assertTrue(result.isFailure)
    },
    test("propagates auth, rate limit, timeout and provider errors") {
      val errors = List[LlmError](
        LlmError.AuthenticationError("auth"),
        LlmError.RateLimitError(Some(1.second)),
        LlmError.TimeoutError(1.second),
        LlmError.ProviderError("provider", None),
      )

      for
        outcomes <- ZIO.foreach(errors) { err =>
                      for
                        http <- mockHttpClient(_ => ZIO.fail(err))
                        provider = OpenCodeProvider.make(config(), http)
                        outcome <- provider.execute("x").either
                      yield outcome
                    }
      yield assertTrue(outcomes.forall(_.isLeft))
    },
  )
