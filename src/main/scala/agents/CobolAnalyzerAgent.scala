package agents

import java.nio.file.Path

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

import core.{ AIService, FileService, Logger, ResponseParser }
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

object CobolAnalyzerAgent:
  def analyze(cobolFile: CobolFile): ZIO[CobolAnalyzerAgent, AnalysisError, CobolAnalysis] =
    ZIO.serviceWithZIO[CobolAnalyzerAgent](_.analyze(cobolFile))

  def analyzeAll(files: List[CobolFile]): ZStream[CobolAnalyzerAgent, AnalysisError, CobolAnalysis] =
    ZStream.serviceWithStream[CobolAnalyzerAgent](_.analyzeAll(files))

  val live: ZLayer[AIService & ResponseParser & FileService & MigrationConfig, Nothing, CobolAnalyzerAgent] =
    ZLayer.fromFunction {
      (
        aiService: AIService,
        responseParser: ResponseParser,
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

            val call =
              for
                response  <- aiService
                               .execute(enrichedPrompt)
                               .mapError(e => AnalysisError.AIFailed(cobolFile.name, e.message))
                parsed    <- parseAnalysis(response, cobolFile)
                validated <- validateAnalysis(parsed, cobolFile, attempt, maxAttempts)
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
              .mapZIOParUnordered(config.parallelism) { file =>
                analyze(file)
                  .tapError(err => Logger.warn(err.message))
              }

            ZStream.unwrapScoped {
              for
                analysesRef <- Ref.make(List.empty[CobolAnalysis])
                result       = stream.tap(analysis => analysesRef.update(analysis :: _))
                _           <- ZIO.addFinalizer(
                                 analysesRef.get.flatMap(analyses => writeSummary(analyses.reverse).ignore)
                               )
              yield result
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

          private def parseAnalysis(
            response: AIResponse,
            cobolFile: CobolFile,
          ): ZIO[Any, AnalysisError, CobolAnalysis] =
            responseParser
              .parse[CobolAnalysis](response)
              .catchSome { case ParseError.SchemaMismatch(_, _) => parseWithFileOverride(response, cobolFile) }
              .mapError(e => AnalysisError.ParseFailed(cobolFile.name, e.message))

          private def parseWithFileOverride(
            response: AIResponse,
            cobolFile: CobolFile,
          ): ZIO[Any, ParseError, CobolAnalysis] =
            for
              jsonText <- responseParser
                            .extractJson(response)
                            .mapError(identity)
              ast      <- ZIO
                            .fromEither(jsonText.fromJson[Json])
                            .mapError(err => ParseError.InvalidJson(jsonText, err))
              _        <- ast match
                            case Json.Arr(elements) =>
                              Logger.warn(
                                s"AI returned array with ${elements.size} element(s) for ${cobolFile.name} - unwrapping to object"
                              )
                            case _                  => ZIO.unit
              patched  <- ZIO
                            .fromEither(normalizeAst(ast, cobolFile))
                            .mapError(err => ParseError.InvalidJson(jsonText, err))
              analysis <- ZIO
                            .fromEither(patched.toJson.fromJson[CobolAnalysis])
                            .mapError(err => ParseError.SchemaMismatch("CobolAnalysis", err))
            yield analysis

          private def normalizeAst(value: Json, cobolFile: CobolFile): Either[String, Json] =
            for
              fileAst <- cobolFile.toJson.fromJson[Json]
            yield value match
              // 🚨 FIX: AI returned array instead of object - unwrap single element
              case Json.Arr(elements) if elements.size == 1 =>
                elements.head match
                  case obj @ Json.Obj(_) => normalizeAst(obj, cobolFile).getOrElse(obj)
                  case other             => other
              case Json.Arr(elements)                       =>
                // Multiple elements - this is a critical error, but try to recover by using first element
                // Note: AI returned array with ${elements.size} elements instead of single object - using first
                elements.headOption match
                  case Some(obj @ Json.Obj(_)) => normalizeAst(obj, cobolFile).getOrElse(obj)
                  case Some(other)             => other
                  case None                    => value
              case Json.Obj(fields)                         =>
                val transformed      = fields.map {
                  case ("file", _)       => "file"       -> fileAst
                  case ("divisions", v)  => "divisions"  -> normalizeDivisions(v)
                  case ("variables", v)  => "variables"  -> normalizeArray(v, normalizeVariable)
                  case ("procedures", v) => "procedures" -> normalizeArray(v, normalizeProcedure)
                  case ("complexity", v) => "complexity" -> normalizeComplexity(v)
                  case ("copybooks", v)  => "copybooks"  -> normalizeCopybooks(v)
                  case other             => other
                }
                val topLevelDefaults = Chunk(
                  "file"       -> fileAst,
                  "divisions"  -> Json.Obj(Chunk(
                    "identification" -> Json.Null,
                    "environment"    -> Json.Null,
                    "data"           -> Json.Null,
                    "procedure"      -> Json.Null,
                  )),
                  "variables"  -> Json.Arr(),
                  "procedures" -> Json.Arr(),
                  "copybooks"  -> Json.Arr(),
                  "complexity" -> normalizeComplexity(Json.Obj(Chunk.empty)),
                )
                Json.Obj(mergeDefaults(transformed, topLevelDefaults))
              case _                                        => value

          private def normalizeArray(value: Json, normalize: Json => Json): Json = value match
            case Json.Arr(elements) => Json.Arr(elements.map(normalize))
            case _                  => value

          private def normalizeDivisions(value: Json): Json = value match
            case Json.Arr(elements) =>
              val strings = elements.collect { case Json.Str(s) => s }.toList
              val keys    = List("identification", "environment", "data", "procedure")
              val pairs   = keys.zipWithIndex.map {
                case (key, i) =>
                  key -> (if i < strings.size then Json.Str(strings(i)) else Json.Null)
              }
              Json.Obj(Chunk.from(pairs))
            case Json.Obj(fields)   =>
              Json.Obj(fields.map {
                case (k, Json.Str(s)) => k -> Json.Str(s)
                case (k, Json.Null)   => k -> Json.Null
                case (k, other)       =>
                  // WARN: AI returned structured JSON for division instead of plain text
                  // Convert to string but this indicates prompt/parsing issues
                  k -> Json.Str(s"[STRUCTURED_JSON_DETECTED] ${other.toJson}")
              })
            case other              => other

          private def normalizeCopybooks(value: Json): Json = value match
            case Json.Null          => Json.Arr()
            case Json.Arr(elements) =>
              val strings = elements.map {
                case Json.Str(s)      => Json.Str(s)
                case Json.Obj(fields) =>
                  fields
                    .collectFirst {
                      case ("name", Json.Str(s))     => Json.Str(s)
                      case ("copybook", Json.Str(s)) => Json.Str(s)
                    }
                    .getOrElse(Json.Str(fields.headOption.collect { case (_, Json.Str(s)) => s }.getOrElse("UNKNOWN")))
                case other            => Json.Str(other.toString)
              }
              Json.Arr(strings)
            case other              => other

          private def normalizeVariable(value: Json): Json = value match
            case Json.Obj(fields) =>
              val defaults = Chunk(
                "name"     -> Json.Str("UNKNOWN"),
                "level"    -> Json.Num(1),
                "dataType" -> Json.Str("alphanumeric"),
              )
              Json.Obj(mergeDefaults(fields, defaults))
            case other            => other

          private def normalizeProcedure(value: Json): Json = value match
            case Json.Obj(fields) =>
              val nameVal    = fields.collectFirst { case ("name", v) => v }.getOrElse(Json.Str("UNKNOWN"))
              val defaults   = Chunk(
                "name"       -> nameVal,
                "paragraphs" -> Json.Arr(Chunk(nameVal)),
                "statements" -> Json.Arr(),
              )
              val withDefs   = mergeDefaults(fields, defaults)
              val normalized = withDefs.map {
                case ("statements", v) =>
                  "statements" -> filterEmptyStatements(normalizeArray(v, normalizeStatement))
                case other             => other
              }
              Json.Obj(normalized)
            case other            => other

          private def normalizeStatement(value: Json): Json = value match
            case Json.Obj(fields) =>
              val defaults = Chunk(
                "lineNumber"    -> Json.Num(0),
                "statementType" -> Json.Str("UNKNOWN"),
                "content"       -> Json.Str(""),
              )
              Json.Obj(mergeDefaults(fields, defaults))
            case other            => other

          private def filterEmptyStatements(value: Json): Json = value match
            case Json.Arr(elements) =>
              Json.Arr(elements.filter {
                case Json.Obj(fields) =>
                  val stmtType = fields.collectFirst { case ("statementType", Json.Str(s)) => s }.getOrElse("")
                  val content  = fields.collectFirst { case ("content", Json.Str(s)) => s }.getOrElse("")
                  !(stmtType == "UNKNOWN" && content.isEmpty)
                case _                => true
              })
            case other              => other

          private def normalizeComplexity(value: Json): Json = value match
            case Json.Obj(fields) =>
              val defaults = Chunk(
                "cyclomaticComplexity" -> Json.Num(1),
                "linesOfCode"          -> Json.Num(0),
                "numberOfProcedures"   -> Json.Num(0),
              )
              Json.Obj(mergeDefaults(fields, defaults))
            case other            => other

          private def mergeDefaults(
            fields: Chunk[(String, Json)],
            defaults: Chunk[(String, Json)],
          ): Chunk[(String, Json)] =
            val existing = fields.map(_._1).toSet
            fields ++ defaults.filterNot { case (k, _) => existing.contains(k) }
        }
    }
