package llm4zio.providers

import java.time.ZoneId

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream

import llm4zio.core.*

object OpenCodeCliConnector:
  def make(config: CliConnectorConfig, executor: CliProcessExecutor): CliConnector =
    new CliConnector:
      private val cwd: String = config.workingDir.getOrElse(".")

      // `--model provider/model` when set; edits need `--auto` (auto-approve permissions) while readOnly maps to
      // the built-in read-only `plan` agent instead; then passthrough flags from config.flags (empty value →
      // bare `--flag`), sorted for determinism. The message itself is positional and goes last.
      private def extraArgs: List[String] =
        val modelArgs = config.model.toList.flatMap(m => List("--model", m))
        val modeArgs  = if config.readOnly then List("--agent", "plan") else List("--auto")
        val flagArgs  = config.flags.toList.sortBy(_._1).flatMap {
          case (k, v) if v.isEmpty => List(s"--$k")
          case (k, v)              => List(s"--$k", v)
        }
        modelArgs ++ modeArgs ++ flagArgs

      override def id: ConnectorId                        = ConnectorId.OpenCode
      override def interactionSupport: InteractionSupport = InteractionSupport.ContinuationOnly

      override def healthCheck: IO[LlmError, HealthStatus] =
        executor.run(List("opencode", "--version"), cwd, Map.empty)
          .map(_ => HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
          .catchAll(_ => ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)))

      override def isAvailable: UIO[Boolean] =
        healthCheck.map(_.availability == Availability.Healthy).catchAll(_ => ZIO.succeed(false))

      override def buildArgv(prompt: String, ctx: CliContext): List[String] =
        List("opencode", "run") ++ extraArgs ++ List(prompt)
      override def buildInteractiveArgv(ctx: CliContext): List[String]      =
        List("opencode")

      override def complete(prompt: String): IO[LlmError, String] =
        executor.run(buildArgv(prompt, CliContext(cwd, "")), cwd, config.envVars)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else
              Clock.instant.flatMap { now =>
                val raw = result.stdout.mkString("\n")
                ZIO.fail(UsageLimits.classify("opencode", raw, now, ZoneId.systemDefault)
                  .getOrElse(LlmError.ProviderError(s"opencode exited with code ${result.exitCode}: $raw", None)))
              }
          }

      // Stream via `opencode run --format json`, parsing the JSON event stream into the shared LlmChunk
      // metadata contract.
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val argv = List("opencode", "run", "--format", "json") ++ extraArgs ++ List(prompt)
        executor.runStreaming(argv, cwd, config.envVars)
          .mapConcat(OpenCodeCliConnector.parseStreamLine)
          .mapZIO { chunk =>
            chunk.metadata.get("opencodeError") match
              case Some(msg) =>
                Clock.instant.flatMap { now =>
                  ZIO.fail(UsageLimits.classify("opencode", msg, now, ZoneId.systemDefault)
                    .getOrElse(LlmError.ProviderError(s"opencode error: $msg", None)))
                }
              case None      => ZIO.succeed(chunk)
          }

  /** Parse one opencode `--format json` line into zero or more chunks. Stateless. Recorded shape (opencode 1.18.3):
    * part events wrapped in `{type, timestamp, sessionID, part}` — `text` parts carry the delta, `tool_use` parts carry
    * `part.tool` + `part.state.input`, and each `step_finish` carries that step's token counts (one usage chunk per
    * step; CostTracker sums them).
    */
  def parseStreamLine(line: String): List[LlmChunk] =
    CliStreamJson.parseLine(line) match
      case None       => Nil
      case Some(json) =>
        CliStreamJson.str(json, "type") match
          case Some("text")        =>
            CliStreamJson.field(json, "part").toList.flatMap { part =>
              CliStreamJson.str(part, "text").filter(_.nonEmpty).map(t => LlmChunk(delta = t)).toList
            }
          case Some("tool_use")    =>
            CliStreamJson.field(json, "part").toList.flatMap { part =>
              val name = CliStreamJson.str(part, "tool").getOrElse("")
              val args = CliStreamJson.field(part, "state")
                .flatMap(s => CliStreamJson.field(s, "input"))
                .map(_.toString)
                .getOrElse("{}")
              List(CliStreamJson.toolChunk(name, args))
            }
          case Some("step_finish") =>
            CliStreamJson.field(json, "part").toList.flatMap(extractUsage)
          case Some("error")       =>
            val msg =
              CliStreamJson.str(json, "error").orElse(CliStreamJson.str(json, "message")).getOrElse("opencode error")
            List(LlmChunk(delta = "", metadata = Map("opencodeError" -> msg)))
          case _                   => Nil // step_start and any future event types

  /** Per-step usage from `part.tokens.{input,output,total,cache.read}`; empty if absent (don't crash). */
  private def extractUsage(part: Json): List[LlmChunk] =
    CliStreamJson.field(part, "tokens").toList.map { tokens =>
      val in     = CliStreamJson.int(tokens, "input").getOrElse(0)
      val out    = CliStreamJson.int(tokens, "output").getOrElse(0)
      val total  = CliStreamJson.int(tokens, "total").getOrElse(in + out)
      val cached = CliStreamJson.field(tokens, "cache").flatMap(c => CliStreamJson.int(c, "read"))
      CliStreamJson.usageChunk(None, TokenUsage(in, out, total, cached))
    }
