package app

import zio.*
import zio.stream

import _root_.config.entity.{ AIProviderConfig, GatewayConfig }
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.{ AnyTool, JsonSchema }

final private[app] case class ConfigAwareLlmService(
  configRef: Ref[GatewayConfig],
  http: HttpClient,
  cliExec: GeminiCliExecutor,
  cacheRef: Ref.Synchronized[Map[LlmConfig, LlmService]],
) extends LlmService:

  override def executeStream(prompt: String): stream.Stream[LlmError, LlmChunk] =
    stream.ZStream.unwrap(serviceChain.map(chain => failoverStream(chain)(_.executeStream(prompt))))

  override def executeStreamWithHistory(messages: List[Message]): stream.Stream[LlmError, LlmChunk] =
    stream.ZStream.unwrap(serviceChain.map(chain => failoverStream(chain)(_.executeStreamWithHistory(messages))))

  override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    withFailover(_.executeWithTools(prompt, tools))

  override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    withFailover(_.executeStructured(prompt, schema))

  override def isAvailable: UIO[Boolean] =
    serviceChain
      .flatMap { chain =>
        ZIO.foreach(chain)(_.isAvailable).map(_.exists(identity))
      }
      .orElseSucceed(false)

  private def serviceChain: IO[LlmError, List[LlmService]] =
    for
      aiCfg <- configRef.get.map(_.resolvedProviderConfig)
      cfgs   = fallbackConfigs(aiCfg)
      svcs  <- ZIO.foreach(cfgs)(providerFor)
    yield svcs

  private def providerFor(cfg: LlmConfig): IO[LlmError, LlmService] =
    cacheRef.modifyZIO { current =>
      current.get(cfg) match
        case Some(existing) => ZIO.succeed((existing, current))
        case None           =>
          ZIO
            .attempt(buildProvider(cfg))
            .mapError(th => LlmError.ConfigError(Option(th.getMessage).getOrElse(th.toString)))
            .map(created => (created, current + (cfg -> created)))
    }

  private def fallbackConfigs(primary: AIProviderConfig): List[LlmConfig] =
    val primaryLlm = ApplicationDI.aiConfigToLlmConfig(primary)
    val fallback   = primary.fallbackChain.models.map { ref =>
      ApplicationDI.aiConfigToLlmConfig(
        AIProviderConfig.withDefaults(
          primary.copy(
            provider = ref.provider.getOrElse(primary.provider),
            model = ref.modelId,
          )
        )
      )
    }
    (primaryLlm :: fallback).distinct

  private def withFailover[A](run: LlmService => IO[LlmError, A]): IO[LlmError, A] =
    serviceChain.flatMap { chain =>
      failoverIO(chain)(run)
    }

  private def failoverIO[A](services: List[LlmService])(run: LlmService => IO[LlmError, A]): IO[LlmError, A] =
    services match
      case head :: tail =>
        run(head).catchAll { err =>
          tail match
            case Nil => ZIO.fail(err)
            case _   => failoverIO(tail)(run)
        }
      case Nil          =>
        ZIO.fail(LlmError.ConfigError("No LLM provider configured"))

  private def failoverStream(
    services: List[LlmService]
  )(
    run: LlmService => stream.Stream[LlmError, LlmChunk]
  ): stream.Stream[LlmError, LlmChunk] =
    services match
      case head :: tail =>
        run(head).catchAll { err =>
          tail match
            case Nil => stream.ZStream.fail(err)
            case _   => failoverStream(tail)(run)
        }
      case Nil          =>
        stream.ZStream.fail(LlmError.ConfigError("No LLM provider configured"))

  private def buildProvider(cfg: LlmConfig): LlmService =
    import llm4zio.providers.*
    cfg.provider match
      case LlmProvider.GeminiCli => GeminiCliProvider.make(cfg, cliExec)
      case LlmProvider.GeminiApi => GeminiApiProvider.make(cfg, http)
      case LlmProvider.OpenAI    => OpenAIProvider.make(cfg, http)
      case LlmProvider.Anthropic => AnthropicProvider.make(cfg, http)
      case LlmProvider.LmStudio  => LmStudioProvider.make(cfg, http)
      case LlmProvider.Ollama    => OllamaProvider.make(cfg, http)
      case LlmProvider.OpenCode  => OpenCodeProvider.make(cfg, http)
      case LlmProvider.Mock      => MockProvider.make(cfg)
