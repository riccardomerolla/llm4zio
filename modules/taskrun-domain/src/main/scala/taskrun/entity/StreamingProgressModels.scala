package taskrun.entity

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

case class StepMetrics(
  tokensUsed: Int = 0,
  latencyMs: Long = 0,
  cost: Double = 0.0,
  itemsProcessed: Int = 0,
  itemsFailed: Int = 0,
) derives JsonCodec

enum StepProgressEvent derives JsonCodec:
  case ItemStarted(
    item: String,
    itemIndex: Int,
    totalItems: Int,
    stepName: String,
  )
  case ItemProgress(
    item: String,
    progress: Double,
    partialResult: Option[Json] = None,
    message: Option[String] = None,
  )
  case ItemCompleted(
    item: String,
    result: Json,
    metrics: StepMetrics,
  )
  case ItemFailed(
    item: String,
    error: String,
    retryStrategy: Option[String] = None,
    isFatal: Boolean = false,
  )
  case StepCompleted(
    stepName: String,
    metrics: StepMetrics,
    summary: Option[String] = None,
  )

object ProgressModels:
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
