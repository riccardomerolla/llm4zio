package llm4zio.providers

import java.time.ZoneId

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream

import llm4zio.core.*

object CursorConnector:
  def make(config: CliConnectorConfig, executor: CliProcessExecutor): CliConnector =
    new CliConnector:
      private val cwd: String = config.workingDir.getOrElse(".")

      // `--model X` when set; edits only apply in print mode with `--force`, so readOnly maps to *omitting* it
      // (cursor then proposes changes without touching files); then passthrough flags from config.flags
      // (empty value → bare `--flag`), sorted for determinism.
      private def extraArgs: List[String] =
        val modelArgs = config.model.toList.flatMap(m => List("--model", m))
        val forceArg  = if config.readOnly then Nil else List("--force")
        val flagArgs  = config.flags.toList.sortBy(_._1).flatMap {
          case (k, v) if v.isEmpty => List(s"--$k")
          case (k, v)              => List(s"--$k", v)
        }
        modelArgs ++ forceArg ++ flagArgs

      override def id: ConnectorId                        = ConnectorId.Cursor
      override def interactionSupport: InteractionSupport = InteractionSupport.ContinuationOnly

      override def healthCheck: IO[LlmError, HealthStatus] =
        executor.run(List("cursor-agent", "--version"), cwd, Map.empty)
          .map(_ => HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
          .catchAll(_ => ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)))

      override def isAvailable: UIO[Boolean] =
        healthCheck.map(_.availability == Availability.Healthy).catchAll(_ => ZIO.succeed(false))

      override def buildArgv(prompt: String, ctx: CliContext): List[String] =
        List("cursor-agent") ++ extraArgs ++ List("-p", prompt)
      override def buildInteractiveArgv(ctx: CliContext): List[String]      =
        List("cursor-agent") ++ extraArgs

      override def complete(prompt: String): IO[LlmError, String] =
        executor.run(buildArgv(prompt, CliContext(cwd, "")), cwd, config.envVars)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else
              Clock.instant.flatMap { now =>
                val raw = result.stdout.mkString("\n")
                ZIO.fail(UsageLimits.classify("cursor", raw, now, ZoneId.systemDefault)
                  .getOrElse(LlmError.ProviderError(s"cursor-agent exited with code ${result.exitCode}: $raw", None)))
              }
          }

      // Stream via `cursor-agent -p --output-format stream-json`, parsing the NDJSON event stream into the
      // shared LlmChunk metadata contract.
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val argv = List("cursor-agent", "--output-format", "stream-json") ++ extraArgs ++ List("-p", prompt)
        executor.runStreaming(argv, cwd, config.envVars)
          .mapConcat(CursorConnector.parseStreamLine)
          .mapZIO { chunk =>
            chunk.metadata.get("cursorError") match
              case Some(msg) =>
                Clock.instant.flatMap { now =>
                  ZIO.fail(UsageLimits.classify("cursor", msg, now, ZoneId.systemDefault)
                    .getOrElse(LlmError.ProviderError(s"cursor error: $msg", None)))
                }
              case None      => ZIO.succeed(chunk)
          }

  /** Parse one cursor-agent `--output-format stream-json` line into zero or more chunks. Stateless. Shape per
    * cursor.com/docs/cli/reference/output-format: `system` init, complete `assistant` message segments, correlated
    * `tool_call` started/completed, terminal `result`. The schema reports no token usage, so cursor seats stay unpriced
    * rather than estimated.
    */
  def parseStreamLine(line: String): List[LlmChunk] =
    CliStreamJson.parseLine(line) match
      case None       => Nil
      case Some(json) =>
        CliStreamJson.str(json, "type") match
          case Some("assistant")                                                           =>
            CliStreamJson.field(json, "message").toList.flatMap(messageText).map(t => LlmChunk(delta = t))
          case Some("tool_call") if CliStreamJson.str(json, "subtype").contains("started") =>
            CliStreamJson.field(json, "tool_call").toList.flatMap {
              case Json.Obj(fields) =>
                fields.headOption.map { (name, body) =>
                  val args = CliStreamJson.field(body, "args").map(_.toString).getOrElse("{}")
                  CliStreamJson.toolChunk(name, args)
                }.toList
              case _                => Nil
            }
          case Some("result")                                                              =>
            val isError = CliStreamJson.field(json, "is_error").contains(Json.Bool(true))
            if isError then
              val msg = CliStreamJson.str(json, "result").getOrElse("cursor error")
              List(LlmChunk(delta = "", metadata = Map("cursorError" -> msg)))
            else List(LlmChunk(delta = "", finishReason = Some("stop")))
          case _                                                                           => Nil // system init, user echo, tool_call completed, future event types

  /** Join the text blocks of a `message.content` array (complete segments — no partial-output dedup needed). */
  private def messageText(message: Json): List[String] =
    CliStreamJson.field(message, "content").toList.flatMap {
      case Json.Arr(blocks) =>
        val text = blocks.toList.flatMap(b => CliStreamJson.str(b, "text")).mkString
        if text.nonEmpty then List(text) else Nil
      case _                => Nil
    }
