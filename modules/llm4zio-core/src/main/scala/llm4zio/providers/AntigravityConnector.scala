package llm4zio.providers

import zio.*
import zio.stream.ZStream

import llm4zio.core.*

/** Google's Antigravity CLI (argv binary `agy`) — the retail successor to gemini-cli (gemini-cli itself stays supported
  * for the enterprise customer that depends on it; see [[GeminiCliProvider]]).
  *
  * Unlike gemini/codex/pi, `agy --print` has no structured JSON output mode (confirmed against `agy` 1.1.2): it just
  * prints plain text to stdout and exits, with errors going to stderr only and a generic non-zero exit code (no
  * per-failure-kind codes like gemini's 42/53). So there is no in-band event stream to parse — `completeStream` just
  * line-chunks stdout and relies on [[llm4zio.core.CliProcessExecutor.runStreaming]] surfacing a captured-stderr
  * failure on a non-zero exit.
  *
  * `agy --print <prompt>` takes the prompt as `--print`'s own argument (there is no stdin-prompt mode), so — unlike
  * codex/pi, which feed the prompt via stdin to dodge `ARG_MAX` — the prompt here always goes on argv.
  *
  * Crucially, agy does NOT treat the process cwd as its workspace (unlike gemini/claude/codex): in `--print` mode a
  * write lands in agy's own scratch project (`~/.gemini/antigravity-cli/scratch`) unless the target directory is added
  * to the session workspace with `--add-dir`. So whenever a `workingDir` is configured — which the flow layer always
  * sets for the coder, to the target repo — we pass `--add-dir <workingDir>`; without it a coder's edits never reach
  * the repo the flow commits. (Verified against `agy` 1.1.2.)
  */
object AntigravityConnector:
  def make(config: CliConnectorConfig, executor: CliProcessExecutor): CliConnector =
    new CliConnector:
      private val cwd: String = config.workingDir.getOrElse(".")

      // Add the configured working dir to agy's session workspace — otherwise edits go to agy's scratch project, not
      // the repo (see the class comment). Emitted first so it's present regardless of model/flags.
      private def workspaceArgs: List[String] =
        config.workingDir.toList.flatMap(dir => List("--add-dir", dir))

      // `--add-dir <workingDir>`, then `--model X` when set, then read-only forces `--mode plan` (overriding any
      // passthrough `mode` flag — e.g. the `antigravity` preset's `mode=accept-edits`), then remaining passthrough
      // flags from config.flags (empty value → bare `--flag`), sorted for determinism.
      private def extraArgs: List[String] =
        val modelArgs = config.model.toList.flatMap(m => List("--model", m))
        val effective = if config.readOnly then config.flags + ("mode" -> "plan") else config.flags
        val flagArgs  = effective.toList.sortBy(_._1).flatMap {
          case (k, v) if v.isEmpty => List(s"--$k")
          case (k, v)              => List(s"--$k", v)
        }
        workspaceArgs ++ modelArgs ++ flagArgs

      override def id: ConnectorId                        = ConnectorId.AntigravityCli
      override def interactionSupport: InteractionSupport = InteractionSupport.InteractiveStdin

      override def healthCheck: IO[LlmError, HealthStatus] =
        executor.run(List("agy", "--version"), cwd, Map.empty)
          .map { result =>
            if result.exitCode == 0 then HealthStatus(Availability.Healthy, AuthStatus.Valid, None)
            else HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)
          }
          .catchAll(_ => ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)))

      override def isAvailable: UIO[Boolean] =
        healthCheck.map(_.availability == Availability.Healthy).catchAll(_ => ZIO.succeed(false))

      override def buildArgv(prompt: String, ctx: CliContext): List[String] =
        List("agy") ++ extraArgs ++ List("--print", prompt)

      override def buildInteractiveArgv(ctx: CliContext): List[String] =
        List("agy") ++ extraArgs

      override def complete(prompt: String): IO[LlmError, String] =
        val argv = List("agy") ++ extraArgs ++ List("--print", prompt)
        executor.run(argv, cwd, config.envVars).flatMap { result =>
          if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
          else
            val detail = (result.stderr ++ result.stdout).mkString("\n")
            ZIO.fail(LlmError.ProviderError(s"agy exited with code ${result.exitCode}: $detail", None))
        }

      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val argv = List("agy") ++ extraArgs ++ List("--print", prompt)
        executor.runStreaming(argv, cwd, config.envVars).map(line => LlmChunk(delta = line)) ++
          ZStream.succeed(LlmChunk(delta = "", finishReason = Some("stop")))
