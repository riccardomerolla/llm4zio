package core

import zio.*
import zio.json.*
import zio.json.ast.Json

import models.{ GeminiResponse, ParseError }

/** ResponseParser - Extract and parse Gemini JSON responses
  *
  * Features:
  *   - Extract JSON from markdown code blocks
  *   - Handle multiple code blocks, preferring JSON-tagged blocks
  *   - Parse JSON into domain models with schema validation
  *   - Typed error handling with ParseError ADT
  *   - Logging for debugging malformed responses
  */
trait ResponseParser:
  def parse[A: JsonDecoder](response: GeminiResponse): ZIO[Any, ParseError, A]
  def extractJson(response: GeminiResponse): ZIO[Any, ParseError, String]

object ResponseParser:
  def parse[A: JsonDecoder](response: GeminiResponse): ZIO[ResponseParser, ParseError, A] =
    ZIO.serviceWithZIO[ResponseParser](_.parse(response))

  def extractJson(response: GeminiResponse): ZIO[ResponseParser, ParseError, String] =
    ZIO.serviceWithZIO[ResponseParser](_.extractJson(response))

  val live: ULayer[ResponseParser] = ZLayer.succeed(new ResponseParserLive)

final class ResponseParserLive extends ResponseParser:
  final private case class CodeBlock(language: Option[String], content: String)

  private val codeBlockPattern = "```\\s*([a-zA-Z0-9_-]+)?\\s*([\\s\\S]*?)```".r
  private val maxLogLength     = 500

  override def extractJson(response: GeminiResponse): ZIO[Any, ParseError, String] =
    val output     = response.output
    val candidates = extractCandidates(output)
    candidates.headOption match
      case Some(value) => ZIO.succeed(value)
      case None        =>
        ZIO
          .fail(ParseError.NoJsonFound(output))
          .tapError(err => logFailure(err))

  override def parse[A: JsonDecoder](response: GeminiResponse): ZIO[Any, ParseError, A] =
    (for
      json <- extractJson(response)
      _    <- ZIO.fromEither(json.fromJson[Json]).mapError(err => ParseError.InvalidJson(json, err))
      out  <- ZIO.fromEither(json.fromJson[A]).mapError { err =>
                ParseError.SchemaMismatch(decoderName[A], err)
              }
    yield out).tapError(err => logFailure(err))

  private def extractCandidates(output: String): List[String] =
    val blocks = extractBlocks(output)
    if blocks.nonEmpty then
      val jsonBlocks = blocks.filter(block => block.language.exists(isJsonLanguage))
      val preferred  = if jsonBlocks.nonEmpty then jsonBlocks else blocks
      preferred
        .map(_.content.trim)
        .filter(_.nonEmpty)
    else
      extractInlineJson(output) match
        case Some(inline) => List(inline)
        case None         =>
          val trimmed = output.trim
          if trimmed.nonEmpty then List(trimmed) else List.empty

  private def extractBlocks(output: String): List[CodeBlock] =
    codeBlockPattern
      .findAllMatchIn(output)
      .toList
      .map { m =>
        val lang    = Option(m.group(1)).map(_.trim).filter(_.nonEmpty)
        val content = Option(m.group(2)).getOrElse("")
        CodeBlock(lang, content)
      }

  private def extractInlineJson(output: String): Option[String] =
    val trimmed = output.trim
    val brace   = candidateBetween(trimmed, '{', '}')
    val bracket = candidateBetween(trimmed, '[', ']')
    List(brace, bracket)
      .flatten
      .distinct
      .find(isValidJson)

  private def candidateBetween(input: String, start: Char, end: Char): Option[String] =
    val startIdx = input.indexOf(start)
    val endIdx   = input.lastIndexOf(end)
    if startIdx >= 0 && endIdx > startIdx then
      Some(input.substring(startIdx, endIdx + 1).trim)
    else None

  private def isValidJson(value: String): Boolean =
    value.fromJson[Json].isRight

  private def isJsonLanguage(lang: String): Boolean =
    lang.toLowerCase.contains("json")

  private def decoderName[A](using decoder: JsonDecoder[A]): String =
    val name = decoder.getClass.getSimpleName
    if name.nonEmpty then name else "JsonDecoder"

  private def logFailure(error: ParseError): UIO[Unit] =
    val message = error match
      case ParseError.NoJsonFound(response)            =>
        s"No JSON found in response. Output: ${truncate(response)}"
      case ParseError.InvalidJson(json, err)           =>
        s"Invalid JSON. Error: $err. JSON: ${truncate(json)}"
      case ParseError.SchemaMismatch(expected, actual) =>
        s"Schema mismatch. Expected: $expected. Error: $actual"
    ZIO.logWarning(message)

  private def truncate(value: String): String =
    if value.length <= maxLogLength then value else value.take(maxLogLength) + "..."
