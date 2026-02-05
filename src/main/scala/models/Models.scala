package models

import java.nio.file.{ Path, Paths }
import java.time.Instant

import zio.json.*

/** Custom JSON codecs for Java types that don't have built-in ZIO JSON support
  */
object Codecs:
  /** Codec for java.nio.file.Path - serializes as string */
  given JsonCodec[Path] = JsonCodec[String].transform(
    str => Paths.get(str),
    path => path.toString,
  )

  /** Codec for java.time.Instant - serializes as ISO-8601 string */
  given JsonCodec[Instant] = JsonCodec[String].transform(
    str => Instant.parse(str),
    instant => instant.toString,
  )

// Import custom codecs into scope for all model derivations
import Codecs.given

/** Domain models for the legacy modernization system */

// ============================================================================
// Discovery Phase
// ============================================================================

enum FileType derives JsonCodec:
  case Program, Copybook, JCL

case class CobolFile(
  path: Path,
  name: String,
  size: Long,
  lastModified: Instant,
  encoding: String,
  fileType: FileType,
) derives JsonCodec

case class FileInventory(
  files: List[CobolFile],
  metadata: Map[String, String],
) derives JsonCodec

// ============================================================================
// Analysis Phase
// ============================================================================

case class CobolDivisions(
  identification: Option[String],
  environment: Option[String],
  data: Option[String],
  procedure: Option[String],
) derives JsonCodec

case class Variable(
  name: String,
  level: Int,
  dataType: String,
  picture: Option[String],
  usage: Option[String],
) derives JsonCodec

case class Statement(
  lineNumber: Int,
  statementType: String,
  content: String,
) derives JsonCodec

case class Procedure(
  name: String,
  paragraphs: List[String],
  statements: List[Statement],
) derives JsonCodec

case class ComplexityMetrics(
  cyclomaticComplexity: Int,
  linesOfCode: Int,
  numberOfProcedures: Int,
) derives JsonCodec

case class CobolAnalysis(
  file: CobolFile,
  divisions: CobolDivisions,
  variables: List[Variable],
  procedures: List[Procedure],
  copybooks: List[String],
  complexity: ComplexityMetrics,
) derives JsonCodec

object CobolAnalysis:
  def empty: CobolAnalysis = CobolAnalysis(
    file = CobolFile(
      path = Paths.get(""),
      name = "",
      size = 0L,
      lastModified = Instant.EPOCH,
      encoding = "UTF-8",
      fileType = FileType.Program,
    ),
    divisions = CobolDivisions(None, None, None, None),
    variables = List.empty,
    procedures = List.empty,
    copybooks = List.empty,
    complexity = ComplexityMetrics(0, 0, 0),
  )

// ============================================================================
// Dependency Mapping Phase
// ============================================================================

enum NodeType derives JsonCodec:
  case Program, Copybook, SharedService

enum EdgeType derives JsonCodec:
  case Includes, Calls, Uses

case class DependencyNode(
  id: String,
  name: String,
  nodeType: NodeType,
  complexity: Int,
) derives JsonCodec

case class DependencyEdge(
  from: String,
  to: String,
  edgeType: EdgeType,
) derives JsonCodec

case class DependencyGraph(
  nodes: List[DependencyNode],
  edges: List[DependencyEdge],
  serviceCandidates: List[String],
) derives JsonCodec

object DependencyGraph:
  def empty: DependencyGraph = DependencyGraph(List.empty, List.empty, List.empty)

// ============================================================================
// Transformation Phase
// ============================================================================

enum HttpMethod derives JsonCodec:
  case GET, POST, PUT, DELETE, PATCH

case class JavaPackage(
  name: String,
  classes: List[String],
) derives JsonCodec

case class JavaField(
  name: String,
  javaType: String,
  annotations: List[String],
) derives JsonCodec

case class JavaEntity(
  name: String,
  fields: List[JavaField],
  annotations: List[String],
) derives JsonCodec

case class JavaParameter(
  name: String,
  javaType: String,
) derives JsonCodec

case class JavaMethod(
  name: String,
  returnType: String,
  parameters: List[JavaParameter],
  body: String,
) derives JsonCodec

case class JavaService(
  name: String,
  methods: List[JavaMethod],
) derives JsonCodec

case class RestEndpoint(
  path: String,
  method: HttpMethod,
  methodName: String,
) derives JsonCodec

case class JavaController(
  name: String,
  basePath: String,
  endpoints: List[RestEndpoint],
) derives JsonCodec

case class ProjectConfiguration(
  groupId: String,
  artifactId: String,
  dependencies: List[String],
) derives JsonCodec

case class SpringBootProject(
  name: String,
  packages: List[JavaPackage],
  entities: List[JavaEntity],
  services: List[JavaService],
  controllers: List[JavaController],
  configuration: ProjectConfiguration,
) derives JsonCodec

object SpringBootProject:
  def empty: SpringBootProject = SpringBootProject(
    name = "",
    packages = List.empty,
    entities = List.empty,
    services = List.empty,
    controllers = List.empty,
    configuration = ProjectConfiguration("", "", List.empty),
  )

// ============================================================================
// Validation Phase
// ============================================================================

case class TestResults(
  totalTests: Int,
  passed: Int,
  failed: Int,
) derives JsonCodec

case class CoverageMetrics(
  lineCoverage: Double,
  branchCoverage: Double,
  methodCoverage: Double,
) derives JsonCodec

case class ValidationReport(
  testResults: TestResults,
  coverageMetrics: CoverageMetrics,
  staticAnalysisIssues: List[String],
  businessLogicValidation: Boolean,
) derives JsonCodec

object ValidationReport:
  def empty: ValidationReport = ValidationReport(
    testResults = TestResults(0, 0, 0),
    coverageMetrics = CoverageMetrics(0.0, 0.0, 0.0),
    staticAnalysisIssues = List.empty,
    businessLogicValidation = false,
  )

// ============================================================================
// Documentation Phase
// ============================================================================

case class MigrationDocumentation(
  technicalDesign: String,
  apiReference: String,
  dataModelMappings: String,
  migrationSummary: String,
  deploymentGuide: String,
) derives JsonCodec

object MigrationDocumentation:
  def empty: MigrationDocumentation = MigrationDocumentation("", "", "", "", "")

// ============================================================================
// State Management
// ============================================================================

enum MigrationStep derives JsonCodec:
  case Discovery, Analysis, Mapping, Transformation, Validation, Documentation

case class MigrationState(
  currentStep: MigrationStep,
  completedSteps: List[MigrationStep],
  fileInventory: Option[FileInventory],
  analyses: List[CobolAnalysis],
  dependencyGraph: Option[DependencyGraph],
  projects: List[SpringBootProject],
  validationReports: List[ValidationReport],
  startTime: Instant,
  lastCheckpoint: Instant,
) derives JsonCodec

object MigrationState:
  def empty: MigrationState = MigrationState(
    currentStep = MigrationStep.Discovery,
    completedSteps = List.empty,
    fileInventory = None,
    analyses = List.empty,
    dependencyGraph = None,
    projects = List.empty,
    validationReports = List.empty,
    startTime = Instant.now(),
    lastCheckpoint = Instant.now(),
  )

// ============================================================================
// Agent Messages
// ============================================================================

enum AgentType derives JsonCodec:
  case CobolDiscovery, CobolAnalyzer, DependencyMapper, JavaTransformer, Validation, Documentation

case class AgentMessage(
  id: String,
  sourceAgent: AgentType,
  targetAgent: AgentType,
  payload: String,
  timestamp: Instant,
) derives JsonCodec
