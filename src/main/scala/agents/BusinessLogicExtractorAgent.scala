package agents

import java.nio.file.Path

import zio.*
import zio.json.*

import llm4zio.core.{LlmService, LlmError}
import llm4zio.tools.JsonSchema
import core.{FileService, Logger}
import models.*
import prompts.PromptTemplates

/** BusinessLogicExtractorAgent - Extract business purpose, use cases, and rules from COBOL analyses.
  */
trait BusinessLogicExtractorAgent:
  def extract(analysis: CobolAnalysis): ZIO[Any, BusinessLogicExtractionError, BusinessLogicExtraction]
  def extractAll(analyses: List[CobolAnalysis]): ZIO[Any, BusinessLogicExtractionError, List[BusinessLogicExtraction]]

object BusinessLogicExtractorAgent:
  def extract(analysis: CobolAnalysis)
    : ZIO[BusinessLogicExtractorAgent, BusinessLogicExtractionError, BusinessLogicExtraction] =
    ZIO.serviceWithZIO[BusinessLogicExtractorAgent](_.extract(analysis))

  def extractAll(analyses: List[CobolAnalysis])
    : ZIO[BusinessLogicExtractorAgent, BusinessLogicExtractionError, List[BusinessLogicExtraction]] =
    ZIO.serviceWithZIO[BusinessLogicExtractorAgent](_.extractAll(analyses))

  val live: ZLayer[LlmService & FileService & MigrationConfig, Nothing, BusinessLogicExtractorAgent] =
    ZLayer.fromFunction {
      (
        llmService: LlmService,
        fileService: FileService,
        config: MigrationConfig,
      ) =>
        new BusinessLogicExtractorAgent {
          private val reportDir = Path.of("reports/business-logic")

          override def extract(analysis: CobolAnalysis)
            : ZIO[Any, BusinessLogicExtractionError, BusinessLogicExtraction] =
            for
              _        <- Logger.info(s"Extracting business logic from ${analysis.file.name}")
              prompt    = PromptTemplates.BusinessLogicExtractor.extractBusinessLogic(analysis)
              schema    = buildJsonSchema()
              parsed   <- llmService
                            .executeStructured[BusinessLogicExtraction](prompt, schema)
                            .mapError(convertError(analysis.file.name))
              enriched  = parsed.copy(
                            fileName = if parsed.fileName.trim.nonEmpty then parsed.fileName else analysis.file.name
                          )
              _        <- writeReport(enriched).tapError(err => Logger.warn(err.message))
            yield enriched

          override def extractAll(
            analyses: List[CobolAnalysis]
          ): ZIO[Any, BusinessLogicExtractionError, List[BusinessLogicExtraction]] =
            for
              results <- ZIO.foreachPar(analyses)(extract).withParallelism(config.parallelism)
              _       <- writeSummary(results).tapError(err => Logger.warn(err.message))
            yield results

          private def writeReport(extraction: BusinessLogicExtraction): ZIO[Any, BusinessLogicExtractionError, Unit] =
            for
              _      <- fileService
                          .ensureDirectory(reportDir)
                          .mapError(fe => BusinessLogicExtractionError.ReportWriteFailed(reportDir, fe.message))
              path    = reportDir.resolve(s"${safeName(extraction.fileName)}.json")
              content = extraction.toJsonPretty
              _      <- fileService
                          .writeFileAtomic(path, content)
                          .mapError(fe => BusinessLogicExtractionError.ReportWriteFailed(path, fe.message))
            yield ()

          private def writeSummary(
            extractions: List[BusinessLogicExtraction]
          ): ZIO[Any, BusinessLogicExtractionError, Unit] =
            for
              _      <- fileService
                          .ensureDirectory(reportDir)
                          .mapError(fe => BusinessLogicExtractionError.ReportWriteFailed(reportDir, fe.message))
              path    = reportDir.resolve("business-logic-summary.json")
              content = extractions.toJsonPretty
              _      <- fileService
                          .writeFileAtomic(path, content)
                          .mapError(fe => BusinessLogicExtractionError.ReportWriteFailed(path, fe.message))
            yield ()

          private def buildJsonSchema(): JsonSchema =
            import zio.json.ast.Json
            Json.Obj(
              "type" -> Json.Str("object"),
              "properties" -> Json.Obj(
                "fileName" -> Json.Obj("type" -> Json.Str("string")),
                "purpose" -> Json.Obj("type" -> Json.Str("string")),
                "useCases" -> Json.Obj("type" -> Json.Str("array")),
                "businessRules" -> Json.Obj("type" -> Json.Str("array"))
              ),
              "required" -> Json.Arr(
                Json.Str("fileName"),
                Json.Str("purpose"),
                Json.Str("useCases"),
                Json.Str("businessRules")
              )
            )

          private def convertError(fileName: String)(error: LlmError): BusinessLogicExtractionError =
            error match
              case LlmError.ProviderError(message, cause) =>
                BusinessLogicExtractionError.AIFailed(
                  fileName,
                  s"Provider error: $message${cause.map(c => s" (${c.getMessage})").getOrElse("")}"
                )
              case LlmError.RateLimitError(retryAfter) =>
                BusinessLogicExtractionError.AIFailed(
                  fileName,
                  s"Rate limited${retryAfter.map(d => s", retry after ${d.toSeconds}s").getOrElse("")}"
                )
              case LlmError.AuthenticationError(message) =>
                BusinessLogicExtractionError.AIFailed(fileName, s"Authentication failed: $message")
              case LlmError.InvalidRequestError(message) =>
                BusinessLogicExtractionError.AIFailed(fileName, s"Invalid request: $message")
              case LlmError.TimeoutError(duration) =>
                BusinessLogicExtractionError.AIFailed(fileName, s"Request timed out after ${duration.toSeconds}s")
              case LlmError.ParseError(message, raw) =>
                BusinessLogicExtractionError.ParseFailed(fileName, s"$message\nRaw: ${raw.take(200)}")
              case LlmError.ToolError(toolName, message) =>
                BusinessLogicExtractionError.AIFailed(fileName, s"Tool error ($toolName): $message")
              case LlmError.ConfigError(message) =>
                BusinessLogicExtractionError.AIFailed(fileName, s"Configuration error: $message")

          private def safeName(fileName: String): String =
            fileName
              .replaceAll("[^A-Za-z0-9._-]", "_")
              .replaceAll("\\.+", ".")
        }
    }
