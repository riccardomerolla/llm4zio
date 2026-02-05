package models

import java.nio.file.{ Path, Paths }
import java.time.Instant

/** Domain models for the legacy modernization system
  */

// Discovery Phase
case class CobolFile(
  path: Path,
  name: String,
  size: Long,
  lastModified: Instant,
  encoding: String,
  fileType: FileType,
)

enum FileType:
  case Program, Copybook, JCL

case class FileInventory(
  files: List[CobolFile],
  metadata: Map[String, String],
)

// Analysis Phase
case class CobolAnalysis(
  file: CobolFile,
  divisions: CobolDivisions,
  variables: List[Variable],
  procedures: List[Procedure],
  copybooks: List[String],
  complexity: ComplexityMetrics,
)

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

case class CobolDivisions(
  identification: Option[String],
  environment: Option[String],
  data: Option[String],
  procedure: Option[String],
)

case class Variable(
  name: String,
  level: Int,
  dataType: String,
  picture: Option[String],
  usage: Option[String],
)

case class Procedure(
  name: String,
  paragraphs: List[String],
  statements: List[Statement],
)

case class Statement(
  lineNumber: Int,
  statementType: String,
  content: String,
)

case class ComplexityMetrics(
  cyclomaticComplexity: Int,
  linesOfCode: Int,
  numberOfProcedures: Int,
)

// Dependency Mapping Phase
case class DependencyGraph(
  nodes: List[DependencyNode],
  edges: List[DependencyEdge],
  serviceCandidates: List[String],
)

object DependencyGraph:
  def empty: DependencyGraph = DependencyGraph(List.empty, List.empty, List.empty)

case class DependencyNode(
  id: String,
  name: String,
  nodeType: NodeType,
  complexity: Int,
)

enum NodeType:
  case Program, Copybook, SharedService

case class DependencyEdge(
  from: String,
  to: String,
  edgeType: EdgeType,
)

enum EdgeType:
  case Includes, Calls, Uses

// Transformation Phase
case class SpringBootProject(
  name: String,
  packages: List[JavaPackage],
  entities: List[JavaEntity],
  services: List[JavaService],
  controllers: List[JavaController],
  configuration: ProjectConfiguration,
)

object SpringBootProject:
  def empty: SpringBootProject = SpringBootProject(
    name = "",
    packages = List.empty,
    entities = List.empty,
    services = List.empty,
    controllers = List.empty,
    configuration = ProjectConfiguration("", "", List.empty),
  )

case class JavaPackage(
  name: String,
  classes: List[String],
)

case class JavaEntity(
  name: String,
  fields: List[JavaField],
  annotations: List[String],
)

case class JavaField(
  name: String,
  javaType: String,
  annotations: List[String],
)

case class JavaService(
  name: String,
  methods: List[JavaMethod],
)

case class JavaMethod(
  name: String,
  returnType: String,
  parameters: List[JavaParameter],
  body: String,
)

case class JavaParameter(
  name: String,
  javaType: String,
)

case class JavaController(
  name: String,
  basePath: String,
  endpoints: List[RestEndpoint],
)

case class RestEndpoint(
  path: String,
  method: HttpMethod,
  methodName: String,
)

enum HttpMethod:
  case GET, POST, PUT, DELETE, PATCH

case class ProjectConfiguration(
  groupId: String,
  artifactId: String,
  dependencies: List[String],
)

// Validation Phase
case class ValidationReport(
  testResults: TestResults,
  coverageMetrics: CoverageMetrics,
  staticAnalysisIssues: List[String],
  businessLogicValidation: Boolean,
)

object ValidationReport:
  def empty: ValidationReport = ValidationReport(
    testResults = TestResults(0, 0, 0),
    coverageMetrics = CoverageMetrics(0.0, 0.0, 0.0),
    staticAnalysisIssues = List.empty,
    businessLogicValidation = false,
  )

case class TestResults(
  totalTests: Int,
  passed: Int,
  failed: Int,
)

case class CoverageMetrics(
  lineCoverage: Double,
  branchCoverage: Double,
  methodCoverage: Double,
)

// Documentation Phase
case class MigrationDocumentation(
  technicalDesign: String,
  apiReference: String,
  dataModelMappings: String,
  migrationSummary: String,
  deploymentGuide: String,
)

object MigrationDocumentation:
  def empty: MigrationDocumentation = MigrationDocumentation("", "", "", "", "")

// State Management
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
)

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

enum MigrationStep:
  case Discovery, Analysis, Mapping, Transformation, Validation, Documentation

// Agent Messages
case class AgentMessage(
  id: String,
  sourceAgent: AgentType,
  targetAgent: AgentType,
  payload: String, // JSON payload
  timestamp: Instant,
)

enum AgentType:
  case CobolDiscovery, CobolAnalyzer, DependencyMapper, JavaTransformer, Validation, Documentation
