package models

import zio.Scope
import zio.test.*

object WorkflowValidatorSpec extends ZIOSpecDefault:
  private val validWorkflow = WorkflowDefinition(
    id = None,
    name = "Pipeline A",
    description = Some("End-to-end flow"),
    steps = WorkflowDefinition.defaultSteps,
    isBuiltin = false,
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkflowValidatorSpec")(
    test("accepts a valid workflow") {
      val result = WorkflowValidator.validate(validWorkflow)
      assertTrue(result == Right(validWorkflow))
    },
    test("trims valid name before returning workflow") {
      val result = WorkflowValidator.validate(validWorkflow.copy(name = "  Pipeline A  "))
      assertTrue(result == Right(validWorkflow))
    },
    test("rejects empty name") {
      val result = WorkflowValidator.validate(validWorkflow.copy(name = "  "))
      assertTrue(
        result == Left(List("Workflow name cannot be empty"))
      )
    },
    test("rejects empty steps") {
      val result = WorkflowValidator.validate(validWorkflow.copy(steps = Nil))
      assertTrue(
        result == Left(List("Workflow steps cannot be empty"))
      )
    },
    test("rejects duplicate steps") {
      val steps  = List("TaskA", "TaskB", "TaskB")
      val result = WorkflowValidator.validate(validWorkflow.copy(steps = steps))
      assertTrue(
        result == Left(List("Duplicate step not allowed: TaskB"))
      )
    },
    test("accepts dynamic single-step workflows") {
      val steps  = List("AnalyzeIntent")
      val result = WorkflowValidator.validate(validWorkflow.copy(steps = steps))
      assertTrue(
        result == Right(validWorkflow.copy(steps = steps))
      )
    },
    test("accepts arbitrary step ordering for dynamic workflows") {
      val steps  = List("B", "A")
      val result = WorkflowValidator.validate(validWorkflow.copy(steps = steps))
      assertTrue(
        result == Right(validWorkflow.copy(steps = steps))
      )
    },
    test("returns multiple validation errors in one pass") {
      val steps  = List("Transform", "Map", "Map")
      val result = WorkflowValidator.validate(validWorkflow.copy(name = " ", steps = steps))
      assertTrue(
        result == Left(
          List(
            "Workflow name cannot be empty",
            "Duplicate step not allowed: Map",
          )
        )
      )
    },
    test("default workflow is valid") {
      val result = WorkflowValidator.validate(WorkflowDefinition.default)
      assertTrue(result == Right(WorkflowDefinition.default))
    },
  )
