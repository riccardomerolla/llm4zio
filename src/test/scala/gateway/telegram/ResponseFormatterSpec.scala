package gateway.telegram

import java.time.Instant

import zio.test.*

import gateway.models.*
import models.WorkflowRunState

object ResponseFormatterSpec extends ZIOSpecDefault:

  private def message(content: String, metadata: Map[String, String] = Map.empty): NormalizedMessage =
    NormalizedMessage(
      id = "msg-1",
      channelName = "telegram",
      sessionKey = SessionKey("telegram", "conversation:1"),
      direction = MessageDirection.Outbound,
      role = GatewayMessageRole.Assistant,
      content = content,
      metadata = metadata,
      timestamp = Instant.EPOCH,
    )

  def spec: Spec[TestEnvironment, Any] = suite("ResponseFormatterSpec")(
    test("wraps code-like content in markdown fences") {
      val formatted = ResponseFormatter.format(message("public class A {\n  return 1;\n}"))
      assertTrue(
        formatted.text.contains("```") || formatted.parseMode.contains("Markdown")
      )
    },
    test("formats csv-like structured content as fenced monospace table") {
      val formatted = ResponseFormatter.format(message("name,age\nAlice,30\nBob,35", Map("content.type" -> "table")))
      assertTrue(
        formatted.text.startsWith("```"),
        formatted.text.contains("name"),
        formatted.text.contains("Alice"),
        formatted.parseMode.contains("Markdown"),
      )
    },
    test("formats markdown table content as fenced monospace table") {
      val formatted = ResponseFormatter.format(
        message("| name | age |\n| --- | --- |\n| Alice | 30 |\n| Bob | 35 |")
      )
      assertTrue(
        formatted.text.startsWith("```"),
        formatted.text.contains("name"),
        formatted.text.contains("Bob"),
        formatted.parseMode.contains("Markdown"),
      )
    },
    test("appends attachment section for pdf/zip metadata") {
      val formatted = ResponseFormatter.format(
        message(
          "Done",
          Map(
            "reportPdf" -> "/tmp/report.pdf",
            "bundleZip" -> "/tmp/output.zip",
          ),
        )
      )
      assertTrue(
        formatted.text.contains("Generated attachments"),
        formatted.text.contains("report.pdf"),
        formatted.text.contains("output.zip"),
      )
    },
    test("keeps long responses intact (no custom truncation)") {
      val formatted = ResponseFormatter.format(message("x" * 5000))
      assertTrue(
        formatted.replyMarkup.isEmpty,
        formatted.continuationToken.isEmpty,
        formatted.remaining.isEmpty,
        formatted.text.length == 5000,
      )
    },
    test("formats task progress states with expected prefixes") {
      val running   = ResponseFormatter.formatTaskProgress(WorkflowRunState.Running, "Generate report", Some("chat"))
      val completed = ResponseFormatter.formatTaskProgress(WorkflowRunState.Completed, "Generate report", None)
      val failed    = ResponseFormatter.formatTaskProgress(WorkflowRunState.Failed, "Generate report", Some("chat"))
      assertTrue(
        running.startsWith("▶"),
        running.contains("Step: chat"),
        completed.startsWith("✓"),
        failed.startsWith("✗"),
        failed.contains("step: chat"),
      )
    },
  )
