package shared.web

import java.time.Instant

import zio.test.*

import orchestration.control.{ AgentExecutionInfo, AgentExecutionState, AgentMonitorSnapshot }
import shared.web.AgentMonitorView.AgentRunView

object AgentMonitorViewSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-05T10:00:00Z")

  private val sampleInfo = AgentExecutionInfo(
    agentName      = "claude-3-5-sonnet",
    state          = AgentExecutionState.Executing,
    runId          = Some("issue-abc-12345"),
    step           = None,
    task           = Some("Refactor auth"),
    conversationId = Some("sess-xyz-99"),
    tokensUsed     = 12345L,
    latencyMs      = 200L,
    cost           = 0.001,
    lastUpdatedAt  = now,
    message        = Some("Processing files"),
  )

  private val snapshot = AgentMonitorSnapshot(generatedAt = now, agents = List(sampleInfo))

  def spec: Spec[Any, Nothing] =
    suite("AgentMonitorView")(
      test("table renders <table> element with data-agent-table attribute") {
        val html = AgentMonitorView.table(Nil)
        assertTrue(html.contains("data-agent-table"))
      },
      test("table renders all 7 column headers") {
        val html = AgentMonitorView.table(Nil)
        assertTrue(
          html.contains("ID"),
          html.contains("STAGE"),
          html.contains("PID"),
          html.contains("AGE"),
          html.contains("TOKENS"),
          html.contains("SESSION"),
          html.contains("EVENT"),
        )
      },
      test("empty table shows no active agent runs message") {
        val html = AgentMonitorView.table(Nil)
        assertTrue(html.contains("No active agent runs"))
      },
      test("TOKEN values are comma-formatted") {
        val row  = AgentRunView(
          issueId     = "issue-1",
          stage       = "EXEC",
          pid         = None,
          ageSeconds  = 30L,
          turnCount   = 12345L,
          tokensTotal = 12345L,
          sessionId   = None,
          lastEvent   = "Working",
        )
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("12,345"))
      },
      test("EVENT text is truncated at 60 chars") {
        val longEvent = "A" * 65
        val row       = AgentRunView(
          issueId     = "issue-1",
          stage       = "EXEC",
          pid         = None,
          ageSeconds  = 0L,
          turnCount   = 0L,
          tokensTotal = 0L,
          sessionId   = None,
          lastEvent   = longEvent,
        )
        val html      = AgentMonitorView.table(List(row))
        assertTrue(
          !html.contains(longEvent),
          html.contains("A" * 59),
        )
      },
      test("EXEC stage renders emerald dot") {
        val row  = AgentRunView("id", "EXEC", None, 0L, 0L, 0L, None, "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("bg-emerald-400"))
      },
      test("FAIL stage renders rose dot") {
        val row  = AgentRunView("id", "FAIL", None, 0L, 0L, 0L, None, "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("bg-rose-500"))
      },
      test("TOOL stage renders amber dot") {
        val row  = AgentRunView("id", "TOOL", None, 0L, 0L, 0L, None, "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("bg-amber-400"))
      },
      test("fromSnapshot converts AgentExecutionInfo to AgentRunView") {
        val rows = AgentMonitorView.fromSnapshot(snapshot)
        assertTrue(
          rows.size == 1,
          rows.head.issueId == "issue-abc-12345",
          rows.head.stage == "EXEC",
          rows.head.tokensTotal == 12345L,
          rows.head.lastEvent == "Processing files",
          rows.head.sessionId.contains("sess-xyz-99"),
        )
      },
      test("table with rows renders issue ID (truncated to 12 chars)") {
        val rows = AgentMonitorView.fromSnapshot(snapshot)
        val html = AgentMonitorView.table(rows)
        assertTrue(html.contains("issue-abc-12"))
      },
      test("AGE displays seconds for runs under 60s") {
        val row  = AgentRunView("id", "EXEC", None, 45L, 0L, 0L, None, "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("45s"))
      },
      test("AGE displays minutes for runs 60s+") {
        val row  = AgentRunView("id", "EXEC", None, 125L, 0L, 0L, None, "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("2m"))
      },
      test("SESSION truncated to 8 chars when present") {
        val row  = AgentRunView("id", "EXEC", None, 0L, 0L, 0L, Some("sess-xyz-99"), "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("sess-xyz"))
      },
    )
