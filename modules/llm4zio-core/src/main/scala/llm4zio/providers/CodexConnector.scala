package llm4zio.providers

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream

import llm4zio.core.*

object CodexConnector:
  def make(config: CliConnectorConfig, executor: CliProcessExecutor): CliConnector =
    new CliConnector:
      private val cwd: String = config.workingDir.getOrElse(".")

      // `--model X` when set, then passthrough flags from config.flags (empty
      // value → bare `--flag`, e.g. `--full-auto`), sorted for determinism.
      private def extraArgs: List[String] =
        val modelArgs = config.model.toList.flatMap(m => List("--model", m))
        val flagArgs  = config.flags.toList.sortBy(_._1).flatMap {
          case (k, v) if v.isEmpty => List(s"--$k")
          case (k, v)              => List(s"--$k", v)
        }
        modelArgs ++ flagArgs

      override def id: ConnectorId                                          = ConnectorId.Codex
      override def interactionSupport: InteractionSupport                   = InteractionSupport.InteractiveStdin
      override def healthCheck: IO[LlmError, HealthStatus]                  =
        executor.run(List("codex", "--version"), cwd, Map.empty)
          .map(_ => HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
          .catchAll(_ => ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)))
      override def isAvailable: UIO[Boolean]                                =
        healthCheck.map(_.availability == Availability.Healthy).catchAll(_ => ZIO.succeed(false))
      // `codex exec` is the headless (non-interactive) subcommand.
      override def buildArgv(prompt: String, ctx: CliContext): List[String] =
        List("codex", "exec") ++ extraArgs ++ List(prompt)
      override def buildInteractiveArgv(ctx: CliContext): List[String]      =
        List("codex") ++ extraArgs
      override def complete(prompt: String): IO[LlmError, String]           =
        executor.run(buildArgv(prompt, CliContext(cwd, cwd)), cwd, config.envVars)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else
              ZIO.fail(LlmError.ProviderError(
                s"codex exited with code ${result.exitCode}: ${result.stdout.mkString("\n")}",
                None,
              ))
          }

      // Stream via `codex exec --json`, parsing the JSONL event stream into the shared LlmChunk metadata contract.
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val argv = List("codex", "exec", "--json") ++ extraArgs ++ List(prompt)
        executor.runStreaming(argv, cwd, config.envVars).mapConcat(CodexConnector.parseStreamLine)

  /** Parse one codex `--json` line into zero or more chunks. Stateless. */
  def parseStreamLine(line: String): List[LlmChunk] =
    CliStreamJson.parseLine(line) match
      case None       => Nil
      case Some(json) =>
        CliStreamJson.str(json, "type") match
          case Some("item.completed") =>
            CliStreamJson.field(json, "item").toList.flatMap { item =>
              CliStreamJson.str(item, "type") match
                case Some("agent_message")     =>
                  CliStreamJson.str(item, "text").filter(_.nonEmpty).map(t => LlmChunk(delta = t)).toList
                case Some("command_execution") =>
                  val cmd = CliStreamJson.str(item, "command").getOrElse("")
                  List(CliStreamJson.toolChunk("Bash", s"""{"command":${Json.Str(cmd).toString}}"""))
                case _                         => Nil
            }
          case Some("turn.completed") =>
            CliStreamJson.field(json, "usage").toList.map { usage =>
              val in  = CliStreamJson.int(usage, "input_tokens").getOrElse(0)
              val out = CliStreamJson.int(usage, "output_tokens").getOrElse(0)
              CliStreamJson.usageChunk(None, TokenUsage(in, out, in + out))
            }
          case _                      => Nil
