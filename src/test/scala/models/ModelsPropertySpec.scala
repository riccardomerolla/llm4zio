package models

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.json.*
import zio.test.*

import models.Codecs.given

/** Property-based tests for domain models using ZIO Test generators.
  *
  * Verifies serialization invariants, model constraints, and error message properties using random data generation with
  * automatic shrinking.
  */
object ModelsPropertySpec extends ZIOSpecDefault:

  // ============================================================================
  // Custom Generators
  // ============================================================================

  private val genPath = Gen.alphaNumericStringBounded(1, 20).map(s => Paths.get(s"/test/$s"))

  private val genInstant = Gen.long(0L, 4102444800000L).map(Instant.ofEpochMilli)

  private val genDuration = Gen.long(1L, 600000L).map(Duration.fromMillis)

  private val genFileType = Gen.elements(FileType.Program, FileType.Copybook, FileType.JCL)

  private val genNodeType = Gen.elements(NodeType.Program, NodeType.Copybook, NodeType.SharedService)

  private val genEdgeType = Gen.elements(EdgeType.Includes, EdgeType.Calls, EdgeType.Uses)

  private val genHttpMethod =
    Gen.elements(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)

  private val genSeverity = Gen.elements(Severity.ERROR, Severity.WARNING, Severity.INFO)

  private val genIssueCategory =
    Gen.elements(
      IssueCategory.Compile,
      IssueCategory.Coverage,
      IssueCategory.StaticAnalysis,
      IssueCategory.Semantic,
      IssueCategory.Convention,
    )

  private val genValidationStatus =
    Gen.elements(ValidationStatus.Passed, ValidationStatus.PassedWithWarnings, ValidationStatus.Failed)

  private val genMigrationStep = Gen.elements(
    MigrationStep.Discovery,
    MigrationStep.Analysis,
    MigrationStep.Mapping,
    MigrationStep.Transformation,
    MigrationStep.Validation,
    MigrationStep.Documentation,
  )

  private val genDiagramType = Gen.elements(DiagramType.Mermaid, DiagramType.PlantUML)

  private val genCobolFile =
    for
      path     <- genPath
      name     <- Gen.alphaNumericStringBounded(1, 15).map(_ + ".cbl")
      size     <- Gen.long(0L, 1000000L)
      lines    <- Gen.long(0L, 50000L)
      modified <- genInstant
      encoding <- Gen.elements("UTF-8", "EBCDIC", "ASCII")
      fileType <- genFileType
    yield CobolFile(path, name, size, lines, modified, encoding, fileType)

  private val genVariable =
    for
      name     <- Gen.alphaNumericStringBounded(1, 20)
      level    <- Gen.elements(1, 5, 10, 15, 77, 88)
      dataType <- Gen.elements("NUMERIC", "ALPHANUMERIC", "GROUP", "CONDITION")
      picture  <- Gen.option(Gen.elements("9(5)", "X(10)", "9(3)V99", "X(30)"))
      usage    <- Gen.option(Gen.elements("COMP", "COMP-3", "DISPLAY"))
    yield Variable(name, level, dataType, picture, usage)

  private val genStatement =
    for
      line    <- Gen.int(1, 10000)
      stype   <- Gen.elements("MOVE", "IF", "PERFORM", "EVALUATE", "CALL", "DISPLAY", "STOP")
      content <- Gen.alphaNumericStringBounded(5, 50)
    yield Statement(line, stype, content)

  private val genComplexityMetrics =
    for
      cc  <- Gen.int(0, 1000)
      loc <- Gen.int(0, 100000)
      np  <- Gen.int(0, 500)
    yield ComplexityMetrics(cc, loc, np)

  private val genDependencyNode =
    for
      id         <- Gen.alphaNumericStringBounded(1, 15)
      name       <- Gen.alphaNumericStringBounded(1, 15)
      nodeType   <- genNodeType
      complexity <- Gen.int(0, 100)
    yield DependencyNode(id, name, nodeType, complexity)

  private val genDependencyEdge =
    for
      from     <- Gen.alphaNumericStringBounded(1, 15)
      to       <- Gen.alphaNumericStringBounded(1, 15)
      edgeType <- genEdgeType
    yield DependencyEdge(from, to, edgeType)

  private val genValidationIssue =
    for
      severity   <- genSeverity
      category   <- genIssueCategory
      message    <- Gen.alphaNumericStringBounded(5, 50)
      file       <- Gen.option(Gen.alphaNumericStringBounded(3, 20))
      line       <- Gen.option(Gen.int(1, 10000))
      suggestion <- Gen.option(Gen.alphaNumericStringBounded(5, 40))
    yield ValidationIssue(severity, category, message, file, line, suggestion)

  private val genCompileResult =
    for
      success  <- Gen.boolean
      exitCode <- Gen.int(0, 255)
      output   <- Gen.alphaNumericStringBounded(0, 50)
    yield CompileResult(success, exitCode, output)

  private val genCoverageMetrics =
    for
      vars     <- Gen.double(0.0, 100.0)
      procs    <- Gen.double(0.0, 100.0)
      fileSec  <- Gen.double(0.0, 100.0)
      unmapped <- Gen.listOfBounded(0, 3)(Gen.alphaNumericStringBounded(1, 10))
    yield CoverageMetrics(vars, procs, fileSec, unmapped)

  private val genGeminiResponse =
    for
      output   <- Gen.alphaNumericStringBounded(1, 50)
      exitCode <- Gen.int(0, 5)
    yield GeminiResponse(output, exitCode)

  private val genTestResults =
    for
      total  <- Gen.int(0, 1000)
      passed <- Gen.int(0, 1000)
      failed <- Gen.int(0, 1000)
    yield TestResults(total, passed, failed)

  def spec: Spec[Any, Nothing] = suite("ModelsPropertySpec")(
    suite("Enum round-trip properties")(
      test("all FileType values survive round-trip") {
        check(genFileType) { ft =>
          val json    = ft.toJson
          val decoded = json.fromJson[FileType]
          assertTrue(decoded == Right(ft))
        }
      },
      test("all NodeType values survive round-trip") {
        check(genNodeType) { nt =>
          val json    = nt.toJson
          val decoded = json.fromJson[NodeType]
          assertTrue(decoded == Right(nt))
        }
      },
      test("all EdgeType values survive round-trip") {
        check(genEdgeType) { et =>
          val json    = et.toJson
          val decoded = json.fromJson[EdgeType]
          assertTrue(decoded == Right(et))
        }
      },
      test("all HttpMethod values survive round-trip") {
        check(genHttpMethod) { hm =>
          val json    = hm.toJson
          val decoded = json.fromJson[HttpMethod]
          assertTrue(decoded == Right(hm))
        }
      },
      test("all Severity values survive round-trip") {
        check(genSeverity) { s =>
          val json    = s.toJson
          val decoded = json.fromJson[Severity]
          assertTrue(decoded == Right(s))
        }
      },
      test("all IssueCategory values survive round-trip") {
        check(genIssueCategory) { ic =>
          val json    = ic.toJson
          val decoded = json.fromJson[IssueCategory]
          assertTrue(decoded == Right(ic))
        }
      },
      test("all ValidationStatus values survive round-trip") {
        check(genValidationStatus) { vs =>
          val json    = vs.toJson
          val decoded = json.fromJson[ValidationStatus]
          assertTrue(decoded == Right(vs))
        }
      },
      test("all MigrationStep values survive round-trip") {
        check(genMigrationStep) { ms =>
          val json    = ms.toJson
          val decoded = json.fromJson[MigrationStep]
          assertTrue(decoded == Right(ms))
        }
      },
      test("all DiagramType values survive round-trip") {
        check(genDiagramType) { dt =>
          val json    = dt.toJson
          val decoded = json.fromJson[DiagramType]
          assertTrue(decoded == Right(dt))
        }
      },
    ),
    suite("Codec round-trip properties")(
      test("Path codec survives round-trip for any valid path") {
        check(Gen.alphaNumericStringBounded(1, 30)) { str =>
          val path    = Paths.get(s"/$str")
          val json    = path.toJson
          val decoded = json.fromJson[java.nio.file.Path]
          assertTrue(decoded == Right(path))
        }
      },
      test("Instant codec survives round-trip") {
        check(genInstant) { instant =>
          val json    = instant.toJson
          val decoded = json.fromJson[Instant]
          assertTrue(decoded == Right(instant))
        }
      },
      test("Duration codec survives round-trip") {
        check(genDuration) { duration =>
          val json    = duration.toJson
          val decoded = json.fromJson[zio.Duration]
          assertTrue(decoded == Right(duration))
        }
      },
    ),
    suite("Domain model round-trip properties")(
      test("CobolFile survives round-trip") {
        check(genCobolFile) { file =>
          val json    = file.toJson
          val decoded = json.fromJson[CobolFile]
          assertTrue(decoded == Right(file))
        }
      },
      test("Variable survives round-trip") {
        check(genVariable) { variable =>
          val json    = variable.toJson
          val decoded = json.fromJson[Variable]
          assertTrue(decoded == Right(variable))
        }
      },
      test("Statement survives round-trip") {
        check(genStatement) { stmt =>
          val json    = stmt.toJson
          val decoded = json.fromJson[Statement]
          assertTrue(decoded == Right(stmt))
        }
      },
      test("ComplexityMetrics survives round-trip") {
        check(genComplexityMetrics) { metrics =>
          val json    = metrics.toJson
          val decoded = json.fromJson[ComplexityMetrics]
          assertTrue(decoded == Right(metrics))
        }
      },
      test("DependencyNode survives round-trip") {
        check(genDependencyNode) { node =>
          val json    = node.toJson
          val decoded = json.fromJson[DependencyNode]
          assertTrue(decoded == Right(node))
        }
      },
      test("DependencyEdge survives round-trip") {
        check(genDependencyEdge) { edge =>
          val json    = edge.toJson
          val decoded = json.fromJson[DependencyEdge]
          assertTrue(decoded == Right(edge))
        }
      },
      test("ValidationIssue survives round-trip") {
        check(genValidationIssue) { issue =>
          val json    = issue.toJson
          val decoded = json.fromJson[ValidationIssue]
          assertTrue(decoded == Right(issue))
        }
      },
      test("CompileResult survives round-trip") {
        check(genCompileResult) { cr =>
          val json    = cr.toJson
          val decoded = json.fromJson[CompileResult]
          assertTrue(decoded == Right(cr))
        }
      },
      test("CoverageMetrics survives round-trip") {
        check(genCoverageMetrics) { cm =>
          val json    = cm.toJson
          val decoded = json.fromJson[CoverageMetrics]
          assertTrue(decoded == Right(cm))
        }
      },
      test("GeminiResponse survives round-trip") {
        check(genGeminiResponse) { gr =>
          val json    = gr.toJson
          val decoded = json.fromJson[GeminiResponse]
          assertTrue(decoded == Right(gr))
        }
      },
      test("TestResults survives round-trip") {
        check(genTestResults) { tr =>
          val json    = tr.toJson
          val decoded = json.fromJson[TestResults]
          assertTrue(decoded == Right(tr))
        }
      },
    ),
    suite("Error type message properties")(
      test("FileError.message always contains path information") {
        check(genPath) { path =>
          val errors = List(
            FileError.NotFound(path),
            FileError.PermissionDenied(path),
            FileError.IOError(path, "test cause"),
            FileError.DirectoryNotEmpty(path),
            FileError.AlreadyExists(path),
          )
          assertTrue(errors.forall(e => e.message.contains(path.toString)))
        }
      },
      test("FileError.InvalidPath message contains the invalid path string") {
        check(Gen.alphaNumericStringBounded(1, 30)) { pathStr =>
          val error = FileError.InvalidPath(pathStr)
          assertTrue(error.message.contains(pathStr))
        }
      },
      test("StateError.message always contains runId") {
        check(Gen.alphaNumericStringBounded(1, 20)) { runId =>
          val errors = List(
            StateError.StateNotFound(runId),
            StateError.InvalidState(runId, "reason"),
            StateError.WriteError(runId, "cause"),
            StateError.ReadError(runId, "cause"),
            StateError.LockError(runId),
          )
          assertTrue(errors.forall(e => e.message.contains(runId)))
        }
      },
      test("GeminiError variants all have non-empty messages") {
        val errors: List[GeminiError] = List(
          GeminiError.ProcessStartFailed("test"),
          GeminiError.OutputReadFailed("test"),
          GeminiError.Timeout(Duration.fromSeconds(30)),
          GeminiError.NonZeroExit(1, "error"),
          GeminiError.ProcessFailed("test"),
          GeminiError.NotInstalled,
          GeminiError.InvalidResponse("test"),
          GeminiError.RateLimitExceeded(Duration.fromSeconds(30)),
          GeminiError.RateLimitMisconfigured("test"),
        )
        assertTrue(errors.forall(e => e.message.nonEmpty))
      },
      test("ParseError variants all have non-empty messages") {
        val errors: List[ParseError] = List(
          ParseError.NoJsonFound("response"),
          ParseError.InvalidJson("json", "error"),
          ParseError.SchemaMismatch("expected", "actual"),
        )
        assertTrue(errors.forall(e => e.message.nonEmpty))
      },
      test("DiscoveryError variants all have non-empty messages") {
        check(genPath) { path =>
          val errors: List[DiscoveryError] = List(
            DiscoveryError.SourceNotFound(path),
            DiscoveryError.ScanFailed(path, "cause"),
            DiscoveryError.MetadataFailed(path, "cause"),
            DiscoveryError.EncodingDetectionFailed(path, "cause"),
            DiscoveryError.ReportWriteFailed(path, "cause"),
            DiscoveryError.InvalidConfig("details"),
          )
          assertTrue(errors.forall(e => e.message.nonEmpty))
        }
      },
      test("AnalysisError variants all have non-empty messages") {
        check(genPath) { path =>
          val errors: List[AnalysisError] = List(
            AnalysisError.FileReadFailed(path, "cause"),
            AnalysisError.GeminiFailed("file", "cause"),
            AnalysisError.ParseFailed("file", "cause"),
            AnalysisError.ReportWriteFailed(path, "cause"),
          )
          assertTrue(errors.forall(e => e.message.nonEmpty))
        }
      },
      test("MappingError variants all have non-empty messages") {
        val errors: List[MappingError] = List(
          MappingError.EmptyAnalysis,
          MappingError.ReportWriteFailed(Paths.get("/test"), "cause"),
        )
        assertTrue(errors.forall(e => e.message.nonEmpty))
      },
      test("TransformError variants all have non-empty messages") {
        val errors: List[TransformError] = List(
          TransformError.GeminiFailed("file", "cause"),
          TransformError.ParseFailed("file", "cause"),
          TransformError.WriteFailed(Paths.get("/test"), "cause"),
        )
        assertTrue(errors.forall(e => e.message.nonEmpty))
      },
      test("ValidationError variants all have non-empty messages") {
        val errors: List[ValidationError] = List(
          ValidationError.CompileFailed("project", "cause"),
          ValidationError.SemanticValidationFailed("project", "cause"),
          ValidationError.ReportWriteFailed(Paths.get("/test"), "cause"),
          ValidationError.InvalidProject("project", "reason"),
        )
        assertTrue(errors.forall(e => e.message.nonEmpty))
      },
      test("DocError variants all have non-empty messages") {
        val errors: List[DocError] = List(
          DocError.InvalidResult("reason"),
          DocError.ReportWriteFailed(Paths.get("/test"), "cause"),
          DocError.RenderFailed("cause"),
        )
        assertTrue(errors.forall(e => e.message.nonEmpty))
      },
      test("RateLimitError variants all have non-empty messages") {
        val errors: List[RateLimitError] = List(
          RateLimitError.AcquireTimeout(Duration.fromSeconds(30)),
          RateLimitError.InvalidConfig("details"),
        )
        assertTrue(errors.forall(e => e.message.nonEmpty))
      },
    ),
    suite("Empty/default model properties")(
      test("CobolAnalysis.empty has expected defaults") {
        val empty = CobolAnalysis.empty
        assertTrue(
          empty.variables.isEmpty,
          empty.procedures.isEmpty,
          empty.copybooks.isEmpty,
          empty.complexity.cyclomaticComplexity == 0,
        )
      },
      test("DependencyGraph.empty has empty collections") {
        val empty = DependencyGraph.empty
        assertTrue(
          empty.nodes.isEmpty,
          empty.edges.isEmpty,
          empty.serviceCandidates.isEmpty,
        )
      },
      test("SpringBootProject.empty has expected defaults") {
        val empty = SpringBootProject.empty
        assertTrue(
          empty.projectName.isEmpty,
          empty.entities.isEmpty,
          empty.services.isEmpty,
          empty.controllers.isEmpty,
          empty.repositories.isEmpty,
        )
      },
      test("ValidationReport.empty has failed status") {
        val empty = ValidationReport.empty
        assertTrue(
          empty.overallStatus == ValidationStatus.Failed,
          !empty.compileResult.success,
          empty.issues.isEmpty,
        )
      },
      test("MigrationDocumentation.empty has empty strings") {
        val empty = MigrationDocumentation.empty
        assertTrue(
          empty.summaryReport.isEmpty,
          empty.designDocument.isEmpty,
          empty.diagrams.isEmpty,
        )
      },
      test("MigrationState.empty creates with Discovery step") {
        for state <- MigrationState.empty
        yield assertTrue(
          state.currentStep == MigrationStep.Discovery,
          state.completedSteps.isEmpty,
          state.errors.isEmpty,
          state.fileInventory.isEmpty,
          state.analyses.isEmpty,
          state.dependencyGraph.isEmpty,
          state.projects.isEmpty,
          state.validationReports.isEmpty,
        )
      },
    ),
    suite("InventorySummary consistency")(
      test("totalFiles is sum of categories") {
        check(Gen.int(0, 100), Gen.int(0, 100), Gen.int(0, 100)) { (programs, copybooks, jclFiles) =>
          val summary = InventorySummary(
            totalFiles = programs + copybooks + jclFiles,
            programFiles = programs,
            copybooks = copybooks,
            jclFiles = jclFiles,
            totalLines = 0L,
            totalBytes = 0L,
          )
          assertTrue(
            summary.totalFiles == summary.programFiles + summary.copybooks + summary.jclFiles
          )
        }
      }
    ),
  )
