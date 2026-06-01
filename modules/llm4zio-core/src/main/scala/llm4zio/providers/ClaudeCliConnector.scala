package llm4zio.providers

import zio.*
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

      override def id: ConnectorId                                                  = ConnectorId.ClaudeCli
      override def interactionSupport: InteractionSupport                           = InteractionSupport.InteractiveStdin
      override def healthCheck: IO[LlmError, HealthStatus]                          =
        executor.run(List("claude", "--version"), cwd, Map.empty)
          .map(_ => HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
          .catchAll(_ => ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)))
      override def isAvailable: UIO[Boolean]                                        =
        healthCheck.map(_.availability == Availability.Healthy).catchAll(_ => ZIO.succeed(false))
      override def buildArgv(prompt: String, ctx: CliContext): List[String]         =
        List("claude", "--print") ++ extraArgs ++ List(prompt)
      override def buildInteractiveArgv(ctx: CliContext): List[String]              =
        List("claude") ++ extraArgs
      override def complete(prompt: String): IO[LlmError, String]                   =
        executor.run(buildArgv(prompt, CliContext(cwd, cwd)), cwd, config.envVars)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else
              ZIO.fail(LlmError.ProviderError(
                s"claude exited with code ${result.exitCode}: ${result.stdout.mkString("\n")}",
                None,
              ))
          }
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(complete(prompt)).map(text => LlmChunk(delta = text))
