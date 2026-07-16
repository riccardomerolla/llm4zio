package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object GrokCliConnectorSpec extends ZIOSpecDefault:

  /** Captures the argv passed to runStreaming, then replays canned lines. */
  final class RecordingExec(seenArgv: Ref[List[String]], lines: List[String]) extends CliProcessExecutor:
    def run(a: List[String], c: String, e: Map[String, String]): IO[LlmError, ProcessResult]             =
      ZIO.succeed(ProcessResult(List("pong"), 0))
    def runStreaming(a: List[String], c: String, e: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.fromZIO(seenArgv.set(a)).drain ++ ZStream.fromIterable(lines)

  /** Replays canned JSONL lines for any streaming call. */
  final class MockCliExec(lines: List[String]) extends CliProcessExecutor:
    def run(a: List[String], c: String, e: Map[String, String]): IO[LlmError, ProcessResult]             =
      ZIO.succeed(ProcessResult(lines, 0))
    def runStreaming(a: List[String], c: String, e: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.fromIterable(lines)

  // Recorded from `grok -p ... --output-format streaming-json` (grok CLI, 2026-07): thought/text deltas, then a
  // terminal `end` event carrying usage and per-model breakdown. Tool executions emit no events of their own.
  private val streamLines = List(
    """{"type":"thought","data":"Thinking"}""",
    """{"type":"text","data":"Adding "}""",
    """{"type":"text","data":"multiply."}""",
    """{"type":"end","stopReason":"EndTurn","sessionId":"s","requestId":"r","usage":{"input_tokens":298,"cache_read_input_tokens":32256,"output_tokens":101,"reasoning_tokens":40,"total_tokens":32655},"num_turns":2,"modelUsage":{"grok-4.5":{"inputTokens":298,"outputTokens":101,"cacheReadInputTokens":32256,"modelCalls":2}}}""",
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("GrokCliConnector")(
    test("id is grok") {
      val connector = GrokCliConnector.make(CliConnectorConfig(ConnectorId.Grok), new MockCliExec(Nil))
      assertTrue(connector.id == ConnectorId.Grok)
    },
    test("kind is Cli") {
      val connector = GrokCliConnector.make(CliConnectorConfig(ConnectorId.Grok), new MockCliExec(Nil))
      assertTrue(connector.kind == ConnectorKind.Cli)
    },
    test("completeStream argv: headless streaming-json with the prompt as the -p value") {
      for
        seenArgv <- Ref.make(List.empty[String])
        exec      = new RecordingExec(seenArgv, streamLines)
        conn      = GrokCliConnector.make(CliConnectorConfig(ConnectorId.Grok), exec)
        _        <- conn.completeStream("THE-PROMPT").runDrain
        argv     <- seenArgv.get
      yield assertTrue(
        argv.head == "grok",
        argv.containsSlice(List("--output-format", "streaming-json")),
        argv.containsSlice(List("-p", "THE-PROMPT")),
        argv.contains("--always-approve"),
      )
    },
    test("extraArgs: model adds --model and readOnly maps to --permission-mode plan without --always-approve") {
      for
        seenArgv <- Ref.make(List.empty[String])
        exec      = new RecordingExec(seenArgv, streamLines)
        conn      = GrokCliConnector.make(
                      CliConnectorConfig(ConnectorId.Grok, model = Some("grok-4.5"), readOnly = true),
                      exec,
                    )
        _        <- conn.completeStream("go").runDrain
        argv     <- seenArgv.get
      yield assertTrue(
        argv.containsSlice(List("--model", "grok-4.5")),
        argv.containsSlice(List("--permission-mode", "plan")),
        !argv.contains("--always-approve"),
      )
    },
    test("completeStream emits text deltas and terminal usage with the served model") {
      val connector = GrokCliConnector.make(CliConnectorConfig(ConnectorId.Grok), new MockCliExec(streamLines))
      for chunks <- connector.completeStream("go").runCollect
      yield assertTrue(
        chunks.map(_.delta).mkString == "Adding multiply.",
        chunks.exists(c =>
          c.usage.exists(u => u.prompt == 298 && u.completion == 101 && u.total == 32655 && u.cached.contains(32256))
        ),
        chunks.exists(_.metadata.get("model").contains("grok-4.5")),
      )
    },
    test("parseStreamLine skips thought deltas") {
      val chunks = GrokCliConnector.parseStreamLine("""{"type":"thought","data":"pondering"}""")
      assertTrue(chunks.isEmpty)
    },
    test("parseStreamLine surfaces an error event in metadata") {
      val chunks = GrokCliConnector.parseStreamLine("""{"type":"error","data":"credits exhausted"}""")
      assertTrue(chunks.exists(_.metadata.get("grokError").contains("credits exhausted")))
    },
    test("an error event fails the stream as a ProviderError") {
      val lines     = List("""{"type":"error","data":"boom"}""")
      val connector = GrokCliConnector.make(CliConnectorConfig(ConnectorId.Grok), new MockCliExec(lines))
      for exit <- connector.completeStream("go").runDrain.exit
      yield assertTrue(exit.isFailure)
    },
    test("a quota-keyword error is classified as UsageLimitError so the wait layers engage") {
      val lines     = List("""{"type":"error","data":"429 Too Many Requests — rate limit exceeded"}""")
      val connector = GrokCliConnector.make(CliConnectorConfig(ConnectorId.Grok), new MockCliExec(lines))
      for exit <- connector.completeStream("go").runDrain.exit
      yield assertTrue(exit match
        case Exit.Failure(cause) => cause.failureOption.exists(_.isInstanceOf[LlmError.UsageLimitError])
        case _                   => false)
    },
  )
