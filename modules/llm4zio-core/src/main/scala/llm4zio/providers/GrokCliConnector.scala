package llm4zio.providers

import java.time.ZoneId

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream

import llm4zio.core.*

object GrokCliConnector:
  def make(config: CliConnectorConfig, executor: CliProcessExecutor): CliConnector =
    new CliConnector:
      private val cwd: String = config.workingDir.getOrElse(".")

      // `--model X` when set; headless edits need `--always-approve` while readOnly maps to grok's plan
      // permission mode instead; then passthrough flags from config.flags (empty value → bare `--flag`),
      // sorted for determinism.
      private def extraArgs: List[String] =
        val modelArgs = config.model.toList.flatMap(m => List("--model", m))
        val modeArgs  = if config.readOnly then List("--permission-mode", "plan") else List("--always-approve")
        val flagArgs  = config.flags.toList.sortBy(_._1).flatMap {
          case (k, v) if v.isEmpty => List(s"--$k")
          case (k, v)              => List(s"--$k", v)
        }
        modelArgs ++ modeArgs ++ flagArgs

      override def id: ConnectorId                        = ConnectorId.Grok
      override def interactionSupport: InteractionSupport = InteractionSupport.ContinuationOnly

      override def healthCheck: IO[LlmError, HealthStatus] =
        executor.run(List("grok", "--version"), cwd, Map.empty)
          .map(_ => HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
          .catchAll(_ => ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)))

      override def isAvailable: UIO[Boolean] =
        healthCheck.map(_.availability == Availability.Healthy).catchAll(_ => ZIO.succeed(false))

      // grok's headless mode takes the prompt as the `-p` value — there is no stdin path; switch large
      // prompts to `--prompt-file` if ARG_MAX ever bites.
      override def buildArgv(prompt: String, ctx: CliContext): List[String] =
        List("grok") ++ extraArgs ++ List("-p", prompt)
      override def buildInteractiveArgv(ctx: CliContext): List[String]      =
        List("grok") ++ extraArgs

      override def complete(prompt: String): IO[LlmError, String] =
        executor.run(buildArgv(prompt, CliContext(cwd, "")), cwd, config.envVars)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else
              Clock.instant.flatMap { now =>
                val raw = result.stdout.mkString("\n")
                ZIO.fail(UsageLimits.classify("grok", raw, now, ZoneId.systemDefault)
                  .getOrElse(LlmError.ProviderError(s"grok exited with code ${result.exitCode}: $raw", None)))
              }
          }

      // Stream via `grok -p --output-format streaming-json`, parsing the JSONL event stream into the shared
      // LlmChunk metadata contract.
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val argv = List("grok", "--output-format", "streaming-json") ++ extraArgs ++ List("-p", prompt)
        executor.runStreaming(argv, cwd, config.envVars)
          .mapConcat(GrokCliConnector.parseStreamLine)
          .mapZIO { chunk =>
            chunk.metadata.get("grokError") match
              case Some(msg) =>
                Clock.instant.flatMap { now =>
                  ZIO.fail(UsageLimits.classify("grok", msg, now, ZoneId.systemDefault)
                    .getOrElse(LlmError.ProviderError(s"grok error: $msg", None)))
                }
              case None      => ZIO.succeed(chunk)
          }

  /** Parse one grok `--output-format streaming-json` line into zero or more chunks. Stateless. Recorded shape (grok
    * CLI, 2026-07): `thought`/`text` deltas and a terminal `end` event carrying usage plus a per-model breakdown; tool
    * executions emit no events of their own.
    */
  def parseStreamLine(line: String): List[LlmChunk] =
    CliStreamJson.parseLine(line) match
      case None       => Nil
      case Some(json) =>
        CliStreamJson.str(json, "type") match
          case Some("text")  =>
            CliStreamJson.str(json, "data").filter(_.nonEmpty).map(t => LlmChunk(delta = t)).toList
          case Some("end")   => extractUsage(json)
          case Some("error") =>
            val msg =
              CliStreamJson.str(json, "data").orElse(CliStreamJson.str(json, "message")).getOrElse("grok error")
            List(LlmChunk(delta = "", metadata = Map("grokError" -> msg)))
          case _             => Nil // `thought` deltas and any future event types

  /** Terminal usage from `usage.{input_tokens,output_tokens,total_tokens,cache_read_input_tokens}`; the served model is
    * the `modelUsage` object's key. Empty if absent (don't crash).
    */
  private def extractUsage(json: Json): List[LlmChunk] =
    CliStreamJson.field(json, "usage").toList.map { usage =>
      val in     = CliStreamJson.int(usage, "input_tokens").getOrElse(0)
      val out    = CliStreamJson.int(usage, "output_tokens").getOrElse(0)
      val total  = CliStreamJson.int(usage, "total_tokens").getOrElse(in + out)
      val cached = CliStreamJson.int(usage, "cache_read_input_tokens")
      val model  = CliStreamJson.field(json, "modelUsage").flatMap {
        case Json.Obj(fields) => fields.headOption.map(_._1)
        case _                => None
      }
      CliStreamJson.usageChunk(model, TokenUsage(in, out, total, cached))
    }
