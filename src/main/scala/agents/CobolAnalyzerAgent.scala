package agents

import java.nio.file.Path

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

import core.{ FileService, GeminiService, Logger, ResponseParser }
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

  val live: ZLayer[GeminiService & ResponseParser & FileService & MigrationConfig, Nothing, CobolAnalyzerAgent] =
    ZLayer.fromFunction {
      (
        geminiService: GeminiService,
        responseParser: ResponseParser,
        fileService: FileService,
        config: MigrationConfig,
      ) =>
        new CobolAnalyzerAgent {
          private val reportDir = Path.of("reports/analysis")

          override def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis] =
            for
              _        <- Logger.info(s"Analyzing ${cobolFile.name}")
              content  <- fileService
                            .readFile(cobolFile.path)
                            .mapError(fe => AnalysisError.FileReadFailed(cobolFile.path, fe.message))
              prompt    = PromptTemplates.CobolAnalyzer.analyzeStructure(cobolFile, content)
              response <- geminiService
                            .executeLegacy(prompt)
                            .mapError(e => AnalysisError.GeminiFailed(cobolFile.name, e.message))
              parsed   <- parseAnalysis(response, cobolFile)
              analysis  = parsed.copy(file = cobolFile)
              _        <- writeReport(analysis).tapError(err => Logger.warn(err.message))
              _        <- Logger.info(
                            s"Analysis complete for ${cobolFile.name}: ${analysis.complexity.linesOfCode} LOC"
                          )
            yield analysis

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
              _      <- writeFileAtomic(path, content)
            yield ()

          private def writeSummary(analyses: List[CobolAnalysis]): ZIO[Any, AnalysisError, Unit] =
            for
              _      <- fileService
                          .ensureDirectory(reportDir)
                          .mapError(fe => AnalysisError.ReportWriteFailed(reportDir, fe.message))
              path    = reportDir.resolve("analysis-summary.json")
              content = analyses.toJsonPretty
              _      <- writeFileAtomic(path, content)
            yield ()

          private def writeFileAtomic(path: Path, content: String): ZIO[Any, AnalysisError, Unit] =
            for
              suffix  <- ZIO
                           .attemptBlocking(java.util.UUID.randomUUID().toString)
                           .mapError(e => AnalysisError.ReportWriteFailed(path, e.getMessage))
              tempPath = path.resolveSibling(s"${path.getFileName}.tmp.$suffix")
              _       <- fileService
                           .writeFile(tempPath, content)
                           .mapError(fe => AnalysisError.ReportWriteFailed(tempPath, fe.message))
              _       <- ZIO
                           .attemptBlocking {
                             import java.nio.file.StandardCopyOption
                             try
                               java.nio.file.Files.move(
                                 tempPath,
                                 path,
                                 StandardCopyOption.REPLACE_EXISTING,
                                 StandardCopyOption.ATOMIC_MOVE,
                               )
                             catch
                               case _: java.nio.file.AtomicMoveNotSupportedException =>
                                 java.nio.file.Files.move(
                                   tempPath,
                                   path,
                                   StandardCopyOption.REPLACE_EXISTING,
                                 )
                           }
                           .mapError(e => AnalysisError.ReportWriteFailed(path, e.getMessage))
            yield ()

          private def safeName(name: String): String =
            name.replaceAll("[^A-Za-z0-9._-]", "_")

          private def parseAnalysis(
            response: GeminiResponse,
            cobolFile: CobolFile,
          ): ZIO[Any, AnalysisError, CobolAnalysis] =
            responseParser
              .parse[CobolAnalysis](response)
              .catchSome { case ParseError.SchemaMismatch(_, _) => parseWithFileOverride(response, cobolFile) }
              .mapError(e => AnalysisError.ParseFailed(cobolFile.name, e.message))

          private def parseWithFileOverride(
            response: GeminiResponse,
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
                            .fromEither(overrideFileAst(ast, cobolFile))
                            .mapError(err => ParseError.InvalidJson(jsonText, err))
              analysis <- ZIO
                            .fromEither(patched.toJson.fromJson[CobolAnalysis])
                            .mapError(err => ParseError.SchemaMismatch("CobolAnalysis", err))
            yield analysis

          private def overrideFileAst(value: Json, cobolFile: CobolFile): Either[String, Json] =
            for
              fileAst <- cobolFile.toJson.fromJson[Json]
            yield value match
              case Json.Obj(fields) =>
                val updated = fields.map {
                  case ("file", _) => "file" -> fileAst
                  case other       => other
                }
                Json.Obj(updated)
              case _                => value
        }
    }
