package models

import java.nio.file.Path

import zio.json.*

import models.Codecs.given

enum FileError(val message: String) derives JsonCodec:
  case NotFound(path: Path)               extends FileError(s"File not found: $path")
  case PermissionDenied(path: Path)       extends FileError(s"Permission denied: $path")
  case IOError(path: Path, cause: String) extends FileError(s"I/O error at $path: $cause")
  case InvalidPath(path: String)          extends FileError(s"Invalid path: $path")
  case DirectoryNotEmpty(path: Path)      extends FileError(s"Directory not empty: $path")
  case AlreadyExists(path: Path)          extends FileError(s"Already exists: $path")

enum StateError(val message: String) derives JsonCodec:
  case StateNotFound(runId: String)                extends StateError(s"State not found for run: $runId")
  case InvalidState(runId: String, reason: String) extends StateError(s"Invalid state for run $runId: $reason")
  case WriteError(runId: String, cause: String)    extends StateError(s"Failed to write state for run $runId: $cause")
  case ReadError(runId: String, cause: String)     extends StateError(s"Failed to read state for run $runId: $cause")
  case LockError(runId: String)                    extends StateError(s"Failed to acquire lock for run: $runId")

enum WorkspaceError(val message: String) derives JsonCodec:
  case CreationFailed(runId: String, cause: String)
    extends WorkspaceError(s"Failed to create workspace for run $runId: $cause")
  case CleanupFailed(runId: String, cause: String)
    extends WorkspaceError(s"Failed to cleanup workspace for run $runId: $cause")
  case NotFound(runId: String) extends WorkspaceError(s"Workspace not found for run: $runId")
  case AlreadyExists(runId: String)
    extends WorkspaceError(s"Workspace already exists for run: $runId")
  case InvalidConfiguration(runId: String, reason: String)
    extends WorkspaceError(s"Invalid workspace configuration for run $runId: $reason")
  case IOError(runId: String, cause: String)
    extends WorkspaceError(s"Workspace I/O error for run $runId: $cause")

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

enum AIError(val message: String) derives JsonCodec:
  case ProcessStartFailed(cause: String)        extends AIError(s"Failed to start AI process: $cause")
  case OutputReadFailed(cause: String)          extends AIError(s"Failed to read AI output: $cause")
  case Timeout(duration: zio.Duration)          extends AIError(s"AI request timed out after ${duration.toSeconds}s")
  case NonZeroExit(code: Int, output: String)   extends AIError(s"AI process exited with code $code: $output")
  case ProcessFailed(cause: String)             extends AIError(s"AI process failed: $cause")
  case NotAvailable(provider: String)           extends AIError(s"AI provider not available: $provider")
  case InvalidResponse(output: String)          extends AIError(s"Invalid AI response: $output")
  case RateLimitExceeded(timeout: zio.Duration)
    extends AIError(s"AI rate limit exceeded after ${timeout.toSeconds}s")
  case RateLimitMisconfigured(details: String)
    extends AIError(s"AI rate limiter misconfigured: $details")
  case HttpError(statusCode: Int, body: String) extends AIError(s"AI provider HTTP error $statusCode: $body")
  case AuthenticationFailed(provider: String)   extends AIError(s"AI provider authentication failed: $provider")
  case ProviderUnavailable(provider: String, cause: String)
    extends AIError(s"AI provider unavailable: $provider. Cause: $cause")

enum ParseError(val message: String) derives JsonCodec:
  case NoJsonFound(response: String)            extends ParseError("No JSON found in Gemini response")
  case InvalidJson(json: String, error: String) extends ParseError(s"Invalid JSON: $error")
  case SchemaMismatch(expected: String, actual: String)
    extends ParseError(s"Schema mismatch. Expected: $expected. Error: $actual")

enum RateLimitError(val message: String) derives JsonCodec:
  case AcquireTimeout(timeout: zio.Duration)
    extends RateLimitError(s"Rate limiter timed out after ${timeout.toSeconds}s")
  case InvalidConfig(details: String) extends RateLimitError(s"Invalid rate limiter config: $details")

enum DiscoveryError(val message: String) derives JsonCodec:
  case SourceNotFound(path: Path)                extends DiscoveryError(s"Source directory not found: $path")
  case ScanFailed(path: Path, cause: String)     extends DiscoveryError(s"Failed to scan directory $path: $cause")
  case MetadataFailed(path: Path, cause: String) extends DiscoveryError(s"Failed to read metadata for $path: $cause")
  case EncodingDetectionFailed(path: Path, cause: String)
    extends DiscoveryError(s"Failed to detect encoding for $path: $cause")
  case ReportWriteFailed(path: Path, cause: String)
    extends DiscoveryError(s"Failed to write discovery report at $path: $cause")
  case ReportSchemaMismatch(path: Path, cause: String)
    extends DiscoveryError(s"Discovery report schema validation failed for $path: $cause")
  case InvalidConfig(details: String)            extends DiscoveryError(s"Invalid discovery config: $details")

enum AnalysisError(val message: String) derives JsonCodec:
  case FileReadFailed(path: Path, cause: String) extends AnalysisError(s"Failed to read COBOL file $path: $cause")
  case AIFailed(fileName: String, cause: String)
    extends AnalysisError(s"AI analysis failed for $fileName: $cause")
  case ParseFailed(fileName: String, cause: String)
    extends AnalysisError(s"Failed to parse analysis for $fileName: $cause")
  case ValidationFailed(fileName: String, cause: String)
    extends AnalysisError(s"Analysis validation failed for $fileName: $cause")
  case ReportWriteFailed(path: Path, cause: String)
    extends AnalysisError(s"Failed to write analysis report at $path: $cause")

object AnalysisError:
  def GeminiFailed(fileName: String, cause: String): AnalysisError =
    AIFailed(fileName, cause)

enum BusinessLogicExtractionError(val message: String) derives JsonCodec:
  case AIFailed(fileName: String, cause: String)
    extends BusinessLogicExtractionError(s"AI business logic extraction failed for $fileName: $cause")
  case ParseFailed(fileName: String, cause: String)
    extends BusinessLogicExtractionError(s"Failed to parse business logic extraction for $fileName: $cause")
  case ReportWriteFailed(path: Path, cause: String)
    extends BusinessLogicExtractionError(s"Failed to write business logic report at $path: $cause")

enum MappingError(val message: String) derives JsonCodec:
  case EmptyAnalysis extends MappingError("No COBOL analyses provided for dependency mapping")
  case ReportWriteFailed(path: Path, cause: String)
    extends MappingError(s"Failed to write dependency mapping report at $path: $cause")

enum TransformError(val message: String) derives JsonCodec:
  case AIFailed(fileName: String, cause: String)
    extends TransformError(s"AI transform failed for $fileName: $cause")
  case ParseFailed(fileName: String, cause: String)
    extends TransformError(s"Failed to parse transform output for $fileName: $cause")
  case WriteFailed(path: Path, cause: String)
    extends TransformError(s"Failed to write generated output at $path: $cause")

object TransformError:
  def GeminiFailed(fileName: String, cause: String): TransformError =
    AIFailed(fileName, cause)

enum ValidationError(val message: String) derives JsonCodec:
  case CompileFailed(projectName: String, cause: String)
    extends ValidationError(s"Compilation failed for $projectName: $cause")
  case SemanticValidationFailed(projectName: String, cause: String)
    extends ValidationError(s"Semantic validation failed for $projectName: $cause")
  case ReportWriteFailed(path: Path, cause: String)
    extends ValidationError(s"Failed to write validation report at $path: $cause")
  case InvalidProject(projectName: String, reason: String)
    extends ValidationError(s"Invalid project $projectName: $reason")

enum DocError(val message: String) derives JsonCodec:
  case InvalidResult(reason: String) extends DocError(s"Invalid migration result: $reason")
  case ReportWriteFailed(path: Path, cause: String)
    extends DocError(s"Failed to write documentation at $path: $cause")
  case RenderFailed(cause: String)   extends DocError(s"Failed to render documentation: $cause")

enum OrchestratorError derives JsonCodec:
  case DiscoveryFailed(error: DiscoveryError)
  case AnalysisFailed(file: String, error: AnalysisError)
  case MappingFailed(error: MappingError)
  case TransformationFailed(file: String, error: TransformError)
  case ValidationFailed(file: String, error: ValidationError)
  case DocumentationFailed(error: DocError)
  case StateFailed(error: StateError)
  case Interrupted(message: String)

object OrchestratorError:
  extension (error: OrchestratorError)
    def message: String = error match
      case OrchestratorError.DiscoveryFailed(inner)            =>
        s"Discovery failed: ${inner.message}"
      case OrchestratorError.AnalysisFailed(file, inner)       =>
        s"Analysis failed for $file: ${inner.message}"
      case OrchestratorError.MappingFailed(inner)              =>
        s"Mapping failed: ${inner.message}"
      case OrchestratorError.TransformationFailed(file, inner) =>
        s"Transformation failed for $file: ${inner.message}"
      case OrchestratorError.ValidationFailed(file, inner)     =>
        s"Validation failed for $file: ${inner.message}"
      case OrchestratorError.DocumentationFailed(inner)        =>
        s"Documentation failed: ${inner.message}"
      case OrchestratorError.StateFailed(inner)                =>
        s"State operation failed: ${inner.message}"
      case OrchestratorError.Interrupted(msg)                  =>
        s"Migration interrupted: $msg"
