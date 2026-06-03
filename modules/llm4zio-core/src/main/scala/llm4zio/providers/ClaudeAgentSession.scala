package llm4zio.providers

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream

import llm4zio.core.*

/** A live, steerable claude session over a held `claude … --input-format stream-json --output-format stream-json`
  * process. Parses the stdout event stream into [[SessionEvent]]s and frames user turns as stream-json on stdin.
  */
object ClaudeAgentSession:

  /** Argv for a held bidirectional claude session. `extraFlags` carries interactive extras (e.g. `--mcp-config`,
    * `--permission-prompt-tool`, `--allowedTools`) added by later tasks.
    */
  def sessionArgv(model: Option[String], extraFlags: List[String]): List[String] =
    List("claude", "--print", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose") ++
      model.toList.flatMap(m => List("--model", m)) ++ extraFlags

  /** The stdin framing claude expects for a user turn (verified by the Phase-2 spike). */
  def userMessageJson(text: String): String =
    Json.Obj(
      "type"    -> Json.Str("user"),
      "message" -> Json.Obj("role" -> Json.Str("user"), "content" -> Json.Str(text)),
    ).toString

  /** Parse one claude stream-json line into zero or more [[SessionEvent]]s. Stateless. */
  def parseSessionLine(line: String): List[SessionEvent] =
    CliStreamJson.parseLine(line) match
      case None       => Nil
      case Some(json) =>
        CliStreamJson.str(json, "type") match
          case Some("assistant") => assistantEvents(json)
          case Some("user")      => toolResultEvents(json)
          case Some("result")    => resultEvents(json)
          case _                 => Nil

  /** Model name from the `system`/`init` line (threaded onto the result's Usage in a live session). */
  def initModel(line: String): Option[String] =
    CliStreamJson.parseLine(line).flatMap { json =>
      if CliStreamJson.str(json, "type").contains("system") then CliStreamJson.str(json, "model") else None
    }

  private def contentBlocks(json: Json): List[Json] =
    CliStreamJson.field(json, "message").flatMap(CliStreamJson.field(_, "content")) match
      case Some(Json.Arr(blocks)) => blocks.toList
      case _                      => Nil

  private def assistantEvents(json: Json): List[SessionEvent] =
    contentBlocks(json).flatMap { block =>
      CliStreamJson.str(block, "type") match
        case Some("text")     =>
          CliStreamJson.str(block, "text").filter(_.nonEmpty).map(SessionEvent.TextDelta(_)).toList
        case Some("tool_use") =>
          val name = CliStreamJson.str(block, "name").getOrElse("")
          val raw  = CliStreamJson.field(block, "input").map(_.toString).getOrElse("{}")
          val id   = CliStreamJson.str(block, "id").getOrElse("")
          List(SessionEvent.ToolUse(name, raw, id))
        case _                => Nil
    }

  private def toolResultEvents(json: Json): List[SessionEvent] =
    contentBlocks(json).flatMap { block =>
      CliStreamJson.str(block, "type") match
        case Some("tool_result") =>
          val id      = CliStreamJson.str(block, "tool_use_id").getOrElse("")
          val status  = if CliStreamJson.field(block, "is_error").contains(Json.Bool(true)) then "error" else "success"
          val content = CliStreamJson.field(block, "content").map(_.toString).getOrElse("")
          List(SessionEvent.ToolResult(id, status, content))
        case _                   => Nil
    }

  private def resultEvents(json: Json): List[SessionEvent] =
    val usageEvent = CliStreamJson.field(json, "usage").map { usage =>
      val in     = CliStreamJson.int(usage, "input_tokens").getOrElse(0)
      val out    = CliStreamJson.int(usage, "output_tokens").getOrElse(0)
      val cached = CliStreamJson.int(usage, "cache_read_input_tokens")
      SessionEvent.Usage(TokenUsage(in, out, in + out, cached), None)
    }
    val doneEvent  = SessionEvent.Done(CliStreamJson.str(json, "result").getOrElse(""))
    usageEvent.toList :+ doneEvent

  /** Open a live claude session over `executor.runBidirectional`. An internal fiber drains stdout, publishes events to
    * a hub, accumulates the final result, and threads the init model onto the Usage event. Scoped — closing the scope
    * kills the process.
    */
  def open(
    executor: CliProcessExecutor,
    argv: List[String],
    cwd: String,
    envVars: Map[String, String] = Map.empty,
  ): ZIO[Scope, LlmError, AgentSession] =
    for
      bundle      <- executor.runBidirectional(argv, cwd, envVars)
      (queue, out) = bundle
      hub         <- Hub.unbounded[SessionEvent]
      done        <- Promise.make[LlmError, SessionResult]
      modelRef    <- Ref.make(Option.empty[String])
      textRef     <- Ref.make(new StringBuilder())
      usageRef    <- Ref.make(Option.empty[TokenUsage])
      _           <- out
                       .mapZIO { line =>
                         ClaudeAgentSession.initModel(line) match
                           case Some(m) => modelRef.set(Some(m)).as(line)
                           case None    => ZIO.succeed(line)
                       }
                       .mapConcat(parseSessionLine)
                       .mapZIO { event =>
                         val track = event match
                           case SessionEvent.TextDelta(t) => textRef.update(_.append(t))
                           case SessionEvent.Usage(u, _)  => usageRef.set(Some(u))
                           case SessionEvent.Done(result) =>
                             for
                               text  <- textRef.get.map(_.toString)
                               usage <- usageRef.get
                               model <- modelRef.get
                               r      = if result.nonEmpty then result else text
                               _     <- done.succeed(SessionResult(r, usage, model))
                             yield ()
                           case _                         => ZIO.unit
                         track *> hub.publish(event)
                       }
                       .runDrain
                       .catchAll(e => done.fail(e).unit)
                       .ensuring(done.fail(LlmError.ProviderError("claude session ended before a result", None)).unit)
                       .forkScoped
    yield new AgentSession:
      def events: ZStream[Any, LlmError, SessionEvent]                         = ZStream.fromHub(hub)
      def sendUserMessage(text: String): IO[LlmError, Unit]                    = queue.offer(userMessageJson(text)).unit
      def respondToAsk(answer: String): IO[LlmError, Unit]                     = sendUserMessage(answer)
      def respondToApproval(id: String, approved: Boolean): IO[LlmError, Unit] = ZIO.unit
      def awaitResult: IO[LlmError, SessionResult]                             = done.await
      def cancel: UIO[Unit]                                                    = queue.shutdown
