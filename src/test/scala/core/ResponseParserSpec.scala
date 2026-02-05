package core

import zio.*
import zio.json.*
import zio.test.*

import models.{ GeminiResponse, ParseError }

final case class TestPayload(name: String, count: Int) derives JsonCodec

object ResponseParserSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("ResponseParserSpec")(
    test("extracts JSON from json code block") {
      val response = GeminiResponse(
        "Here is the result:\n```json\n{\"name\":\"alpha\",\"count\":1}\n```\nDone.",
        0,
      )
      for json <- ResponseParser.extractJson(response).provide(ResponseParser.live)
      yield assertTrue(json == "{\"name\":\"alpha\",\"count\":1}")
    },
    test("prefers JSON block when multiple code blocks exist") {
      val response = GeminiResponse(
        "```scala\nval x = 1\n```\n```json\n{\"name\":\"beta\",\"count\":2}\n```\n",
        0,
      )
      for json <- ResponseParser.extractJson(response).provide(ResponseParser.live)
      yield assertTrue(json == "{\"name\":\"beta\",\"count\":2}")
    },
    test("extracts inline JSON when no code blocks exist") {
      val response = GeminiResponse(
        "Result => {\"name\":\"gamma\",\"count\":3} <-- end",
        0,
      )
      for json <- ResponseParser.extractJson(response).provide(ResponseParser.live)
      yield assertTrue(json == "{\"name\":\"gamma\",\"count\":3}")
    },
    test("parses valid JSON into model") {
      val response = GeminiResponse(
        "```json\n{\"name\":\"delta\",\"count\":4}\n```",
        0,
      )
      for result <- ResponseParser.parse[TestPayload](response).provide(ResponseParser.live)
      yield assertTrue(result == TestPayload("delta", 4))
    },
    test("returns InvalidJson for malformed JSON") {
      val response = GeminiResponse("```json\n{not valid json}\n```", 0)
      for result <- ResponseParser.parse[TestPayload](response).provide(ResponseParser.live).either
      yield assertTrue(
        result.left.exists {
          case ParseError.InvalidJson(_, _) => true
          case _                            => false
        }
      )
    },
    test("returns SchemaMismatch for schema mismatch") {
      val response = GeminiResponse("```json\n{\"name\":\"epsilon\"}\n```", 0)
      for result <- ResponseParser.parse[TestPayload](response).provide(ResponseParser.live).either
      yield assertTrue(
        result.left.exists {
          case ParseError.SchemaMismatch(_, _) => true
          case _                               => false
        }
      )
    },
  )
