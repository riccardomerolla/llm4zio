package models

import java.nio.file.{ Path, Paths }
import java.time.Instant

import zio.*
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

  /** Codec for zio.Duration - serializes as milliseconds */
  given JsonCodec[zio.Duration] = JsonCodec[Long].transform(
    millis => zio.Duration.fromMillis(millis),
    duration => duration.toMillis,
  )

// Import custom codecs into scope for all model derivations
import Codecs.given

/** Domain models for the legacy modernization system */

// ============================================================================
// Error Types
// ============================================================================

/** File operation errors with typed error handling */
enum FileError(val message: String) derives JsonCodec:
  case NotFound(path: Path)               extends FileError(s"File not found: $path")
  case PermissionDenied(path: Path)       extends FileError(s"Permission denied: $path")
  case IOError(path: Path, cause: String) extends FileError(s"I/O error at $path: $cause")
  case InvalidPath(path: String)          extends FileError(s"Invalid path: $path")
  case DirectoryNotEmpty(path: Path)      extends FileError(s"Directory not empty: $path")
  case AlreadyExists(path: Path)          extends FileError(s"Already exists: $path")

/** State service errors with typed error handling */
enum StateError(val message: String) derives JsonCodec:
  case StateNotFound(runId: String)                extends StateError(s"State not found for run: $runId")
  case InvalidState(runId: String, reason: String) extends StateError(s"Invalid state for run $runId: $reason")
  case WriteError(runId: String, cause: String)    extends StateError(s"Failed to write state for run $runId: $cause")
  case ReadError(runId: String, cause: String)     extends StateError(s"Failed to read state for run $runId: $cause")
  case LockError(runId: String)                    extends StateError(s"Failed to acquire lock for run: $runId")

/** Gemini CLI service errors with typed error handling */
enum GeminiError(val message: String) derives JsonCodec:
  case ProcessStartFailed(cause: String)      extends GeminiError(s"Failed to start Gemini process: $cause")
  case OutputReadFailed(cause: String)        extends GeminiError(s"Failed to read Gemini output: $cause")
  case Timeout(duration: zio.Duration)        extends GeminiError(s"Gemini process timed out after ${duration.toSeconds}s")
  case NonZeroExit(code: Int, output: String) extends GeminiError(s"Gemini process exited with code $code: $output")
  case ProcessFailed(cause: String)           extends GeminiError(s"Gemini process failed: $cause")
  case NotInstalled                           extends GeminiError("Gemini CLI is not installed or not in PATH")
  case InvalidResponse(output: String)        extends GeminiError(s"Invalid response from Gemini: $output")
  case RateLimitExceeded(timeout: zio.Duration)
    extends GeminiError(s"Gemini rate limit exceeded after ${timeout.toSeconds}s")
  case RateLimitMisconfigured(details: String)
    extends GeminiError(s"Rate limiter misconfigured: $details")

/** Gemini response parsing errors with typed error handling */
enum ParseError(val message: String) derives JsonCodec:
  case NoJsonFound(response: String)            extends ParseError("No JSON found in Gemini response")
  case InvalidJson(json: String, error: String) extends ParseError(s"Invalid JSON: $error")
  case SchemaMismatch(expected: String, actual: String)
    extends ParseError(s"Schema mismatch. Expected: $expected. Error: $actual")

/** Rate limiter errors with typed error handling */
enum RateLimitError(val message: String) derives JsonCodec:
  case AcquireTimeout(timeout: zio.Duration)
    extends RateLimitError(s"Rate limiter timed out after ${timeout.toSeconds}s")
  case InvalidConfig(details: String) extends RateLimitError(s"Invalid rate limiter config: $details")

/** Discovery errors with typed error handling */
enum DiscoveryError(val message: String) derives JsonCodec:
  case SourceNotFound(path: Path)                extends DiscoveryError(s"Source directory not found: $path")
  case ScanFailed(path: Path, cause: String)     extends DiscoveryError(s"Failed to scan directory $path: $cause")
  case MetadataFailed(path: Path, cause: String) extends DiscoveryError(s"Failed to read metadata for $path: $cause")
  case EncodingDetectionFailed(path: Path, cause: String)
    extends DiscoveryError(s"Failed to detect encoding for $path: $cause")
  case ReportWriteFailed(path: Path, cause: String)
    extends DiscoveryError(s"Failed to write discovery report at $path: $cause")
  case InvalidConfig(details: String)            extends DiscoveryError(s"Invalid discovery config: $details")

/** Analysis errors with typed error handling */
enum AnalysisError(val message: String) derives JsonCodec:
  case FileReadFailed(path: Path, cause: String) extends AnalysisError(s"Failed to read COBOL file $path: $cause")
  case GeminiFailed(fileName: String, cause: String)
    extends AnalysisError(s"Gemini analysis failed for $fileName: $cause")
  case ParseFailed(fileName: String, cause: String)
    extends AnalysisError(s"Failed to parse analysis for $fileName: $cause")
  case ReportWriteFailed(path: Path, cause: String)
    extends AnalysisError(s"Failed to write analysis report at $path: $cause")

/** Dependency mapping errors with typed error handling */
enum MappingError(val message: String) derives JsonCodec:
  case EmptyAnalysis extends MappingError("No COBOL analyses provided for dependency mapping")
  case ReportWriteFailed(path: Path, cause: String)
    extends MappingError(s"Failed to write dependency mapping report at $path: $cause")

// ============================================================================
// Gemini Service
// ============================================================================

/** Response from Gemini CLI execution */
case class GeminiResponse(
  output: String,
  exitCode: Int,
) derives JsonCodec

// ============================================================================
// Discovery Phase
// ============================================================================

enum FileType derives JsonCodec:
  case Program, Copybook, JCL

case class CobolFile(
  path: Path,
  name: String,
  size: Long,
  lineCount: Long,
  lastModified: Instant,
  encoding: String,
  fileType: FileType,
) derives JsonCodec

case class InventorySummary(
  totalFiles: Int,
  programFiles: Int,
  copybooks: Int,
  jclFiles: Int,
  totalLines: Long,
  totalBytes: Long,
) derives JsonCodec

case class FileInventory(
  discoveredAt: Instant,
  sourceDirectory: Path,
  files: List[CobolFile],
  summary: InventorySummary,
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
      lineCount = 0L,
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

case class MigrationError(
  step: MigrationStep,
  message: String,
  timestamp: Instant,
) derives JsonCodec

/** Configuration for the migration tool with all necessary settings
  *
  * @param sourceDir
  *   Path to the COBOL source directory
  * @param outputDir
  *   Path to the output directory for generated Java code
  * @param stateDir
  *   Path to the directory for storing migration state and checkpoints
  * @param geminiModel
  *   Gemini model name to use for AI operations
  * @param geminiTimeout
  *   Timeout duration for Gemini API calls
  * @param geminiMaxRetries
  *   Maximum number of retry attempts for failed Gemini API calls
  * @param geminiRequestsPerMinute
  *   Maximum Gemini requests per minute (rate limit)
  * @param geminiBurstSize
  *   Maximum burst size for rate limiter
  * @param geminiAcquireTimeout
  *   Timeout for waiting on rate limiter token
  * @param discoveryMaxDepth
  *   Maximum directory depth for discovery scanning
  * @param discoveryExcludePatterns
  *   Glob patterns to exclude during discovery
  * @param parallelism
  *   Number of parallel processing workers
  * @param batchSize
  *   Number of files to process in each batch
  * @param enableCheckpointing
  *   Whether to enable automatic checkpointing
  * @param resumeFromCheckpoint
  *   Optional checkpoint run ID to resume from
  * @param dryRun
  *   If true, perform a dry run without writing output files
  * @param verbose
  *   Enable verbose logging output
  */
case class MigrationConfig(
  // Directories
  sourceDir: Path,
  outputDir: Path,
  stateDir: Path = Paths.get(".migration-state"),

  // Gemini settings
  geminiModel: String = "gemini-2.0-flash",
  geminiTimeout: zio.Duration = zio.Duration.fromSeconds(60),
  geminiMaxRetries: Int = 3,
  geminiRequestsPerMinute: Int = 60,
  geminiBurstSize: Int = 10,
  geminiAcquireTimeout: zio.Duration = zio.Duration.fromSeconds(30),

  // Discovery settings
  discoveryMaxDepth: Int = 25,
  discoveryExcludePatterns: List[String] = List(
    "**/.git/**",
    "**/target/**",
    "**/node_modules/**",
    "**/.idea/**",
    "**/.vscode/**",
    "**/backup/**",
    "**/*.bak",
    "**/*.tmp",
    "**/*~",
  ),

  // Processing
  parallelism: Int = 4,
  batchSize: Int = 10,

  // Features
  enableCheckpointing: Boolean = true,
  resumeFromCheckpoint: Option[String] = None,
  dryRun: Boolean = false,
  verbose: Boolean = false,
) derives JsonCodec

case class MigrationState(
  runId: String,
  startedAt: Instant,
  currentStep: MigrationStep,
  completedSteps: Set[MigrationStep],
  artifacts: Map[String, String], // MigrationStep name -> artifact path
  errors: List[MigrationError],
  config: MigrationConfig,
  fileInventory: Option[FileInventory],
  analyses: List[CobolAnalysis],
  dependencyGraph: Option[DependencyGraph],
  projects: List[SpringBootProject],
  validationReports: List[ValidationReport],
  lastCheckpoint: Instant,
) derives JsonCodec

object MigrationState:
  def empty: UIO[MigrationState] =
    Clock.instant.map { now =>
      MigrationState(
        runId = s"run-${now.toEpochMilli}",
        startedAt = now,
        currentStep = MigrationStep.Discovery,
        completedSteps = Set.empty,
        artifacts = Map.empty,
        errors = List.empty,
        config = MigrationConfig(
          sourceDir = Paths.get("cobol-source"),
          outputDir = Paths.get("java-output"),
        ),
        fileInventory = None,
        analyses = List.empty,
        dependencyGraph = None,
        projects = List.empty,
        validationReports = List.empty,
        lastCheckpoint = now,
      )
    }

case class MigrationRunSummary(
  runId: String,
  startedAt: Instant,
  currentStep: MigrationStep,
  completedSteps: Set[MigrationStep],
  errorCount: Int,
) derives JsonCodec

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
