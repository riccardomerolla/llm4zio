package gateway.telegram

import java.time.Instant

import zio.test.*

import gateway.models.*

object ResponseFormatterSpec extends ZIOSpecDefault:

  private def message(content: String, metadata: Map[String, String] = Map.empty): NormalizedMessage =
    NormalizedMessage(
      id = "msg-1",
      channelName = "telegram",
      sessionKey = SessionKey("telegram", "conversation:1"),
      direction = MessageDirection.Outbound,
      role = MessageRole.Assistant,
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
    test("formats csv-like structured content as markdown table") {
      val formatted = ResponseFormatter.format(message("name,age\nAlice,30\nBob,35", Map("content.type" -> "table")))
      assertTrue(
        formatted.text.contains("| name | age |"),
        formatted.text.contains("| Alice | 30 |"),
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
    test("truncates long responses and provides show more callback") {
      val formatted = ResponseFormatter.format(message("x" * 5000))
      assertTrue(
        formatted.replyMarkup.nonEmpty,
        formatted.continuationToken.nonEmpty,
        formatted.remaining.exists(_.nonEmpty),
        formatted.text.contains("truncated"),
      )
    },
  )
