package agents

import java.nio.file.Path

import zio.*
import zio.json.*
import zio.stream.*

import core.{ FileService, Logger }
import llm4zio.core.{ LlmError, LlmService }
import llm4zio.tools.JsonSchema
import models.*
import prompts.{ PromptHelpers, PromptTemplates }

/** CobolAnalyzerAgent - Deep structural analysis of COBOL programs using AI
  *
  * Responsibilities:
  *   - Parse COBOL divisions (IDENTIFICATION, ENVIRONMENT, DATA, PROCEDURE)
  *   - Extract variables, data structures, and types
  *   - Identify control flow (IF, PERFORM, GOTO statements)
  *   - Detect copybook dependencies
  *   - Generate structured analysis JSON
  *
  * Interactions:
  *   - Input from: CobolDiscoveryAgent
  *   - Output consumed by: JavaTransformerAgent, DependencyMapperAgent
  */
trait CobolAnalyzerAgent:
  def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis]
  def analyzeAll(files: List[CobolFile]): ZStream[Any, AnalysisError, CobolAnalysis]
  def analyzeAllWithProgress(
    files: List[CobolFile],
    stepName: String,
  ): ZStream[Any, AnalysisError, StepProgressEvent]

object CobolAnalyzerAgent:
  def analyze(cobolFile: CobolFile): ZIO[CobolAnalyzerAgent, AnalysisError, CobolAnalysis] =
    ZIO.serviceWithZIO[CobolAnalyzerAgent](_.analyze(cobolFile))

  def analyzeAll(files: List[CobolFile]): ZStream[CobolAnalyzerAgent, AnalysisError, CobolAnalysis] =
    ZStream.serviceWithStream[CobolAnalyzerAgent](_.analyzeAll(files))

  def analyzeAllWithProgress(
    files: List[CobolFile],
    stepName: String,
  ): ZStream[CobolAnalyzerAgent, AnalysisError, StepProgressEvent] =
    ZStream.serviceWithStream[CobolAnalyzerAgent](_.analyzeAllWithProgress(files, stepName))

  val live: ZLayer[LlmService & FileService & MigrationConfig, Nothing, CobolAnalyzerAgent] =
    ZLayer.fromFunction {
      (
        llmService: LlmService,
        fileService: FileService,
        config: MigrationConfig,
      ) =>
        new CobolAnalyzerAgent {
          private val reportDir = Path.of("reports/analysis")

          override def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis] =
            for
              _       <- Logger.info(s"Analyzing ${cobolFile.name}")
              content <- fileService
                           .readFile(cobolFile.path)
                           .mapError(fe => AnalysisError.FileReadFailed(cobolFile.path, fe.message))
              prompt   = PromptTemplates.CobolAnalyzer.analyzeStructure(cobolFile, content)
              parsed  <- callAndParse(prompt, cobolFile, attempt = 1, maxAttempts = config.maxCompileRetries + 1)
              analysis = parsed.copy(file = cobolFile)
              _       <- writeReport(analysis).tapError(err => Logger.warn(err.message))
              _       <- Logger.info(
                           s"Analysis complete for ${cobolFile.name}: ${analysis.complexity.linesOfCode} LOC"
                         )
            yield analysis

          private def callAndParse(
            prompt: String,
            cobolFile: CobolFile,
            attempt: Int,
            maxAttempts: Int,
            previousError: Option[String] = None,
          ): ZIO[Any, AnalysisError, CobolAnalysis] = {
            val enrichedPrompt = previousError match
              case Some(err) =>
                val diagnostic = buildErrorDiagnostic(err)
                s"""$prompt
                   |
                   |${"=" * 80}
                   |🚨 CRITICAL ERROR — YOUR PREVIOUS RESPONSE FAILED TO PARSE (Attempt $attempt/$maxAttempts)
                   |${"=" * 80}
                   |
                   |ERROR MESSAGE:
                   |$err
                   |
                   |$diagnostic
                   |
                   |REQUIRED SCHEMA — Your response MUST match this EXACT structure:
                   |${PromptHelpers.schemaReference("CobolAnalysis")}
                   |
                   |CORRECT EXAMPLE (follow this pattern exactly):
                   |{
                   |  "file": { "path": "${cobolFile.path}", "name": "${cobolFile.name}", "size": ${cobolFile.size}, "lineCount": ${cobolFile.lineCount}, "lastModified": "${cobolFile.lastModified}", "encoding": "${cobolFile.encoding}", "fileType": "${cobolFile.fileType}" },
                   |  "divisions": {
                   |    "identification": "PROGRAM-ID. EXAMPLE.",
                   |    "environment": null,
                   |    "data": "WORKING-STORAGE SECTION. 01 VAR PIC X.",
                   |    "procedure": "DISPLAY 'HELLO'. STOP RUN."
                   |  },
                   |  "variables": [
                   |    { "name": "VAR", "level": 1, "dataType": "alphanumeric", "picture": "X", "usage": null }
                   |  ],
                   |  "procedures": [
                   |    { "name": "MAIN", "paragraphs": ["MAIN"], "statements": [ { "lineNumber": 1, "statementType": "DISPLAY", "content": "DISPLAY 'HELLO'" } ] }
                   |  ],
                   |  "copybooks": [],
                   |  "complexity": { "cyclomaticComplexity": 1, "linesOfCode": 10, "numberOfProcedures": 1 }
                   |}
                   |
                   |${"=" * 80}
                   |RESPOND WITH THE CORRECTED JSON OBJECT NOW (no markdown, no explanation)
                   |${"=" * 80}
                   |""".stripMargin
              case None      => prompt

            val schema = buildJsonSchema()
            val call   =
              for
                analysis  <- llmService
                               .executeStructured[CobolAnalysis](enrichedPrompt, schema)
                               .mapError(convertError(cobolFile.name))
                               .map(_.copy(file = cobolFile)) // Ensure file metadata is correct
                validated <- validateAnalysis(analysis, cobolFile, attempt, maxAttempts)
              yield validated

            call.catchSome {
              case err @ AnalysisError.ParseFailed(_, _) if attempt < maxAttempts      =>
                Logger.warn(
                  s"Parse failed for ${cobolFile.name} (attempt $attempt/$maxAttempts): ${err.message}. Retrying..."
                ) *> callAndParse(prompt, cobolFile, attempt + 1, maxAttempts, Some(err.message))
              case err @ AnalysisError.ValidationFailed(_, _) if attempt < maxAttempts =>
                Logger.warn(
                  s"Validation failed for ${cobolFile.name} (attempt $attempt/$maxAttempts): ${err.message}. Retrying..."
                ) *> callAndParse(prompt, cobolFile, attempt + 1, maxAttempts, Some(err.message))
            }
          }

          private def buildErrorDiagnostic(errorMessage: String): String =
            if errorMessage.contains("expected '{' got '['") then
              """|DIAGNOSIS: You returned a JSON ARRAY [...] but we need a JSON OBJECT {...}
                 |
                 |WRONG (what you did):
                 |[
                 |  { "file": {...}, ... }
                 |]
                 |
                 |CORRECT (what we need):
                 |{
                 |  "file": {...},
                 |  "divisions": {...},
                 |  "variables": [...],
                 |  "procedures": [...],
                 |  "copybooks": [...],
                 |  "complexity": {...}
                 |}
                 |
                 |⚠️  YOUR RESPONSE MUST START WITH '{' and END WITH '}' — NOT '[' and ']'
                 |""".stripMargin
            else if errorMessage.contains("SchemaMismatch") || errorMessage.contains("Schema mismatch") then
              """|DIAGNOSIS: The JSON structure does not match the expected CobolAnalysis schema.
                 |
                 |Common issues:
                 |1. Missing required fields (file, divisions, variables, procedures, copybooks, complexity)
                 |2. Wrong data types (e.g., string instead of number, or vice versa)
                 |3. Nested objects where arrays are expected, or vice versa
                 |4. Extra fields or typos in field names
                 |
                 |⚠️  Verify EVERY field matches the schema exactly
                 |""".stripMargin
            else if errorMessage.contains("InvalidJson") then
              """|DIAGNOSIS: The response is not valid JSON.
                 |
                 |Common issues:
                 |1. Unmatched brackets/braces: every '{' needs a '}', every '[' needs a ']'
                 |2. Missing or extra commas
                 |3. Unescaped quotes in strings (use \" inside strings)
                 |4. Trailing commas before closing braces/brackets
                 |5. Markdown formatting or text before/after the JSON
                 |
                 |⚠️  Use a JSON validator to check syntax before responding
                 |""".stripMargin
            else
              """|DIAGNOSIS: Unknown parsing error. Please ensure:
                 |1. Response is ONLY valid JSON (no markdown, no explanations)
                 |2. Response starts with '{' and ends with '}'
                 |3. All field names match the schema exactly
                 |4. All data types are correct
                 |""".stripMargin

          private def buildJsonSchema(): JsonSchema =
            import zio.json.ast.Json
            Json.Obj(
              "type"       -> Json.Str("object"),
              "properties" -> Json.Obj(
                "file"       -> Json.Obj("type" -> Json.Str("object")),
                "divisions"  -> Json.Obj("type" -> Json.Str("object")),
                "variables"  -> Json.Obj("type" -> Json.Str("array")),
                "procedures" -> Json.Obj("type" -> Json.Str("array")),
                "copybooks"  -> Json.Obj("type" -> Json.Str("array")),
                "complexity" -> Json.Obj("type" -> Json.Str("object")),
              ),
              "required"   -> Json.Arr(
                Json.Str("file"),
                Json.Str("divisions"),
                Json.Str("variables"),
                Json.Str("procedures"),
                Json.Str("copybooks"),
                Json.Str("complexity"),
              ),
            )

          private def convertError(fileName: String)(error: LlmError): AnalysisError =
            error match
              case LlmError.ProviderError(message, cause) =>
                AnalysisError.AIFailed(
                  fileName,
                  s"Provider error: $message${cause.map(c => s" (${c.getMessage})").getOrElse("")}",
                )
              case LlmError.RateLimitError(retryAfter)    =>
                AnalysisError.AIFailed(
                  fileName,
                  s"Rate limited${retryAfter.map(d => s", retry after ${d.toSeconds}s").getOrElse("")}",
                )
              case LlmError.AuthenticationError(message)  =>
                AnalysisError.AIFailed(fileName, s"Authentication failed: $message")
              case LlmError.InvalidRequestError(message)  =>
                AnalysisError.AIFailed(fileName, s"Invalid request: $message")
              case LlmError.TimeoutError(duration)        =>
                AnalysisError.AIFailed(fileName, s"Request timed out after ${duration.toSeconds}s")
              case LlmError.ParseError(message, raw)      =>
                AnalysisError.ParseFailed(fileName, s"$message\nRaw: ${raw.take(200)}")
              case LlmError.ToolError(toolName, message)  =>
                AnalysisError.AIFailed(fileName, s"Tool error ($toolName): $message")
              case LlmError.ConfigError(message)          =>
                AnalysisError.AIFailed(fileName, s"Configuration error: $message")

          private def validateAnalysis(
            analysis: CobolAnalysis,
            cobolFile: CobolFile,
            attempt: Int,
            maxAttempts: Int,
          ): ZIO[Any, AnalysisError, CobolAnalysis] =
            val divisionIssues = List(
              analysis.divisions.identification,
              analysis.divisions.environment,
              analysis.divisions.data,
              analysis.divisions.procedure,
            ).flatten.filter(_.contains("[STRUCTURED_JSON_DETECTED]"))

            val hasStructuredDivisions = divisionIssues.nonEmpty
            val hasNoData              = analysis.variables.isEmpty && analysis.procedures.isEmpty

            if hasStructuredDivisions then
              val msg =
                "AI returned structured JSON objects for divisions instead of plain COBOL text. " +
                  s"This indicates the AI misunderstood the schema. Found in: ${divisionIssues.size} division(s)."
              if attempt >= maxAttempts then
                Logger.warn(s"$msg Final attempt - accepting with warnings.") *> ZIO.succeed(analysis)
              else
                ZIO.fail(AnalysisError.ValidationFailed(cobolFile.name, msg))
            else if hasNoData && cobolFile.lineCount > 20 then
              val msg =
                s"Analysis has no extracted variables or procedures, but source has ${cobolFile.lineCount} lines. " +
                  "This suggests the AI failed to extract data properly."
              if attempt >= maxAttempts then
                Logger.warn(s"$msg Final attempt - accepting incomplete analysis.") *> ZIO.succeed(analysis)
              else
                ZIO.fail(AnalysisError.ValidationFailed(cobolFile.name, msg))
            else
              ZIO.succeed(analysis)

          override def analyzeAll(files: List[CobolFile]): ZStream[Any, AnalysisError, CobolAnalysis] =
            val stream = ZStream
              .fromIterable(files)
              .zipWithIndex
              .mapZIOParUnordered(config.parallelism) {
                case (file, idx) =>
                  for
                    startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
                    _         <- Logger.info(s"[${idx + 1}/${files.size}] Starting analysis of ${file.name}")
                    analysis  <- analyze(file)
                                   .tapError(err => Logger.warn(err.message))
                    endTime   <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
                    duration   = endTime - startTime
                    _         <- Logger.info(
                                   s"[${idx + 1}/${files.size}] Completed ${file.name} in ${duration}ms " +
                                     s"(${analysis.complexity.linesOfCode} LOC, ${analysis.procedures.size} procedures)"
                                 )
                  yield analysis
              }

            ZStream.unwrapScoped {
              for
                analysesRef <- Ref.make(List.empty[CobolAnalysis])
                progressRef <- Ref.make(0)
                startTime   <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
                result       = stream
                                 .tap { analysis =>
                                   for
                                     count       <- progressRef.updateAndGet(_ + 1)
                                     currentTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
                                     elapsed      = currentTime - startTime
                                     rate         = if elapsed > 0 then (count.toDouble / elapsed) * 1000 else 0.0
                                     remaining    = files.size - count
                                     eta          = if rate > 0 then (remaining / rate).toLong else 0
                                     _           <- Logger.info(
                                                      f"[Progress] $count/${files.size} files analyzed " +
                                                        f"(${rate}%.2f files/sec, ETA ${eta}ms)"
                                                    )
                                     _           <- analysesRef.update(analysis :: _)
                                   yield ()
                                 }
                _           <- ZIO.addFinalizer(
                                 analysesRef.get.flatMap(analyses => writeSummary(analyses.reverse).ignore)
                               )
              yield result
            }

          override def analyzeAllWithProgress(
            files: List[CobolFile],
            stepName: String,
          ): ZStream[Any, AnalysisError, StepProgressEvent] =
            val totalItems = files.size
            val metricsRef = Ref.Synchronized.make(
              StepMetrics(
                tokensUsed = 0,
                latencyMs = 0,
                cost = 0.0,
                itemsProcessed = 0,
                itemsFailed = 0,
              )
            )

            ZStream.unwrap {
              for
                metrics   <- metricsRef
                startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
              yield ZStream
                .fromIterable(files)
                .zipWithIndex
                .flatMap {
                  case (file, idx) =>
                    val itemIndex        = idx.toInt
                    val itemStartedEvent =
                      StepProgressEvent.ItemStarted(file.name, itemIndex, totalItems, stepName)

                    // Wrap the analysis with progress tracking
                    val analysisEffect =
                      for
                        itemStart <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
                        result    <- analyze(file)
                                       .foldZIO(
                                         error =>
                                           for
                                             _ <- metrics.update(m => m.copy(itemsFailed = m.itemsFailed + 1))
                                           yield Left(error),
                                         analysis =>
                                           for
                                             itemEnd <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
                                             duration = itemEnd - itemStart
                                             _       <- metrics.update(m =>
                                                          m.copy(
                                                            itemsProcessed = m.itemsProcessed + 1,
                                                            latencyMs = m.latencyMs + duration,
                                                          )
                                                        )
                                           yield Right(analysis),
                                       )
                      yield result

                    ZStream.fromZIO(analysisEffect).flatMap {
                      case Left(error) =>
                        val failedEvent = StepProgressEvent.ItemFailed(
                          file.name,
                          error.message,
                          None,
                          isFatal = false,
                        )
                        ZStream(itemStartedEvent, failedEvent)

                      case Right(analysis) =>
                        val resultJson = analysis.toJson.fromJson[zio.json.ast.Json] match
                          case Left(_)     => zio.json.ast.Json.Null
                          case Right(json) => json

                        ZStream.fromZIO(metrics.get).flatMap { currentMetrics =>
                          val itemMetrics    = StepMetrics(
                            tokensUsed = 0, // Would need to extract from LLM response
                            latencyMs = currentMetrics.latencyMs,
                            cost = 0.0,
                            itemsProcessed = 1,
                            itemsFailed = 0,
                          )
                          val completedEvent =
                            StepProgressEvent.ItemCompleted(file.name, resultJson, itemMetrics)

                          ZStream(itemStartedEvent, completedEvent)
                        }
                    }
                }
                ++ ZStream.fromZIO(
                  for
                    endTime       <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
                    duration       = endTime - startTime
                    finalMetrics  <- metrics.get
                    completedEvent = StepProgressEvent.StepCompleted(
                                       stepName,
                                       finalMetrics.copy(latencyMs = duration),
                                       Some(
                                         s"Analyzed ${finalMetrics.itemsProcessed} files, " +
                                           s"${finalMetrics.itemsFailed} failures"
                                       ),
                                     )
                  yield completedEvent
                )
            }

          private def writeReport(analysis: CobolAnalysis): ZIO[Any, AnalysisError, Unit] =
            for
              _      <- fileService
                          .ensureDirectory(reportDir)
                          .mapError(fe => AnalysisError.ReportWriteFailed(reportDir, fe.message))
              path    = reportDir.resolve(s"${safeName(analysis.file.name)}.json")
              content = analysis.toJsonPretty
              _      <- fileService
                          .writeFileAtomic(path, content)
                          .mapError(fe => AnalysisError.ReportWriteFailed(path, fe.message))
            yield ()

          private def writeSummary(analyses: List[CobolAnalysis]): ZIO[Any, AnalysisError, Unit] =
            for
              _      <- fileService
                          .ensureDirectory(reportDir)
                          .mapError(fe => AnalysisError.ReportWriteFailed(reportDir, fe.message))
              path    = reportDir.resolve("analysis-summary.json")
              content = analyses.toJsonPretty
              _      <- fileService
                          .writeFileAtomic(path, content)
                          .mapError(fe => AnalysisError.ReportWriteFailed(path, fe.message))
            yield ()

          private def safeName(name: String): String =
            name.replaceAll("[^A-Za-z0-9._-]", "_")
        }
    }
