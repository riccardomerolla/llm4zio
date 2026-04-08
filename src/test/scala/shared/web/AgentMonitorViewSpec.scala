package shared.web

import java.time.Instant

import zio.test.*

import llm4zio.observability.MetricsSnapshot
import orchestration.entity.{ AgentExecutionInfo, AgentExecutionState, AgentMonitorSnapshot }
import shared.web.AgentMonitorView.{ AgentGlobalStats, AgentRunView }

object AgentMonitorViewSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-05T10:00:00Z")

  private val sampleInfo = AgentExecutionInfo(
    agentName = "claude-3-5-sonnet",
    state = AgentExecutionState.Executing,
    runId = Some("issue-abc-12345"),
    step = None,
    task = Some("Refactor auth"),
    conversationId = Some("sess-xyz-99"),
    tokensUsed = 12345L,
    latencyMs = 200L,
    cost = 0.001,
    lastUpdatedAt = now,
    startedAt = None,
    message = Some("Processing files"),
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
          issueId = "issue-1",
          stage = "EXEC",
          pid = None,
          ageSeconds = 30L,
          tokensTotal = 12345L,
          sessionId = None,
          lastEvent = "Working",
        )
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("12,345"))
      },
      test("EVENT text is truncated at 60 chars") {
        val longEvent = "A" * 65
        val row       = AgentRunView(
          issueId = "issue-1",
          stage = "EXEC",
          pid = None,
          ageSeconds = 0L,
          tokensTotal = 0L,
          sessionId = None,
          lastEvent = longEvent,
        )
        val html      = AgentMonitorView.table(List(row))
        assertTrue(
          !html.contains(longEvent),
          html.contains("A" * 59),
        )
      },
      test("EXEC stage renders emerald dot") {
        val row  = AgentRunView("id", "EXEC", None, 0L, 0L, None, "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("bg-emerald-400"))
      },
      test("FAIL stage renders rose dot") {
        val row  = AgentRunView("id", "FAIL", None, 0L, 0L, None, "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("bg-rose-500"))
      },
      test("TOOL stage renders amber dot") {
        val row  = AgentRunView("id", "TOOL", None, 0L, 0L, None, "")
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
          rows.head.reviewHref.contains("/checkpoints/issue-abc-12345"),
        )
      },
      test("table rows navigate to checkpoint review when runId is present") {
        val html = AgentMonitorView.table(AgentMonitorView.fromSnapshot(snapshot))
        assertTrue(
          html.contains("window.location='/checkpoints/issue-abc-12345'"),
          html.contains("cursor-pointer"),
        )
      },
      test("table with rows renders issue ID (truncated to 12 chars)") {
        val rows = AgentMonitorView.fromSnapshot(snapshot)
        val html = AgentMonitorView.table(rows)
        assertTrue(html.contains("issue-abc-12"))
      },
      test("AGE displays seconds for runs under 60s") {
        val row  = AgentRunView("id", "EXEC", None, 45L, 0L, None, "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("45s"))
      },
      test("AGE displays minutes for runs 60s+") {
        val row  = AgentRunView("id", "EXEC", None, 125L, 0L, None, "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("2m"))
      },
      test("SESSION truncated to 8 chars when present") {
        val row  = AgentRunView("id", "EXEC", None, 0L, 0L, Some("sess-xyz-99"), "")
        val html = AgentMonitorView.table(List(row))
        assertTrue(html.contains("sess-xyz"))
      },
      // -----------------------------------------------------------------------
      // statsHeader tests
      // -----------------------------------------------------------------------
      test("statsHeader renders data-agent-stats attribute") {
        val html = AgentMonitorView.statsHeader(AgentGlobalStats.empty)
        assertTrue(html.contains("data-agent-stats"))
      },
      test("statsHeader renders all 4 metrics") {
        val html = AgentMonitorView.statsHeader(AgentGlobalStats.empty)
        assertTrue(
          html.contains("Agents"),
          html.contains("Runtime"),
          html.contains("Tokens in"),
          html.contains("Tokens out"),
        )
      },
      test("statsHeader formats activeAgents/maxAgents as X / Y") {
        val stats = AgentGlobalStats(
          activeAgents = 3,
          maxAgents = 10,
          runtimeSeconds = 0L,
          tokensIn = 0L,
          tokensOut = 0L,
          tokensTotal = 0L,
        )
        val html  = AgentMonitorView.statsHeader(stats)
        assertTrue(html.contains("3 / 10"))
      },
      test("statsHeader formats tokensOut with comma separators") {
        val stats = AgentGlobalStats(
          activeAgents = 0,
          maxAgents = 0,
          runtimeSeconds = 0L,
          tokensIn = 0L,
          tokensOut = 1234567L,
          tokensTotal = 0L,
        )
        val html  = AgentMonitorView.statsHeader(stats)
        assertTrue(html.contains("1,234,567"))
      },
      test("statsHeader formats tokensIn with comma separators") {
        val stats = AgentGlobalStats(
          activeAgents = 0,
          maxAgents = 0,
          runtimeSeconds = 0L,
          tokensIn = 98765L,
          tokensOut = 0L,
          tokensTotal = 0L,
        )
        val html  = AgentMonitorView.statsHeader(stats)
        assertTrue(html.contains("98,765"))
      },
      test("statsHeader formats runtime as Xm Ys for values >= 60s") {
        val stats = AgentGlobalStats(
          activeAgents = 0,
          maxAgents = 0,
          runtimeSeconds = 114L,
          tokensIn = 0L,
          tokensOut = 0L,
          tokensTotal = 0L,
        )
        val html  = AgentMonitorView.statsHeader(stats)
        assertTrue(html.contains("1m 54s"))
      },
      test("statsHeader formats runtime as Xs for values under 60s") {
        val stats = AgentGlobalStats(
          activeAgents = 0,
          maxAgents = 0,
          runtimeSeconds = 42L,
          tokensIn = 0L,
          tokensOut = 0L,
          tokensTotal = 0L,
        )
        val html  = AgentMonitorView.statsHeader(stats)
        assertTrue(html.contains("42s"))
      },
      test("AgentGlobalStats.fromSnapshotAndMetrics counts active agents") {
        val llmMetrics = MetricsSnapshot(
          requests = 0,
          promptTokens = 0L,
          completionTokens = 12345L,
          totalLatencyMs = 0L,
          errors = 0L,
        )
        val stats      = AgentGlobalStats.fromSnapshotAndMetrics(snapshot, llmMetrics)
        assertTrue(
          stats.activeAgents == 1, // sampleInfo is Executing
          stats.tokensTotal == 12345L,
        )
      },
    )
