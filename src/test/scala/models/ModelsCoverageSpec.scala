package models

import java.time.Instant

import zio.Scope
import zio.test.*

object ModelsCoverageSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ModelsCoverageSpec")(
    suite("AgentMetrics")(
      test("averageLatencyMs and successRate handle empty metrics") {
        val metrics = AgentMetrics()
        assertTrue(metrics.averageLatencyMs == 0.0, metrics.successRate == 0.0)
      },
      test("recordInvocation updates counters and averages") {
        val now     = Instant.EPOCH
        val metrics = AgentMetrics().recordInvocation(success = true, latencyMs = 200, timestamp = now)
        assertTrue(
          metrics.invocations == 1,
          metrics.successCount == 1,
          metrics.failureCount == 0,
          metrics.averageLatencyMs == 200.0,
          metrics.successRate == 1.0,
          metrics.lastInvocation.contains(now),
        )
      },
    ),
    suite("AgentHealth")(
      test("recordFailure transitions status based on thresholds") {
        val now       = Instant.EPOCH
        val healthy   = AgentHealth().recordFailure(now, "one")
        val degraded  = AgentHealth(consecutiveFailures = 2).recordFailure(now, "three")
        val unhealthy = AgentHealth(consecutiveFailures = 4).recordFailure(now, "five")

        assertTrue(
          healthy.status == AgentHealthStatus.Healthy,
          degraded.status == AgentHealthStatus.Degraded,
          unhealthy.status == AgentHealthStatus.Unhealthy,
        )
      },
      test("recordSuccess resets failures and status") {
        val now    = Instant.EPOCH
        val health = AgentHealth(consecutiveFailures = 3, status = AgentHealthStatus.Degraded).recordSuccess(now)
        assertTrue(
          health.status == AgentHealthStatus.Healthy,
          health.consecutiveFailures == 0,
          health.lastCheckTime.contains(now),
          health.message.isEmpty,
        )
      },
      test("disable and enable toggle flags") {
        val disabled = AgentHealth().disable("maintenance")
        val enabled  = disabled.enable()
        assertTrue(
          !disabled.isEnabled,
          disabled.message.contains("maintenance"),
          enabled.isEnabled,
          enabled.message.isEmpty,
        )
      },
    ),
    suite("ControlPlaneModels")(
      test("workflow and step events capture fields") {
        val now       = Instant.EPOCH
        val started   = WorkflowStarted("corr", "run-1", 42L, now)
        val progress  = StepProgress("corr", "run-1", TaskStep.Analysis, 3, 10, "processing", now)
        val completed = StepCompleted("corr", "run-1", TaskStep.Analysis, WorkflowStatus.Completed, now)
        val failed    = StepFailed("corr", "run-1", TaskStep.Analysis, "boom", now)
        val allocated = ResourceAllocated("corr", "run-1", 2, now)
        val released  = ResourceReleased("corr", "run-1", 2, now)

        assertTrue(
          started.workflowId == 42L,
          progress.itemsProcessed == 3,
          completed.status == WorkflowStatus.Completed,
          failed.error == "boom",
          allocated.parallelismSlot == 2,
          released.parallelismSlot == 2,
        )
      },
      test("agent capability defaults to enabled") {
        val cap = AgentCapability("agent-x", List(TaskStep.Discovery))
        assertTrue(cap.isEnabled)
      },
    ),
    suite("ChatModels")(
      test("agent issue and assignment keep fields") {
        val now        = Instant.EPOCH
        val issue      = AgentIssue(
          title = "Broken parse",
          description = "Parser fails",
          issueType = "parse",
          createdAt = now,
          updatedAt = now,
        )
        val assignment = AgentAssignment(
          issueId = 42L,
          agentName = "agent-x",
          assignedAt = now,
        )

        assertTrue(
          issue.title == "Broken parse",
          issue.issueType == "parse",
          assignment.issueId == 42L,
          assignment.status == "pending",
        )
      },
      test("request DTOs set defaults") {
        val messageReq = ConversationMessageRequest("hi")
        val convoReq   = ChatConversationCreateRequest("title")
        val issueReq   = AgentIssueCreateRequest(title = "title", description = "desc", issueType = "type")

        assertTrue(
          messageReq.messageType == MessageType.Text,
          convoReq.description.isEmpty,
          issueReq.priority == IssuePriority.Medium,
        )
      },
    ),
    suite("Errors")(
      test("workspace errors include runId") {
        val created = WorkspaceError.CreationFailed("run-1", "disk")
        val cleaned = WorkspaceError.CleanupFailed("run-2", "locked")
        val invalid = WorkspaceError.InvalidConfiguration("run-3", "missing")

        assertTrue(
          created.message.contains("run-1"),
          cleaned.message.contains("run-2"),
          invalid.message.contains("run-3"),
        )
      },
      test("control plane error messages are descriptive") {
        val mismatch   = ControlPlaneError.AgentCapabilityMismatch("Analysis", "agent-x")
        val transition = ControlPlaneError.InvalidWorkflowTransition("run-9", "Discovery", "Validation")

        assertTrue(
          mismatch.message.contains("Analysis"),
          transition.message.contains("run-9"),
        )
      },
    ),
    suite("ProgressModels")(
      test("helper constructors create expected events") {
        val metrics   = StepMetrics(tokensUsed = 5, latencyMs = 100, cost = 0.2, itemsProcessed = 1, itemsFailed = 0)
        val started   = ProgressModels.itemStarted("file.cbl", 0, 1, "Analysis")
        val progress  = ProgressModels.itemProgress("file.cbl", 0.5, "half")
        val completed = ProgressModels.itemCompleted("file.cbl", zio.json.ast.Json.Obj(), metrics)
        val failed    = ProgressModels.itemFailed("file.cbl", "oops", fatal = true)
        val stepDone  = ProgressModels.stepCompleted("Analysis", metrics)

        assertTrue(
          started.isInstanceOf[StepProgressEvent.ItemStarted],
          progress.isInstanceOf[StepProgressEvent.ItemProgress],
          completed.isInstanceOf[StepProgressEvent.ItemCompleted],
          failed.isInstanceOf[StepProgressEvent.ItemFailed],
          stepDone.isInstanceOf[StepProgressEvent.StepCompleted],
        )
      }
    ),
  )
