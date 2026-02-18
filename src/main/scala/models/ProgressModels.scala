package models

import zio.json.*
import zio.json.ast.Json

enum TaskStepProgressStatus derives JsonCodec:
  case Running, Completed, Failed

case class TaskStepProgress(
  taskRunId: Long,
  stepName: String,
  percentComplete: Double,
  message: String,
  status: TaskStepProgressStatus,
) derives JsonCodec

/** Metrics captured during step execution.
  *
  * @param tokensUsed
  *   Total tokens consumed (if AI was involved)
  * @param latencyMs
  *   Execution time in milliseconds
  * @param cost
  *   Estimated cost in dollars
  * @param itemsProcessed
  *   Number of items successfully processed
  * @param itemsFailed
  *   Number of items that failed
  */
case class StepMetrics(
  tokensUsed: Int = 0,
  latencyMs: Long = 0,
  cost: Double = 0.0,
  itemsProcessed: Int = 0,
  itemsFailed: Int = 0,
) derives JsonCodec

/** Real-time progress events emitted during workflow step execution.
  *
  * These events enable streaming progress updates for:
  *   - Server-Sent Events (SSE) or WebSocket for UI
  *   - CLI progress bars
  *   - Audit logging
  *   - Metrics aggregation
  *
  * Each step in a workflow can emit multiple events as it processes items.
  */
enum StepProgressEvent derives JsonCodec:
  /** Emitted when processing of an individual item begins.
    *
    * @param item
    *   The item being processed (e.g., file path, module name)
    * @param itemIndex
    *   Zero-based index of this item
    * @param totalItems
    *   Total number of items to process
    * @param stepName
    *   The workflow step name
    */
  case ItemStarted(
    item: String,
    itemIndex: Int,
    totalItems: Int,
    stepName: String,
  )

  /** Emitted during long-running item processing to show intermediate progress.
    *
    * @param item
    *   The item being processed
    * @param progress
    *   Progress percentage (0.0 to 1.0)
    * @param partialResult
    *   Optional partial results in JSON format
    * @param message
    *   Optional human-readable progress message
    */
  case ItemProgress(
    item: String,
    progress: Double,
    partialResult: Option[Json] = None,
    message: Option[String] = None,
  )

  /** Emitted when an item completes successfully.
    *
    * @param item
    *   The item that completed
    * @param result
    *   The result in JSON format
    * @param metrics
    *   Metrics captured during processing
    */
  case ItemCompleted(
    item: String,
    result: Json,
    metrics: StepMetrics,
  )

  /** Emitted when an item fails during processing.
    *
    * @param item
    *   The item that failed
    * @param error
    *   Human-readable error description
    * @param retryStrategy
    *   Optional description of retry strategy being applied
    * @param isFatal
    *   Whether this error should stop the entire step
    */
  case ItemFailed(
    item: String,
    error: String,
    retryStrategy: Option[String] = None,
    isFatal: Boolean = false,
  )

  /** Emitted at the end of a step with aggregate metrics.
    *
    * @param stepName
    *   The workflow step name
    * @param metrics
    *   Aggregate metrics for the entire step
    * @param summary
    *   Optional human-readable summary
    */
  case StepCompleted(
    stepName: String,
    metrics: StepMetrics,
    summary: Option[String] = None,
  )

object ProgressModels:
  // Helper constructors for common patterns
  def itemStarted(item: String, index: Int, total: Int, step: String): StepProgressEvent =
    StepProgressEvent.ItemStarted(item, index, total, step)

  def itemProgress(item: String, pct: Double, msg: String): StepProgressEvent =
    StepProgressEvent.ItemProgress(item, pct, None, Some(msg))

  def itemCompleted(item: String, result: Json, metrics: StepMetrics): StepProgressEvent =
    StepProgressEvent.ItemCompleted(item, result, metrics)

  def itemFailed(item: String, error: String, fatal: Boolean = false): StepProgressEvent =
    StepProgressEvent.ItemFailed(item, error, None, fatal)

  def stepCompleted(step: String, metrics: StepMetrics): StepProgressEvent =
    StepProgressEvent.StepCompleted(step, metrics, None)
