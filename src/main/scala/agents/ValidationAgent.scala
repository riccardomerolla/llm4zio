package agents

import java.nio.file.Path

import zio.*
import zio.json.*

import core.{ AIService, FileService, Logger, ResponseParser }
import models.*
import prompts.ValidationPrompts

/** ValidationAgent - Validate generated Spring Boot code for correctness
  *
  * Responsibilities:
  *   - Check compilation and static analysis heuristics
  *   - Validate variable/procedure mapping coverage
  *   - Run AI semantic validation against COBOL intent
  *   - Generate validation reports
  */
trait ValidationAgent:
  def validate(project: SpringBootProject, analysis: CobolAnalysis): ZIO[Any, ValidationError, ValidationReport]

object ValidationAgent:
  def validate(project: SpringBootProject, analysis: CobolAnalysis)
    : ZIO[ValidationAgent, ValidationError, ValidationReport] =
    ZIO.serviceWithZIO[ValidationAgent](_.validate(project, analysis))

  val live: ZLayer[AIService & ResponseParser & FileService & MigrationConfig, Nothing, ValidationAgent] =
    ZLayer.fromFunction {
      (
        aiService: AIService,
        responseParser: ResponseParser,
        fileService: FileService,
        config: MigrationConfig,
      ) =>
        new ValidationAgent {
          private val reportDir = Path.of("reports/validation")

          override def validate(project: SpringBootProject, analysis: CobolAnalysis)
            : ZIO[Any, ValidationError, ValidationReport] =
            for
              _                  <- validateProject(project)
              _                  <- Logger.info(s"Validating ${project.projectName}")
              _                  <-
                Logger.debug(
                  s"Validation inputs for ${project.projectName}: entities=${project.entities.size}, services=${project.services.size}, controllers=${project.controllers.size}, repositories=${project.repositories.size}"
                )
              compileResult      <- runCompile(project)
              _                  <-
                Logger.debug(
                  s"Compile result for ${project.projectName}: success=${compileResult.success}, exitCode=${compileResult.exitCode}"
                )
              coverageMetrics     = calculateCoverage(project, analysis)
              _                  <-
                Logger.debug(
                  f"Coverage for ${project.projectName}: variables=${coverageMetrics.variablesCovered}%.2f%%, procedures=${coverageMetrics.proceduresCovered}%.2f%%, fileSections=${coverageMetrics.fileSectionCovered}%.2f%%, unmapped=${coverageMetrics.unmappedItems.size}"
                )
              staticIssues        = runStaticChecks(project, analysis)
              _                  <- Logger.debug(
                                      s"Static heuristic checks for ${project.projectName}: ${staticIssues.size} issues"
                                    )
              mavenStaticIssues  <- runMavenStaticAnalysis(project)
              _                  <- Logger.debug(
                                      s"Maven static checks for ${project.projectName}: ${mavenStaticIssues.size} issues"
                                    )
              coverageIssues      = coverageUnmapped(coverageMetrics)
              _                  <- Logger.debug(
                                      s"Coverage checks for ${project.projectName}: ${coverageIssues.size} issues"
                                    )
              semanticValidation <- runSemanticValidation(project, analysis)
              _                  <-
                Logger.debug(
                  f"Semantic validation for ${project.projectName}: preserved=${semanticValidation.businessLogicPreserved}, confidence=${semanticValidation.confidence}%.2f, issues=${semanticValidation.issues.size}"
                )
              validatedAt        <- Clock.instant
              allIssues           =
                (compileIssue(
                  compileResult
                ) ++ staticIssues ++ mavenStaticIssues ++ coverageIssues ++ semanticValidation.issues).distinct
              overallStatus       = determineStatus(compileResult, allIssues)
              report              = ValidationReport(
                                      projectName = project.projectName,
                                      validatedAt = validatedAt,
                                      compileResult = compileResult,
                                      coverageMetrics = coverageMetrics,
                                      issues = allIssues,
                                      semanticValidation = semanticValidation,
                                      overallStatus = overallStatus,
                                    )
              _                  <- writeReports(project.projectName, report)
              _                  <- Logger.info(
                                      s"Validation complete for ${project.projectName}: status=$overallStatus, issues=${allIssues.size}"
                                    )
            yield report

          private def validateProject(project: SpringBootProject): ZIO[Any, ValidationError, Unit] =
            if project.projectName.trim.isEmpty then
              ZIO.fail(ValidationError.InvalidProject(project.projectName, "projectName cannot be empty"))
            else Logger.debug(s"Validation project accepted: ${project.projectName}")

          private def runCompile(project: SpringBootProject): ZIO[Any, ValidationError, CompileResult] =
            val projectDir = config.outputDir.resolve(project.projectName.toLowerCase)
            val pomPath    = projectDir.resolve("pom.xml")
            Logger.debug(s"Compile check for ${project.projectName}: projectDir=$projectDir, pomPath=$pomPath") *>
              ZIO
                .attemptBlocking(java.nio.file.Files.exists(pomPath))
                .mapError(e => ValidationError.CompileFailed(project.projectName, e.getMessage))
                .flatMap { hasPom =>
                  if !hasPom then
                    Logger.warn(s"Compile skipped for ${project.projectName}: missing pom.xml at $pomPath") *>
                      ZIO.succeed(
                        CompileResult(
                          success = false,
                          exitCode = -1,
                          output = s"Missing build file at $pomPath",
                        )
                      )
                  else
                    Logger.debug(s"Running Maven compile for ${project.projectName}") *>
                      ZIO
                        .attemptBlocking {
                          val process = new ProcessBuilder("mvn", "-q", "compile")
                            .directory(projectDir.toFile)
                            .redirectErrorStream(true)
                            .start()
                          val output  = new String(process.getInputStream.readAllBytes())
                          val code    = process.waitFor()
                          CompileResult(success = code == 0, exitCode = code, output = truncate(output))
                        }
                        .timeout(90.seconds)
                        .mapError(e => ValidationError.CompileFailed(project.projectName, e.getMessage))
                        .map {
                          case Some(result) => result
                          case None         => CompileResult(success = false, exitCode = 124, output = "mvn compile timed out")
                        }
                }

          private def calculateCoverage(project: SpringBootProject, analysis: CobolAnalysis): CoverageMetrics =
            val normalizedFields = project.entities.flatMap(_.fields.map(f => normalizeName(f.name))).toSet
            val normalizedVars   = analysis.variables.map(v => normalizeName(v.name))
            val variableCoverage =
              if normalizedVars.isEmpty then 100.0
              else normalizedVars.count(normalizedFields.contains).toDouble / normalizedVars.size.toDouble * 100.0

            val methodNames       = project.services.flatMap(_.methods.map(m => normalizeName(m.name))).toSet
            val normalizedProcs   = analysis.procedures.map(p => normalizeName(p.name))
            val procedureCoverage =
              if normalizedProcs.isEmpty then 100.0
              else normalizedProcs.count(methodNames.contains).toDouble / normalizedProcs.size.toDouble * 100.0

            val fileSectionCoverage =
              if analysis.copybooks.isEmpty then 100.0
              else
                val mapped = analysis.copybooks.count(cb =>
                  project.repositories.exists(r => normalizeName(r.entityName).contains(normalizeName(cb)))
                )
                mapped.toDouble / analysis.copybooks.size.toDouble * 100.0

            val unmappedVariables  = analysis.variables
              .map(_.name)
              .filterNot(name => normalizedFields.contains(normalizeName(name)))
            val unmappedProcedures = analysis.procedures
              .map(_.name)
              .filterNot(name => methodNames.contains(normalizeName(name)))

            CoverageMetrics(
              variablesCovered = variableCoverage,
              proceduresCovered = procedureCoverage,
              fileSectionCovered = fileSectionCoverage,
              unmappedItems = (unmappedVariables ++ unmappedProcedures).distinct,
            )

          private def runMavenStaticAnalysis(project: SpringBootProject)
            : ZIO[Any, ValidationError, List[ValidationIssue]] =
            val projectDir = config.outputDir.resolve(project.projectName.toLowerCase)
            val pomPath    = projectDir.resolve("pom.xml")
            Logger.debug(
              s"Maven static analysis for ${project.projectName}: projectDir=$projectDir, pomPath=$pomPath"
            ) *>
              ZIO
                .attemptBlocking(java.nio.file.Files.exists(pomPath))
                .mapError(e => ValidationError.CompileFailed(project.projectName, e.getMessage))
                .flatMap { hasPom =>
                  if !hasPom then
                    Logger.debug(s"Skipping Maven static analysis for ${project.projectName}: missing pom.xml") *>
                      ZIO.succeed(Nil)
                  else
                    Logger.debug(s"Running Maven checkstyle/spotbugs for ${project.projectName}") *>
                      ZIO
                        .attemptBlocking {
                          val process = new ProcessBuilder("mvn", "-q", "checkstyle:check", "spotbugs:check")
                            .directory(projectDir.toFile)
                            .redirectErrorStream(true)
                            .start()
                          val output  = new String(process.getInputStream.readAllBytes())
                          val code    = process.waitFor()
                          (code, truncate(output))
                        }
                        .timeout(120.seconds)
                        .mapError(e => ValidationError.CompileFailed(project.projectName, e.getMessage))
                        .map {
                          case Some((0, _))      => Nil
                          case Some((_, output)) =>
                            List(
                              ValidationIssue(
                                severity = Severity.WARNING,
                                category = IssueCategory.StaticAnalysis,
                                message = "Maven static analysis reported issues (checkstyle/spotbugs)",
                                file = None,
                                line = None,
                                suggestion = Some(output),
                              )
                            )
                          case None              =>
                            List(
                              ValidationIssue(
                                severity = Severity.WARNING,
                                category = IssueCategory.StaticAnalysis,
                                message = "Maven static analysis timed out",
                                file = None,
                                line = None,
                                suggestion = Some("Run checkstyle and spotbugs manually for detailed diagnostics"),
                              )
                            )
                        }
                }

          private def runStaticChecks(project: SpringBootProject, analysis: CobolAnalysis): List[ValidationIssue] =
            val entityIssues =
              if project.entities.exists(e => !e.annotations.exists(_.contains("@Entity"))) then
                List(
                  ValidationIssue(
                    severity = Severity.WARNING,
                    category = IssueCategory.Convention,
                    message = "One or more entities are missing @Entity annotation",
                    file = None,
                    line = None,
                    suggestion = Some("Add @Entity and @Table annotations to all persistent entities"),
                  )
                )
              else Nil

            val serviceIssues =
              if project.services.exists(_.methods.isEmpty) then
                List(
                  ValidationIssue(
                    severity = Severity.ERROR,
                    category = IssueCategory.StaticAnalysis,
                    message = "One or more services have no methods",
                    file = None,
                    line = None,
                    suggestion = Some("Generate at least one service method for each mapped COBOL procedure"),
                  )
                )
              else Nil

            val controllerIssues =
              if project.controllers.exists(_.endpoints.isEmpty) then
                List(
                  ValidationIssue(
                    severity = Severity.WARNING,
                    category = IssueCategory.Convention,
                    message = "One or more controllers have no endpoints",
                    file = None,
                    line = None,
                    suggestion = Some("Ensure every controller has at least one mapped endpoint"),
                  )
                )
              else Nil

            val repoIssues =
              if project.repositories.isEmpty then
                List(
                  ValidationIssue(
                    severity = Severity.WARNING,
                    category = IssueCategory.StaticAnalysis,
                    message = "No repositories generated",
                    file = None,
                    line = None,
                    suggestion = Some("Generate repositories for persistence-backed entities"),
                  )
                )
              else Nil

            val complexityIssues =
              if analysis.complexity.cyclomaticComplexity > 20 then
                List(
                  ValidationIssue(
                    severity = Severity.INFO,
                    category = IssueCategory.Semantic,
                    message = "High complexity source program may require manual review",
                    file = Some(analysis.file.name),
                    line = None,
                    suggestion = Some("Review complex control-flow mappings manually"),
                  )
                )
              else Nil

            entityIssues ++ serviceIssues ++ controllerIssues ++ repoIssues ++ complexityIssues

          private def runSemanticValidation(
            project: SpringBootProject,
            analysis: CobolAnalysis,
          ): ZIO[Any, ValidationError, SemanticValidation] =
            val cobolSource = reconstructCobol(analysis)
            val javaCode    = renderJavaSnapshot(project)
            val prompt      = ValidationPrompts.validateTransformation(cobolSource, javaCode, analysis)
            for
              _          <-
                Logger.debug(
                  s"Running semantic validation for ${project.projectName}: cobolChars=${cobolSource.length}, javaChars=${javaCode.length}, promptChars=${prompt.length}"
                )
              response   <-
                aiService
                  .execute(prompt)
                  .tap(resp =>
                    Logger.debug(
                      s"Semantic AI response for ${project.projectName}: outputChars=${resp.output.length}, metadata=${resp.metadata.keys.mkString(",")}"
                    )
                  )
                  .mapError(e => ValidationError.SemanticValidationFailed(project.projectName, e.message))
              validation <- responseParser
                              .parse[SemanticValidation](response)
                              .mapError(e => ValidationError.SemanticValidationFailed(project.projectName, e.message))
            yield validation

          private def determineStatus(compileResult: CompileResult, issues: List[ValidationIssue]): ValidationStatus =
            if !compileResult.success || issues.exists(_.severity == Severity.ERROR) then ValidationStatus.Failed
            else if issues.exists(_.severity == Severity.WARNING) then ValidationStatus.PassedWithWarnings
            else ValidationStatus.Passed

          private def compileIssue(compileResult: CompileResult): List[ValidationIssue] =
            if compileResult.success then Nil
            else
              List(
                ValidationIssue(
                  severity = Severity.ERROR,
                  category = IssueCategory.Compile,
                  message = s"Maven compile failed (exitCode=${compileResult.exitCode})",
                  file = None,
                  line = None,
                  suggestion = Some("Fix Java compilation errors before continuing"),
                )
              )

          private def coverageUnmapped(metrics: CoverageMetrics): List[ValidationIssue] =
            val coverageIssues = List(
              if metrics.variablesCovered < 100.0 then
                Some(
                  ValidationIssue(
                    severity = Severity.INFO,
                    category = IssueCategory.Coverage,
                    message = f"Variables coverage below 100%% (${metrics.variablesCovered}%.2f%%)",
                    file = None,
                    line = None,
                    suggestion = Some("Map remaining COBOL variables to Java fields"),
                  )
                )
              else None,
              if metrics.proceduresCovered < 100.0 then
                Some(
                  ValidationIssue(
                    severity = Severity.INFO,
                    category = IssueCategory.Coverage,
                    message = f"Procedure coverage below 100%% (${metrics.proceduresCovered}%.2f%%)",
                    file = None,
                    line = None,
                    suggestion = Some("Map remaining COBOL procedures to Java service methods"),
                  )
                )
              else None,
              if metrics.fileSectionCovered < 100.0 then
                Some(
                  ValidationIssue(
                    severity = Severity.INFO,
                    category = IssueCategory.Coverage,
                    message = f"File-section coverage below 100%% (${metrics.fileSectionCovered}%.2f%%)",
                    file = None,
                    line = None,
                    suggestion = Some("Map remaining copybook-backed sections to repositories/services"),
                  )
                )
              else None,
            ).flatten

            val unmappedItemIssues = metrics.unmappedItems.map { item =>
              ValidationIssue(
                severity = Severity.WARNING,
                category = IssueCategory.Coverage,
                message = s"Unmapped COBOL item: $item",
                file = None,
                line = None,
                suggestion = Some("Add explicit mapping for this COBOL item"),
              )
            }

            coverageIssues ++ unmappedItemIssues

          private def writeReports(projectName: String, report: ValidationReport): ZIO[Any, ValidationError, Unit] =
            val jsonPath = reportDir.resolve(s"${projectName.toLowerCase}-validation.json")
            val mdPath   = reportDir.resolve(s"${projectName.toLowerCase}-validation.md")
            for
              _ <- fileService.ensureDirectory(reportDir).mapError(fe =>
                     ValidationError.ReportWriteFailed(reportDir, fe.message)
                   )
              _ <- Logger.debug(s"Writing validation JSON report to $jsonPath")
              _ <- fileService
                     .writeFileAtomic(jsonPath, report.toJsonPretty)
                     .mapError(fe => ValidationError.ReportWriteFailed(jsonPath, fe.message))
              _ <- Logger.debug(s"Writing validation markdown report to $mdPath")
              _ <- fileService
                     .writeFileAtomic(mdPath, renderMarkdown(report))
                     .mapError(fe => ValidationError.ReportWriteFailed(mdPath, fe.message))
            yield ()

          private def renderMarkdown(report: ValidationReport): String =
            val issues =
              if report.issues.isEmpty then "- None"
              else
                report.issues
                  .map(issue => s"- ${issue.severity} [${issue.category}] ${issue.message}")
                  .mkString("\n")

            s"""# Validation Report
               |
               |Project: ${report.projectName}
               |Validated at: ${report.validatedAt}
               |Overall status: ${report.overallStatus}
               |
               |## Compilation
               |- Success: ${report.compileResult.success}
               |- Exit code: ${report.compileResult.exitCode}
               |
               |## Coverage
               |- Variable coverage: ${"%.2f".format(report.coverageMetrics.variablesCovered)}%
               |- Procedure coverage: ${"%.2f".format(report.coverageMetrics.proceduresCovered)}%
               |- File section coverage: ${"%.2f".format(report.coverageMetrics.fileSectionCovered)}%
               |- Unmapped items: ${
                if report.coverageMetrics.unmappedItems.isEmpty then "None"
                else report.coverageMetrics.unmappedItems.mkString(", ")
              }
               |
               |## Semantic Validation
               |- Preserved: ${report.semanticValidation.businessLogicPreserved}
               |- Confidence: ${"%.2f".format(report.semanticValidation.confidence)}
               |- Summary: ${report.semanticValidation.summary}
               |
               |## Issues
               |$issues
               |""".stripMargin

          private def reconstructCobol(analysis: CobolAnalysis): String =
            List(
              analysis.divisions.identification.getOrElse(""),
              analysis.divisions.environment.getOrElse(""),
              analysis.divisions.data.getOrElse(""),
              analysis.divisions.procedure.getOrElse(""),
            ).filter(_.nonEmpty).mkString("\n\n")

          private def renderJavaSnapshot(project: SpringBootProject): String =
            val entityCode     = project.entities.map(_.sourceCode).mkString("\n\n")
            val serviceCode    = project.services.map { service =>
              val methods = service.methods.map(m => s"${m.returnType} ${m.name}() { ${m.body} }").mkString("\n")
              s"class ${service.name} { $methods }"
            }.mkString("\n\n")
            val controllerCode =
              project.controllers.map(c => s"class ${c.name} { /* ${c.basePath} */ }").mkString("\n\n")
            List(entityCode, serviceCode, controllerCode).filter(_.nonEmpty).mkString("\n\n")

          private def normalizeName(value: String): String =
            value.toLowerCase.replaceAll("[^a-z0-9]", "")

          private def truncate(value: String, max: Int = 800): String =
            if value.length <= max then value else value.take(max) + "..."
        }
    }
