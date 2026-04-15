package llm4zio.providers

import zio.*
import zio.stream.ZStream

import llm4zio.core.*

object OpenCodeCliConnector:
  def make(config: CliConnectorConfig, executor: CliProcessExecutor): CliConnector =
    new CliConnector:
      override def id: ConnectorId = ConnectorId.OpenCode
      override def interactionSupport: InteractionSupport = InteractionSupport.ContinuationOnly
      override def healthCheck: IO[LlmError, HealthStatus] =
        executor.run(List("opencode", "--version"), ".", Map.empty)
          .map(_ => HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
          .catchAll(_ => ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None)))
      override def isAvailable: UIO[Boolean] =
        healthCheck.map(_.availability == Availability.Healthy).catchAll(_ => ZIO.succeed(false))
      override def buildArgv(prompt: String, ctx: CliContext): List[String] =
        List("opencode", "run", "--prompt", prompt)
      override def buildInteractiveArgv(ctx: CliContext): List[String] =
        List("opencode")
      override def complete(prompt: String): IO[LlmError, String] =
        executor.run(buildArgv(prompt, CliContext(".", "")), ".", config.envVars)
          .flatMap { result =>
            if result.exitCode == 0 then ZIO.succeed(result.stdout.mkString("\n"))
            else ZIO.fail(LlmError.ProviderError(s"opencode exited with code ${result.exitCode}: ${result.stdout.mkString("\n")}", None))
          }
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(complete(prompt)).map(text => LlmChunk(delta = text))
