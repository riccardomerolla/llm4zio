package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object PiConnectorSpec extends ZIOSpecDefault:

  /** Captures the argv and stdin passed to runStreamingWithStdin, then replays canned lines. */
  final class RecordingExec(seenArgv: Ref[List[String]], seenStdin: Ref[String], lines: List[String])
    extends CliProcessExecutor:
    def run(a: List[String], c: String, e: Map[String, String]): IO[LlmError, ProcessResult]             =
      ZIO.succeed(ProcessResult(List("{}"), 0))
    def runStreaming(a: List[String], c: String, e: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.fromZIO(seenArgv.set(a)).drain ++ ZStream.fromIterable(lines)
    override def runStreamingWithStdin(
      a: List[String],
      c: String,
      e: Map[String, String],
      stdin: String,
    ): ZStream[Any, LlmError, String] =
      ZStream.fromZIO(seenArgv.set(a) *> seenStdin.set(stdin)).drain ++ ZStream.fromIterable(lines)

  /** Replays canned JSONL lines for any streaming call. */
  final class MockCliExec(lines: List[String]) extends CliProcessExecutor:
    def run(a: List[String], c: String, e: Map[String, String]): IO[LlmError, ProcessResult]             =
      ZIO.succeed(ProcessResult(lines, 0))
    def runStreaming(a: List[String], c: String, e: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.fromIterable(lines)
    override def runStreamingWithStdin(
      a: List[String],
      c: String,
      e: Map[String, String],
      stdin: String,
    ): ZStream[Any, LlmError, String] =
      ZStream.fromIterable(lines)

  private val streamLines = List(
    """{"type":"turn_start"}""",
    """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Adding multiply."}}""",
    """{"type":"tool_execution_start","toolName":"bash","args":{"command":"cargo build"}}""",
    """{"type":"message_end","message":{"usage":{"input":900,"output":100}}}""",
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("PiConnector")(
    test("id is pi") {
      val connector = PiConnector.make(CliConnectorConfig(ConnectorId.Pi), new MockCliExec(Nil))
      assertTrue(connector.id == ConnectorId.Pi)
    },
    test("kind is Cli") {
      val connector = PiConnector.make(CliConnectorConfig(ConnectorId.Pi), new MockCliExec(Nil))
      assertTrue(connector.kind == ConnectorKind.Cli)
    },
    test("interactionSupport is InteractiveStdin") {
      val connector = PiConnector.make(CliConnectorConfig(ConnectorId.Pi), new MockCliExec(Nil))
      assertTrue(connector.interactionSupport == InteractionSupport.InteractiveStdin)
    },
    test("completeStream emits assistant text, a tool chunk, and usage from the JSONL stream") {
      val connector = PiConnector.make(CliConnectorConfig(ConnectorId.Pi), new MockCliExec(streamLines))
      for chunks <- connector.completeStream("go").runCollect
      yield assertTrue(
        chunks.map(_.delta).mkString.contains("Adding multiply."),
        chunks.exists(c =>
          c.metadata.get("event").contains("tool_use") &&
          c.metadata.get("tool_name").contains("bash") &&
          c.metadata.get("tool_input").exists(_.contains("cargo build"))
        ),
        chunks.exists(_.usage.exists(_.prompt == 900)),
        chunks.exists(_.usage.exists(_.completion == 100)),
      )
    },
    test("completeStream passes prompt via stdin, NOT in argv") {
      for
        seenArgv  <- Ref.make(List.empty[String])
        seenStdin <- Ref.make("")
        exec       = new RecordingExec(seenArgv, seenStdin, streamLines)
        conn       = PiConnector.make(CliConnectorConfig(ConnectorId.Pi), exec)
        _         <- conn.completeStream("THE-PROMPT").runDrain
        argv      <- seenArgv.get
        stdin     <- seenStdin.get
      yield assertTrue(
        !argv.contains("THE-PROMPT"),
        stdin == "THE-PROMPT",
        argv.containsSlice(List("pi", "-p", "--mode", "json")),
      )
    },
    test("extraArgs: readOnly adds --tools and model adds --model") {
      for
        seenArgv  <- Ref.make(List.empty[String])
        seenStdin <- Ref.make("")
        exec       = new RecordingExec(seenArgv, seenStdin, Nil)
        conn       = PiConnector.make(
                       CliConnectorConfig(ConnectorId.Pi, model = Some("lmstudio/foo"), readOnly = true),
                       exec,
                     )
        _         <- conn.completeStream("go").runDrain
        argv      <- seenArgv.get
      yield assertTrue(
        argv.containsSlice(List("pi", "-p", "--mode", "json")),
        argv.containsSlice(List("--model", "lmstudio/foo")),
        argv.containsSlice(List("--tools", "read,grep,find,ls")),
      )
    },
    test("a pi error event surfaces in metadata") {
      val errLine = """{"type":"auto_retry_end","success":false,"finalError":"rate limited"}"""
      val chunks  = PiConnector.parseStreamLine(errLine)
      assertTrue(chunks.exists(_.metadata.get("piError").contains("rate limited")))
    },
    test("parseStreamLine maps text_delta to a text chunk") {
      val chunks = streamLines.flatMap(PiConnector.parseStreamLine)
      assertTrue(chunks.exists(_.delta == "Adding multiply."))
    },
    test("parseStreamLine maps tool_execution_start to a tool chunk") {
      val chunks = streamLines.flatMap(PiConnector.parseStreamLine)
      assertTrue(chunks.exists(c =>
        c.metadata.get("event").contains("tool_use") &&
        c.metadata.get("tool_name").contains("bash") &&
        c.metadata.get("tool_input").exists(_.contains("cargo build"))
      ))
    },
    test("parseStreamLine maps message_end usage to a usage chunk") {
      val chunks = streamLines.flatMap(PiConnector.parseStreamLine)
      assertTrue(chunks.exists(c => c.usage.exists(_.prompt == 900) && c.usage.exists(_.completion == 100)))
    },
    test("an extension_error event surfaces in metadata") {
      val errLine = """{"type":"extension_error","error":"boom"}"""
      val chunks  = PiConnector.parseStreamLine(errLine)
      assertTrue(chunks.exists(_.metadata.get("piError").contains("boom")))
    },
  )
