package memory

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.json.*
import zio.json.ast.Json

import llm4zio.providers.HttpClient
import models.{ AIProvider, GatewayConfig }

trait EmbeddingService:
  def embed(text: String): IO[Throwable, Vector[Float]]
  def embedBatch(texts: List[String]): IO[Throwable, List[Vector[Float]]]

object EmbeddingService:
  val live: ZLayer[Ref[GatewayConfig] & HttpClient, Nothing, EmbeddingService] =
    ZLayer.fromFunction(EmbeddingServiceLive.apply)

final case class EmbeddingServiceLive(
  configRef: Ref[GatewayConfig],
  httpClient: HttpClient,
) extends EmbeddingService:

  private val defaultDimension: Int = 1536

  override def embed(text: String): IO[Throwable, Vector[Float]] =
    for
      normalizedText <- normalizeText(text)
      config         <- configRef.get.map(_.resolvedProviderConfig)
      result         <- config.provider match
                          case AIProvider.OpenAi | AIProvider.LmStudio | AIProvider.OpenCode =>
                            embedOpenAiCompatible(normalizedText, config)
                          case AIProvider.GeminiApi                                          =>
                            embedGemini(normalizedText, config)
                          case AIProvider.Ollama                                             =>
                            embedOllama(normalizedText, config)
                          case AIProvider.Anthropic                                          =>
                            ZIO.fail(
                              new RuntimeException(
                                "Embedding endpoint is not supported for Anthropic provider in this project"
                              )
                            )
                          case AIProvider.GeminiCli                                          =>
                            ZIO.fail(
                              new RuntimeException(
                                "Embedding endpoint is not supported for Gemini CLI provider; use GeminiApi/OpenAi/Ollama"
                              )
                            )
    yield result

  override def embedBatch(texts: List[String]): IO[Throwable, List[Vector[Float]]] =
    ZIO.foreach(texts)(embed)

  private def embedOpenAiCompatible(
    text: String,
    config: models.AIProviderConfig,
  ): IO[Throwable, Vector[Float]] =
    for
      baseUrl <- baseUrl(config)
      model    = embeddingModel(config.provider)
      request  = Json.Obj(
                   "model" -> Json.Str(model),
                   "input" -> Json.Str(text),
                 )
      headers  = authorizationHeaders(config.apiKey)
      body    <- httpClient
                   .postJson(
                     url = openAiCompatibleEmbeddingsUrl(config.provider, baseUrl),
                     body = request.toJson,
                     headers = headers,
                     timeout = config.timeout,
                   )
                   .mapError(toThrowable)
      parsed  <- ZIO
                   .fromEither(body.fromJson[OpenAiEmbeddingResponse])
                   .mapError(err => new RuntimeException(s"Unable to parse OpenAI-compatible embedding response: $err"))
      vector  <- ZIO
                   .fromOption(parsed.data.headOption.map(_.embedding.map(_.toFloat).toVector))
                   .orElseFail(new RuntimeException("OpenAI-compatible embedding response is empty"))
    yield vector

  private def embedGemini(
    text: String,
    config: models.AIProviderConfig,
  ): IO[Throwable, Vector[Float]] =
    for
      baseUrl   <- baseUrl(config)
      apiKey    <- ZIO
                     .fromOption(config.apiKey.map(_.trim).filter(_.nonEmpty))
                     .orElseFail(new RuntimeException("Gemini API key is required for embeddings"))
      model      = embeddingModel(config.provider)
      request    = Json.Obj(
                     "content"              -> Json.Obj(
                       "parts" -> Json.Arr(Chunk(Json.Obj("text" -> Json.Str(text))))
                     ),
                     "outputDimensionality" -> Json.Num(java.math.BigDecimal.valueOf(dimensionFromEnvironment.toLong)),
                   )
      encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
      url        = s"${baseUrl.stripSuffix("/")}/v1beta/models/$model:embedContent?key=$encodedKey"
      body      <- httpClient
                     .postJson(
                       url = url,
                       body = request.toJson,
                       headers = Map.empty,
                       timeout = config.timeout,
                     )
                     .mapError(toThrowable)
      parsed    <- ZIO
                     .fromEither(body.fromJson[GeminiEmbeddingResponse])
                     .mapError(err => new RuntimeException(s"Unable to parse Gemini embedding response: $err"))
    yield parsed.embedding.values.map(_.toFloat).toVector

  private def embedOllama(
    text: String,
    config: models.AIProviderConfig,
  ): IO[Throwable, Vector[Float]] =
    for
      baseUrl <- baseUrl(config)
      model    = embeddingModel(config.provider)
      request  = Json.Obj(
                   "model"  -> Json.Str(model),
                   "prompt" -> Json.Str(text),
                 )
      body    <- httpClient
                   .postJson(
                     url = s"${baseUrl.stripSuffix("/")}/api/embeddings",
                     body = request.toJson,
                     headers = Map.empty,
                     timeout = config.timeout,
                   )
                   .mapError(toThrowable)
      parsed  <- ZIO
                   .fromEither(body.fromJson[OllamaEmbeddingResponse])
                   .mapError(err => new RuntimeException(s"Unable to parse Ollama embedding response: $err"))
    yield parsed.embedding.map(_.toFloat).toVector

  private def normalizeText(text: String): IO[Throwable, String] =
    ZIO
      .succeed(text.trim)
      .filterOrFail(_.nonEmpty)(new RuntimeException("Embedding text must be non-empty"))

  private def baseUrl(config: models.AIProviderConfig): IO[Throwable, String] =
    ZIO
      .fromOption(config.baseUrl.map(_.trim).filter(_.nonEmpty))
      .orElseFail(new RuntimeException(s"Base URL is required for provider ${config.provider.toString}"))

  private def embeddingModel(provider: AIProvider): String =
    sys.env
      .get("AI_EMBEDDING_MODEL")
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(
        provider match
          case AIProvider.OpenAi | AIProvider.LmStudio | AIProvider.OpenCode => "text-embedding-3-small"
          case AIProvider.GeminiApi                                          => "text-embedding-004"
          case AIProvider.Ollama                                             => "nomic-embed-text"
          case AIProvider.Anthropic | AIProvider.GeminiCli                   => "text-embedding-3-small"
      )

  private def authorizationHeaders(apiKey: Option[String]): Map[String, String] =
    apiKey.map(_.trim).filter(_.nonEmpty).map(value => Map("Authorization" -> s"Bearer $value")).getOrElse(Map.empty)

  private def openAiCompatibleEmbeddingsUrl(provider: AIProvider, baseUrl: String): String =
    val normalized = baseUrl.stripSuffix("/")
    provider match
      case AIProvider.LmStudio => s"$normalized/v1/embeddings"
      case _                   => s"$normalized/embeddings"

  private def dimensionFromEnvironment: Int =
    sys.env
      .get("AI_EMBEDDING_DIMENSION")
      .flatMap(_.toIntOption)
      .filter(_ > 0)
      .getOrElse(defaultDimension)

  private def toThrowable(error: Any): Throwable =
    error match
      case t: Throwable => t
      case other        => new RuntimeException(other.toString)

  final private case class OpenAiEmbeddingResponse(data: List[OpenAiEmbeddingData]) derives JsonCodec
  final private case class OpenAiEmbeddingData(embedding: List[Double]) derives JsonCodec
  final private case class GeminiEmbeddingResponse(embedding: GeminiEmbeddingPayload) derives JsonCodec
  final private case class GeminiEmbeddingPayload(values: List[Double]) derives JsonCodec
  final private case class OllamaEmbeddingResponse(embedding: List[Double]) derives JsonCodec
