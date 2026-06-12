package llm4zio.providers

import java.time.ZoneId

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream

import llm4zio.core.*

object ClaudeCliConnector:
  def make(config: CliConnectorConfig, executor: CliProcessExecutor): CliConnector =
    new CliConnector:
      private val cwd: String = config.workingDir.getOrElse(".")

      // `--model X` when set, then passthrough flags from config.flags (empty
      // value → bare `--flag`, else `--flag value`), sorted for determinism.
      // Empty config → just `claude --print <prompt>` (unchanged default).
      private def extraArgs: List[String] =
        val modelArgs = config.model.toList.flatMap(m => List("--model", m))
        // Read-only forces plan mode (no edits/no side-effecting tools), overriding any permission-mode flag.
        val effective = if config.readOnly then config.flags + ("permission-mode" -> "plan") else config.flags
        val flagArgs  = effective.toList.sortBy(_._1).flatMap {
          case (k, v) if v.isEmpty => List(s"--$k")
          case (k, v)              => List(s"--$k", v)
        }
        modelArgs ++ flagArgs

      override def id: ConnectorId                                          = ConnectorId.ClaudeCli
      override def interactionSupport: InteractionSupport                   = InteractionSupport.InteractiveStdin
      // Claude is the one CLI llm4zio drives as a held agent session (ClaudeAgentSession + InteractiveCoder), so it
      // genuinely supports ask-user and approval over MCP and resumable multi-turn sessions.
      override def capabilities: ConnectorCapabilities                      =
        ConnectorCapabilities(
          interactiveSessions = true,
          askUser = true,
          approval = true,
          resumableSessions = true,
        )
      override def healthCheck: IO[LlmError, HealthStatus]                  =
        executor.run(List("claude", "--version"), cwd, Map.empty)
          .map(_ => HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
          .catchAll(_ => ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)))
      override def isAvailable: UIO[Boolean]                                =
        healthCheck.map(_.availability == Availability.Healthy).catchAll(_ => ZIO.succeed(false))
      override def buildArgv(prompt: String, ctx: CliContext): List[String] =
        List("claude", "--print") ++ extraArgs ++ List(prompt)
      override def buildInteractiveArgv(ctx: CliContext): List[String]      =
        List("claude") ++ extraArgs
      override def complete(prompt: String): IO[LlmError, String]           =
        val argv = List("claude", "--print") ++ extraArgs
        executor.runWithStdin(argv, cwd, config.envVars, prompt)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else
              Clock.instant.flatMap { now =>
                val raw = result.stdout.mkString("\n")
                ZIO.fail(UsageLimits.classify("claude", raw, now, ZoneId.systemDefault)
                  .getOrElse(LlmError.ProviderError(s"claude exited with code ${result.exitCode}: $raw", None)))
              }
          }

      // Stream via `claude -p --output-format stream-json --verbose`, parsing JSONL events into the shared
      // LlmChunk metadata contract. Prompt is fed via stdin to avoid hitting ARG_MAX on large prompts.
      // The model name from the init line is captured and stamped onto usage chunks.
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val argv =
          List("claude", "--print", "--output-format", "stream-json", "--verbose") ++ extraArgs
        ZStream.unwrap {
          Ref.make(Option.empty[String]).map { modelRef =>
            executor.runStreamingWithStdin(argv, cwd, config.envVars, prompt).mapConcatZIO { line =>
              val captureModel = ClaudeCliConnector.initModel(line) match
                case Some(m) => modelRef.set(Some(m))
                case None    => ZIO.unit
              captureModel *> ZIO.foreach(ClaudeCliConnector.parseStreamLine(line)) { chunk =>
                if chunk.usage.isDefined then
                  modelRef.get.map(m => chunk.copy(metadata = chunk.metadata ++ m.map("model" -> _).toMap))
                else ZIO.succeed(chunk)
              }
            }
          }
        }

  /** Parse one claude stream-json line into zero or more chunks. Stateless. */
  def parseStreamLine(line: String): List[LlmChunk] =
    CliStreamJson.parseLine(line) match
      case None       => Nil
      case Some(json) =>
        CliStreamJson.str(json, "type") match
          case Some("assistant") => assistantChunks(json)
          case Some("result")    => resultChunk(json).toList
          case _                 => Nil

  /** The model name if this line is the init system event. */
  def initModel(line: String): Option[String] =
    CliStreamJson.parseLine(line).flatMap { json =>
      if CliStreamJson.str(json, "type").contains("system") then CliStreamJson.str(json, "model") else None
    }

  private def assistantChunks(json: Json): List[LlmChunk] =
    val content = CliStreamJson.field(json, "message").flatMap(CliStreamJson.field(_, "content"))
    content match
      case Some(Json.Arr(blocks)) =>
        blocks.toList.flatMap { block =>
          CliStreamJson.str(block, "type") match
            case Some("text")     =>
              CliStreamJson.str(block, "text").filter(_.nonEmpty).map(t => LlmChunk(delta = t)).toList
            case Some("tool_use") =>
              val name = CliStreamJson.str(block, "name").getOrElse("")
              val raw  = CliStreamJson.field(block, "input").map(_.toString).getOrElse("{}")
              List(CliStreamJson.toolChunk(name, raw))
            case _                => Nil
        }
      case _                      => Nil

  private def resultChunk(json: Json): Option[LlmChunk] =
    CliStreamJson.field(json, "usage").map { usage =>
      val in     = CliStreamJson.int(usage, "input_tokens").getOrElse(0)
      val out    = CliStreamJson.int(usage, "output_tokens").getOrElse(0)
      val cached = CliStreamJson.int(usage, "cache_read_input_tokens")
      CliStreamJson.usageChunk(None, TokenUsage(in, out, in + out, cached))
    }
