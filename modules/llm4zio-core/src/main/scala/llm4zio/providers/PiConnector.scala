package llm4zio.providers

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream

import llm4zio.core.*

object PiConnector:
  def make(config: CliConnectorConfig, executor: CliProcessExecutor): CliConnector =
    new CliConnector:
      private val cwd: String = config.workingDir.getOrElse(".")

      // `--model X` when set, then read-only restricts the toolset (pi has no plan mode; its 0.80.x built-ins
      // are read/bash/edit/write, so `read` is the only read-only one), then passthrough flags from
      // config.flags (empty value → bare `--flag`), sorted for determinism.
      private def extraArgs: List[String] =
        val modelArgs   = config.model.toList.flatMap(m => List("--model", m))
        val readOnlyArg = if config.readOnly then List("--tools", "read") else Nil
        val flagArgs    = config.flags.toList.sortBy(_._1).flatMap {
          case (k, v) if v.isEmpty => List(s"--$k")
          case (k, v)              => List(s"--$k", v)
        }
        modelArgs ++ readOnlyArg ++ flagArgs

      override def id: ConnectorId                        = ConnectorId.Pi
      override def interactionSupport: InteractionSupport = InteractionSupport.InteractiveStdin

      override def healthCheck: IO[LlmError, HealthStatus] =
        executor.run(List("pi", "--version"), cwd, Map.empty)
          .map(_ => HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
          .catchAll(_ => ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)))

      override def isAvailable: UIO[Boolean] =
        healthCheck.map(_.availability == Availability.Healthy).catchAll(_ => ZIO.succeed(false))

      override def buildArgv(prompt: String, ctx: CliContext): List[String] =
        List("pi", "-p") ++ extraArgs ++ List(prompt)
      override def buildInteractiveArgv(ctx: CliContext): List[String]      =
        List("pi") ++ extraArgs

      override def complete(prompt: String): IO[LlmError, String] =
        val argv = List("pi", "-p") ++ extraArgs
        executor.runWithStdin(argv, cwd, config.envVars, prompt)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else
              ZIO.fail(LlmError.ProviderError(
                s"pi exited with code ${result.exitCode}: ${result.stdout.mkString("\n")}",
                None,
              ))
          }

      // Stream via `pi -p --mode json`, parsing the JSONL event stream into the shared LlmChunk metadata contract.
      // Prompt is fed via stdin to avoid hitting ARG_MAX on large prompts.
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val argv = List("pi", "-p", "--mode", "json") ++ extraArgs
        executor.runStreamingWithStdin(argv, cwd, config.envVars, prompt)
          .mapConcat(PiConnector.parseStreamLine)
          .mapZIO { chunk =>
            chunk.metadata.get("piError") match
              case Some(msg) => ZIO.fail(LlmError.ProviderError(s"pi error: $msg", None))
              case None      => ZIO.succeed(chunk)
          }

  /** Parse one pi `--mode json` line into zero or more chunks. Stateless. */
  def parseStreamLine(line: String): List[LlmChunk] =
    CliStreamJson.parseLine(line) match
      case None       => Nil
      case Some(json) =>
        CliStreamJson.str(json, "type") match
          case Some("message_update")                  =>
            CliStreamJson.field(json, "assistantMessageEvent").toList.flatMap { ev =>
              CliStreamJson.str(ev, "type") match
                case Some("text_delta") =>
                  CliStreamJson.str(ev, "delta").filter(_.nonEmpty).map(t => LlmChunk(delta = t)).toList
                case _                  => Nil
            }
          case Some("tool_execution_start")            =>
            val name    = CliStreamJson.str(json, "toolName").getOrElse("")
            val argsStr = CliStreamJson.field(json, "args").map(_.toString).getOrElse("{}")
            List(CliStreamJson.toolChunk(name, argsStr))
          case Some("message_end") | Some("agent_end") =>
            extractUsage(json)
          case Some("auto_retry_end")                  =>
            val failed = json match
              case Json.Obj(fields) => fields.toMap.get("success").contains(Json.Bool(false))
              case _                => false
            if failed then
              val msg = CliStreamJson.str(json, "finalError").getOrElse("pi retry failed")
              List(LlmChunk(delta = "", metadata = Map("piError" -> msg)))
            else Nil
          case Some("extension_error")                 =>
            val msg = CliStreamJson.str(json, "error").getOrElse("pi extension error")
            List(LlmChunk(delta = "", metadata = Map("piError" -> msg)))
          case _                                       => Nil

  /** Read terminal token usage from `message.usage.{input,output}`; empty if absent (don't crash). */
  private def extractUsage(json: Json): List[LlmChunk] =
    CliStreamJson.field(json, "message")
      .flatMap(m => CliStreamJson.field(m, "usage"))
      .toList
      .map { usage =>
        val in  = CliStreamJson.int(usage, "input").getOrElse(0)
        val out = CliStreamJson.int(usage, "output").getOrElse(0)
        CliStreamJson.usageChunk(None, TokenUsage(in, out, in + out))
      }
