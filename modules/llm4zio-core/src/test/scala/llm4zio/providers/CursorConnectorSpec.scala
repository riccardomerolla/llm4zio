package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object CursorConnectorSpec extends ZIOSpecDefault:

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

  // Shape per cursor.com/docs/cli/reference/output-format (stream-json, NDJSON): system init, complete assistant
  // segments, correlated tool_call started/completed, terminal result. No token usage in the schema.
  private val streamLines = List(
    """{"type":"system","subtype":"init","apiKeySource":"env","cwd":"/w","session_id":"u","model":"Composer","permissionMode":"default"}""",
    """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Adding multiply."}]},"session_id":"u"}""",
    """{"type":"tool_call","subtype":"started","call_id":"c1","tool_call":{"shellToolCall":{"args":{"command":"cargo build"}}},"session_id":"u"}""",
    """{"type":"result","subtype":"success","duration_ms":10,"duration_api_ms":8,"is_error":false,"result":"Adding multiply.","session_id":"u"}""",
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("CursorConnector")(
    test("id is cursor") {
      val connector = CursorConnector.make(CliConnectorConfig(ConnectorId.Cursor), new MockCliExec(Nil))
      assertTrue(connector.id == ConnectorId.Cursor)
    },
    test("kind is Cli") {
      val connector = CursorConnector.make(CliConnectorConfig(ConnectorId.Cursor), new MockCliExec(Nil))
      assertTrue(connector.kind == ConnectorKind.Cli)
    },
    test("completeStream argv: cursor-agent print mode with stream-json and --force (edits apply)") {
      for
        seenArgv <- Ref.make(List.empty[String])
        exec      = new RecordingExec(seenArgv, streamLines)
        conn      = CursorConnector.make(CliConnectorConfig(ConnectorId.Cursor), exec)
        _        <- conn.completeStream("THE-PROMPT").runDrain
        argv     <- seenArgv.get
      yield assertTrue(
        argv.head == "cursor-agent",
        argv.containsSlice(List("--output-format", "stream-json")),
        argv.containsSlice(List("-p", "THE-PROMPT")),
        argv.contains("--force"),
      )
    },
    test("readOnly drops --force (print mode only proposes changes) and model adds --model") {
      for
        seenArgv <- Ref.make(List.empty[String])
        exec      = new RecordingExec(seenArgv, streamLines)
        conn      = CursorConnector.make(
                      CliConnectorConfig(ConnectorId.Cursor, model = Some("composer-1"), readOnly = true),
                      exec,
                    )
        _        <- conn.completeStream("go").runDrain
        argv     <- seenArgv.get
      yield assertTrue(
        !argv.contains("--force"),
        argv.containsSlice(List("--model", "composer-1")),
      )
    },
    test("completeStream emits assistant text, a tool chunk, and a terminal stop") {
      val connector = CursorConnector.make(CliConnectorConfig(ConnectorId.Cursor), new MockCliExec(streamLines))
      for chunks <- connector.completeStream("go").runCollect
      yield assertTrue(
        chunks.map(_.delta).mkString == "Adding multiply.",
        chunks.exists(c =>
          c.metadata.get("event").contains("tool_use") &&
          c.metadata.get("tool_name").contains("shellToolCall") &&
          c.metadata.get("tool_input").exists(_.contains("cargo build"))
        ),
        chunks.exists(_.finishReason.contains("stop")),
      )
    },
    test("parseStreamLine skips system init events") {
      assertTrue(CursorConnector.parseStreamLine(streamLines.head).isEmpty)
    },
    test("parseStreamLine surfaces an error result in metadata") {
      val errLine =
        """{"type":"result","subtype":"error","duration_ms":10,"duration_api_ms":8,"is_error":true,"result":"quota exceeded","session_id":"u"}"""
      val chunks  = CursorConnector.parseStreamLine(errLine)
      assertTrue(chunks.exists(_.metadata.get("cursorError").contains("quota exceeded")))
    },
    test("an error result fails the stream as a ProviderError") {
      val lines     = List(
        """{"type":"result","subtype":"error","duration_ms":1,"duration_api_ms":1,"is_error":true,"result":"boom","session_id":"u"}"""
      )
      val connector = CursorConnector.make(CliConnectorConfig(ConnectorId.Cursor), new MockCliExec(lines))
      for exit <- connector.completeStream("go").runDrain.exit
      yield assertTrue(exit.isFailure)
    },
    test("a quota-keyword error result is classified as UsageLimitError so the wait layers engage") {
      val lines     = List(
        """{"type":"result","subtype":"error","duration_ms":1,"duration_api_ms":1,"is_error":true,"result":"You have hit your usage limit.","session_id":"u"}"""
      )
      val connector = CursorConnector.make(CliConnectorConfig(ConnectorId.Cursor), new MockCliExec(lines))
      for exit <- connector.completeStream("go").runDrain.exit
      yield assertTrue(exit match
        case Exit.Failure(cause) => cause.failureOption.exists(_.isInstanceOf[LlmError.UsageLimitError])
        case _                   => false)
    },
  )
