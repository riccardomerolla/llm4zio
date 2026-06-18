package llm4zio.providers

import java.nio.file.Files
import java.time.ZoneId

import zio.*
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.tools.JsonSchema

object CodexConnector:
  def make(config: CliConnectorConfig, executor: CliProcessExecutor): CliConnector =
    new CliConnector:
      private val cwd: String = config.workingDir.getOrElse(".")

      // `--model X` when set, then passthrough flags from config.flags (empty
      // value → bare `--flag`, e.g. `--full-auto`), sorted for determinism.
      private def extraArgs: List[String] =
        val modelArgs = config.model.toList.flatMap(m => List("--model", m))
        // Read-only forces the read-only sandbox (no edits/shell), overriding any sandbox flag.
        val effective = if config.readOnly then config.flags + ("sandbox" -> "read-only") else config.flags
        val flagArgs  = effective.toList.sortBy(_._1).flatMap {
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
        val argv = List("codex", "exec") ++ extraArgs
        executor.runWithStdin(argv, cwd, config.envVars, prompt)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else
              ZIO.fail(LlmError.ProviderError(
                s"codex exited with code ${result.exitCode}: ${result.stdout.mkString("\n")}",
                None,
              ))
          }

      // Stream via `codex exec --json`, parsing the JSONL event stream into the shared LlmChunk metadata contract.
      // Prompt is fed via stdin to avoid hitting ARG_MAX on large prompts.
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val argv = List("codex", "exec", "--json") ++ extraArgs
        executor.runStreamingWithStdin(argv, cwd, config.envVars, prompt)
          .mapConcat(CodexConnector.parseStreamLine)
          .mapZIO { chunk =>
            chunk.metadata.get("codexError") match
              case Some(msg) =>
                Clock.instant.flatMap(now =>
                  ZIO.fail(UsageLimits.classify("codex", msg, now, ZoneId.systemDefault)
                    .getOrElse(LlmError.ProviderError(s"codex error: $msg", None)))
                )
              case None      => ZIO.succeed(chunk)
          }

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        executeStructuredWithUsage[A](prompt, schema).map(_._1)

      // Codex enforces a JSON Schema natively via `--output-schema <file>`. For an enforceable schema, write it to a
      // temp file, constrain the model, and capture token usage from the JSONL stream. Non-enforceable schemas (no
      // properties to constrain — e.g. a permissive `{"type":"object"}` or `{}`) use the prompt-hint + non-streaming
      // `complete` path (no usage available there): codex rejects such schemas with invalid_json_schema.
      override def executeStructuredWithUsage[A: JsonCodec](
        prompt: String,
        schema: JsonSchema,
      ): IO[LlmError, (A, Option[TokenUsage], Option[String])] =
        if !CodexConnector.enforceable(schema) then
          complete(StructuredOutputs.withSchemaHint(prompt, schema))
            .flatMap(text => StructuredOutputs.parseFromText[A](text, schema))
            .map(a => (a, None, None))
        else
          ZIO.scoped {
            for
              file  <- ZIO.acquireRelease(
                         ZIO
                           .attemptBlocking(Files.createTempFile("codex-schema-", ".json"))
                           .mapError(e => LlmError.ProviderError(e.getMessage, Some(e)))
                       )(f => ZIO.attemptBlocking(Files.deleteIfExists(f)).ignore)
              // codex's --output-schema is OpenAI strict structured output: every object must carry
              // `additionalProperties: false` and list all properties in `required`, or codex rejects it with a
              // 400 invalid_json_schema. Make the schema compliant before handing it over.
              _     <- ZIO
                         .attemptBlocking(Files.writeString(file, CodexConnector.strictSchema(schema).toString))
                         .mapError(e => LlmError.ProviderError(e.getMessage, Some(e)))
              argv   = List("codex", "exec", "--json", "--output-schema", file.toString) ++ extraArgs
              reply <- Streaming.collect(
                         executor.runStreamingWithStdin(
                           argv,
                           cwd,
                           config.envVars,
                           prompt,
                         ).mapConcat(CodexConnector.parseStreamLine)
                       )
              // codex surfaces request failures (e.g. a bad schema or a model error) as error/turn.failed events,
              // which carry no agent_message — fail with codex's reason instead of an opaque "no JSON candidate".
              _     <- ZIO.foreachDiscard(reply.metadata.get("codexError")) { msg =>
                         Clock.instant.flatMap { now =>
                           val err = UsageLimits.classify("codex", msg, now, ZoneId.systemDefault)
                             .getOrElse(LlmError.ProviderError(s"codex error: $msg", None))
                           ZIO.fail(err)
                         }
                       }
              out   <- StructuredOutputs.parseFromText[A](reply.content, schema)
            yield (out, reply.usage, reply.metadata.get("model"))
          }

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
          // Request/turn failures carry no agent_message; stash the reason under `codexError` so callers can surface it.
          case Some("error")          =>
            List(LlmChunk(
              delta = "",
              metadata = Map("codexError" -> CliStreamJson.str(json, "message").getOrElse("codex error")),
            ))
          case Some("turn.failed")    =>
            val msg =
              CliStreamJson.field(json, "error").flatMap(e => CliStreamJson.str(e, "message")).getOrElse("turn failed")
            List(LlmChunk(delta = "", metadata = Map("codexError" -> msg)))
          case _                      => Nil

  /** Whether a schema can be enforced by codex's `--output-schema` (OpenAI strict structured output). That mode needs a
    * root object with at least one property (it then requires `additionalProperties:false` + `required`). A permissive
    * schema like `{"type":"object"}` (the shape [[llm4zio.flow.Planner]] uses for sum-typed calls) or `{}` carries
    * nothing to constrain, and codex rejects it with `invalid_json_schema` — such schemas must take the prompt-hint
    * path.
    */
  def enforceable(schema: Json): Boolean = schema match
    case Json.Obj(fields) =>
      fields.exists {
        case ("properties", Json.Obj(props)) => props.nonEmpty
        case _                               => false
      }
    case _                => false

  /** Make a JSON Schema compliant with codex's OpenAI strict structured-output mode: every object gets
    * `additionalProperties: false` and lists all of its properties in `required`. Recurses through `properties` values
    * and array `items`. Other nodes pass through unchanged.
    */
  def strictSchema(json: Json): Json = json match
    case Json.Obj(fields) =>
      val recursed = fields.map((k, v) => (k, strictSchema(v)))
      recursed.collectFirst { case ("properties", Json.Obj(props)) => props.map((k, _) => Json.Str(k): Json) } match
        case Some(names) =>
          val cleaned = recursed.filterNot(kv => kv._1 == "additionalProperties" || kv._1 == "required")
          Json.Obj(cleaned ++ zio.Chunk("additionalProperties" -> Json.Bool(false), "required" -> Json.Arr(names)))
        case None        => Json.Obj(recursed)
    case Json.Arr(elems)  => Json.Arr(elems.map(strictSchema))
    case other            => other
