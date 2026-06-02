package llm4zio.providers

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
        val flagArgs  = config.flags.toList.sortBy(_._1).flatMap {
          case (k, v) if v.isEmpty => List(s"--$k")
          case (k, v)              => List(s"--$k", v)
        }
        modelArgs ++ flagArgs

      override def id: ConnectorId                                          = ConnectorId.ClaudeCli
      override def interactionSupport: InteractionSupport                   = InteractionSupport.InteractiveStdin
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
        executor.run(buildArgv(prompt, CliContext(cwd, cwd)), cwd, config.envVars)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else
              ZIO.fail(LlmError.ProviderError(
                s"claude exited with code ${result.exitCode}: ${result.stdout.mkString("\n")}",
                None,
              ))
          }

      // Stream via `claude -p --output-format stream-json --verbose`, parsing JSONL events into the shared
      // LlmChunk metadata contract. The model name from the init line is captured and stamped onto usage chunks.
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val argv =
          List("claude", "--print", "--output-format", "stream-json", "--verbose") ++ extraArgs ++ List(prompt)
        ZStream.unwrap {
          Ref.make(Option.empty[String]).map { modelRef =>
            executor.runStreaming(argv, cwd, config.envVars).mapConcatZIO { line =>
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
      val in  = CliStreamJson.int(usage, "input_tokens").getOrElse(0)
      val out = CliStreamJson.int(usage, "output_tokens").getOrElse(0)
      CliStreamJson.usageChunk(None, TokenUsage(in, out, in + out))
    }
