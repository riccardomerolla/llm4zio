package agents

import java.nio.file.Path

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

import core.{ AIService, FileService, Logger, ResponseParser }
import models.*
import prompts.PromptTemplates

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
                s"""$prompt
                   |
                   |IMPORTANT — YOUR PREVIOUS RESPONSE FAILED TO PARSE:
                   |$err
                   |
                   |Please fix the JSON structure issues described above and try again.
                   |Remember:
                   |- "divisions" must be a JSON object with keys: identification, environment, data, procedure (each a string or null)
                   |- "copybooks" must be a flat array of strings
                   |- Every variable must have "name" (string), "level" (integer), "dataType" (string)
                   |- Every statement must have "lineNumber" (integer), "statementType" (string), "content" (string with actual COBOL text)
                   |""".stripMargin
              case None      => prompt

            val call =
              for
                response <- aiService
                              .execute(enrichedPrompt)
                              .mapError(e => AnalysisError.AIFailed(cobolFile.name, e.message))
                parsed   <- parseAnalysis(response, cobolFile)
              yield parsed

            call.catchSome {
              case err @ AnalysisError.ParseFailed(_, _) if attempt < maxAttempts =>
                Logger.warn(
                  s"Parse failed for ${cobolFile.name} (attempt $attempt/$maxAttempts): ${err.message}. Retrying..."
                ) *> callAndParse(prompt, cobolFile, attempt + 1, maxAttempts, Some(err.message))
            }
          }

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
              case Json.Obj(fields) =>
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
              case _                => value

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
                case (k, other)       => k -> Json.Str(other.toJson)
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
