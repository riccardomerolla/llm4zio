package orchestration

import zio.*
import zio.test.*

import models.*

/** Test suite for streaming workflow step execution with real-time progress events.
  */
object StreamingExecutionSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("StreamingExecutionSpec")(
    test("ProgressModels helper constructors create correct event types") {
      val started   = ProgressModels.itemStarted("file.cbl", 0, 10, "Analysis")
      val progress  = ProgressModels.itemProgress("file.cbl", 0.5, "Processing...")
      val completed = ProgressModels.itemCompleted(
        "file.cbl",
        zio.json.ast.Json.Obj(),
        StepMetrics(0, 100, 0.0, 1, 0),
      )
      val failed    = ProgressModels.itemFailed("file.cbl", "Test error", fatal = true)
      val stepDone  = ProgressModels.stepCompleted("Analysis", StepMetrics(0, 1000, 0.0, 10, 0))

      assertTrue(
        started.isInstanceOf[StepProgressEvent.ItemStarted],
        progress.isInstanceOf[StepProgressEvent.ItemProgress],
        completed.isInstanceOf[StepProgressEvent.ItemCompleted],
        failed.isInstanceOf[StepProgressEvent.ItemFailed],
        stepDone.isInstanceOf[StepProgressEvent.StepCompleted],
        failed.asInstanceOf[StepProgressEvent.ItemFailed].isFatal == true,
      )
    },
    test("StepProgressEvent.ItemStarted contains correct metadata") {
      val event = StepProgressEvent.ItemStarted("test.cbl", 5, 10, "Analysis")
      event match
        case StepProgressEvent.ItemStarted(item, itemIndex, totalItems, stepName) =>
          assertTrue(
            item == "test.cbl",
            itemIndex == 5,
            totalItems == 10,
            stepName == "Analysis",
          )
        case _                                                                    => assertTrue(false)
    },
    test("StepProgressEvent.ItemProgress tracks completion percentage") {
      val event = StepProgressEvent.ItemProgress("test.cbl", 0.75, None, Some("Almost done"))
      event match
        case StepProgressEvent.ItemProgress(item, progress, _, message) =>
          assertTrue(
            item == "test.cbl",
            progress == 0.75,
            message == Some("Almost done"),
          )
        case _                                                          => assertTrue(false)
    },
    test("StepProgressEvent.ItemCompleted includes metrics") {
      val metrics = StepMetrics(
        tokensUsed = 1000,
        latencyMs = 500,
        cost = 0.05,
        itemsProcessed = 1,
        itemsFailed = 0,
      )
      val event   = StepProgressEvent.ItemCompleted("test.cbl", zio.json.ast.Json.Null, metrics)

      event match
        case StepProgressEvent.ItemCompleted(item, _, m) =>
          assertTrue(
            item == "test.cbl",
            m.tokensUsed == 1000,
            m.latencyMs == 500,
            m.cost == 0.05,
          )
        case _                                           => assertTrue(false)
    },
    test("StepProgressEvent.ItemFailed captures error details") {
      val event = StepProgressEvent.ItemFailed("test.cbl", "File not found", Some("Retry in 5s"), isFatal = true)
      event match
        case StepProgressEvent.ItemFailed(item, error, retryStrategy, isFatal) =>
          assertTrue(
            item == "test.cbl",
            error == "File not found",
            retryStrategy == Some("Retry in 5s"),
            isFatal == true,
          )
        case _                                                                 => assertTrue(false)
    },
    test("StepProgressEvent.StepCompleted aggregates step metrics") {
      val metrics = StepMetrics(
        tokensUsed = 5000,
        latencyMs = 2000,
        cost = 0.25,
        itemsProcessed = 10,
        itemsFailed = 2,
      )
      val event   = StepProgressEvent.StepCompleted("Analysis", metrics, Some("Processed 10 files"))

      event match
        case StepProgressEvent.StepCompleted(stepName, m, summary) =>
          assertTrue(
            stepName == "Analysis",
            m.itemsProcessed == 10,
            m.itemsFailed == 2,
            summary == Some("Processed 10 files"),
          )
        case _                                                     => assertTrue(false)
    },
    test("StepMetrics can be serialized to JSON") {
      import zio.json.*

      val metrics = StepMetrics(
        tokensUsed = 100,
        latencyMs = 50,
        cost = 0.01,
        itemsProcessed = 5,
        itemsFailed = 1,
      )

      val json = metrics.toJson
      assertTrue(
        json.contains("\"tokensUsed\""),
        json.contains("\"latencyMs\""),
        json.contains("\"itemsProcessed\""),
        json.contains("\"itemsFailed\""),
      )
    },
    test("StepProgressEvent enum variants can be pattern matched") {
      val events: List[StepProgressEvent] = List(
        StepProgressEvent.ItemStarted("a.cbl", 0, 1, "Analysis"),
        StepProgressEvent.ItemProgress("a.cbl", 0.5, None, None),
        StepProgressEvent.ItemCompleted("a.cbl", zio.json.ast.Json.Null, StepMetrics(0, 0, 0.0, 1, 0)),
        StepProgressEvent.ItemFailed("b.cbl", "error", None, false),
        StepProgressEvent.StepCompleted("Analysis", StepMetrics(0, 0, 0.0, 1, 1), None),
      )

      val counts = events.foldLeft((0, 0, 0, 0, 0)) { (acc, event) =>
        event match
          case _: StepProgressEvent.ItemStarted   => (acc._1 + 1, acc._2, acc._3, acc._4, acc._5)
          case _: StepProgressEvent.ItemProgress  => (acc._1, acc._2 + 1, acc._3, acc._4, acc._5)
          case _: StepProgressEvent.ItemCompleted => (acc._1, acc._2, acc._3 + 1, acc._4, acc._5)
          case _: StepProgressEvent.ItemFailed    => (acc._1, acc._2, acc._3, acc._4 + 1, acc._5)
          case _: StepProgressEvent.StepCompleted => (acc._1, acc._2, acc._3, acc._4, acc._5 + 1)
      }

      assertTrue(
        counts == (1, 1, 1, 1, 1)
      )
    },
  )

end StreamingExecutionSpec
