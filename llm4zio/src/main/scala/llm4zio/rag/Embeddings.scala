package llm4zio.rag

import zio.*
import zio.json.*
import zio.json.ast.Json
import llm4zio.core.{ LlmError, RateLimiter, RateLimiterConfig }
import llm4zio.providers.HttpClient

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum EmbeddingError derives JsonCodec:
  case ProviderError(message: String)
  case ParseError(message: String, raw: String)
  case InvalidInput(message: String)
  case RateLimited(message: String)

object EmbeddingError:
  extension (error: EmbeddingError)
    def message: String = error match
      case EmbeddingError.ProviderError(msg)    => msg
      case EmbeddingError.ParseError(msg, _)    => msg
      case EmbeddingError.InvalidInput(msg)     => msg
      case EmbeddingError.RateLimited(msg)      => msg

trait EmbeddingService:
  def embed(text: String): IO[EmbeddingError, Vector[Double]]
  def embedBatch(texts: Chunk[String]): IO[EmbeddingError, Chunk[Vector[Double]]]

object EmbeddingService:
  def embed(text: String): ZIO[EmbeddingService, EmbeddingError, Vector[Double]] =
    ZIO.serviceWithZIO[EmbeddingService](_.embed(text))

  def embedBatch(texts: Chunk[String]): ZIO[EmbeddingService, EmbeddingError, Chunk[Vector[Double]]] =
    ZIO.serviceWithZIO[EmbeddingService](_.embedBatch(texts))

  def deterministic(dimensions: Int = 256): UIO[EmbeddingService] =
    ZIO.succeed(DeterministicEmbeddingService(dimensions))

  def cached(base: EmbeddingService): UIO[EmbeddingService] =
    Ref.make(Map.empty[String, Vector[Double]]).map(cache => CachedEmbeddingService(base, cache))

  def openAI(
    httpClient: HttpClient,
    baseUrl: String,
    apiKey: String,
    model: String,
    timeout: Duration = 60.seconds,
    batchSize: Int = 32,
    limiterConfig: RateLimiterConfig = RateLimiterConfig(requestsPerMinute = 3000, burstSize = 200),
  ): ZIO[Scope, Nothing, EmbeddingService] =
    RateLimiter.make(limiterConfig).map { limiter =>
      OpenAiEmbeddingService(httpClient, baseUrl, apiKey, model, timeout, batchSize, limiter)
    }

  def ollama(
    httpClient: HttpClient,
    baseUrl: String,
    model: String,
    timeout: Duration = 60.seconds,
    limiterConfig: RateLimiterConfig = RateLimiterConfig(requestsPerMinute = 1200, burstSize = 100),
  ): ZIO[Scope, Nothing, EmbeddingService] =
    RateLimiter.make(limiterConfig).map { limiter =>
      OllamaEmbeddingService(httpClient, baseUrl, model, timeout, limiter)
    }

private final case class CachedEmbeddingService(
  base: EmbeddingService,
  cache: Ref[Map[String, Vector[Double]]],
) extends EmbeddingService:
  override def embed(text: String): IO[EmbeddingError, Vector[Double]] =
    for
      _ <- ensureNonEmpty(text)
      current <- cache.get
      vector <- current.get(text) match
                  case Some(value) => ZIO.succeed(value)
                  case None        =>
                    for
                      embedded <- base.embed(text)
                      _ <- cache.update(_.updated(text, embedded))
                    yield embedded
    yield vector

  override def embedBatch(texts: Chunk[String]): IO[EmbeddingError, Chunk[Vector[Double]]] =
    ZIO.foreach(texts)(embed).map(Chunk.fromIterable)

private final case class DeterministicEmbeddingService(dimensions: Int) extends EmbeddingService:
  override def embed(text: String): IO[EmbeddingError, Vector[Double]] =
    for
      _ <- ensureNonEmpty(text)
      embedding <- ZIO.attempt {
                     val bytes = sha256(text)
                     val seed = bytes.foldLeft(0L)((acc, b) => (acc * 31L + (b & 0xff).toLong) & Long.MaxValue)
                     val random = new scala.util.Random(seed)
                     Vector.fill(dimensions)(random.nextDouble() * 2.0 - 1.0)
                   }.mapError(err => EmbeddingError.ProviderError(err.getMessage))
      normalized <- normalize(embedding)
    yield normalized

  override def embedBatch(texts: Chunk[String]): IO[EmbeddingError, Chunk[Vector[Double]]] =
    ZIO.foreach(texts)(embed).map(Chunk.fromIterable)

  private def sha256(text: String): Array[Byte] =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(text.getBytes(StandardCharsets.UTF_8))

private final case class OpenAiEmbeddingService(
  httpClient: HttpClient,
  baseUrl: String,
  apiKey: String,
  model: String,
  timeout: Duration,
  batchSize: Int,
  limiter: RateLimiter,
) extends EmbeddingService:

  override def embed(text: String): IO[EmbeddingError, Vector[Double]] =
    embedBatch(Chunk.single(text)).flatMap { vectors =>
      ZIO.fromOption(vectors.headOption).orElseFail(EmbeddingError.ProviderError("Empty embedding response"))
    }

  override def embedBatch(texts: Chunk[String]): IO[EmbeddingError, Chunk[Vector[Double]]] =
    for
      _ <- ensureNonEmptyBatch(texts)
      chunks = texts.grouped(batchSize.max(1)).toList
      vectors <- ZIO.foreach(chunks) { batch =>
                   for
                     _ <- limiter.acquire.mapError(err => EmbeddingError.RateLimited(err.toString))
                     request = Json.Obj(
                                 "model" -> Json.Str(model),
                                 "input" -> Json.Arr(Chunk.fromIterable(batch.map(Json.Str(_)))),
                               )
                     body <- httpClient
                               .postJson(
                                 url = s"${baseUrl.stripSuffix("/")}/embeddings",
                                 body = request.toJson,
                                 headers = Map("Authorization" -> s"Bearer $apiKey"),
                                 timeout = timeout,
                               )
                               .mapError(toEmbeddingError)
                     parsed <- parseOpenAi(body)
                   yield parsed
                 }
    yield Chunk.fromIterable(vectors.flatten)

  private def parseOpenAi(raw: String): IO[EmbeddingError, List[Vector[Double]]] =
    ZIO
      .fromEither(raw.fromJson[OpenAiEmbeddingResponse])
      .mapError(err => EmbeddingError.ParseError(s"Failed to parse OpenAI embeddings: $err", raw))
      .flatMap { response =>
        ZIO.foreach(response.data) { row =>
          normalize(row.embedding)
        }
      }

private final case class OllamaEmbeddingService(
  httpClient: HttpClient,
  baseUrl: String,
  model: String,
  timeout: Duration,
  limiter: RateLimiter,
) extends EmbeddingService:

  override def embed(text: String): IO[EmbeddingError, Vector[Double]] =
    for
      _ <- ensureNonEmpty(text)
      _ <- limiter.acquire.mapError(err => EmbeddingError.RateLimited(err.toString))
      request = Json.Obj(
                  "model" -> Json.Str(model),
                  "prompt" -> Json.Str(text),
                )
      body <- httpClient
                .postJson(
                  url = s"${baseUrl.stripSuffix("/")}/api/embeddings",
                  body = request.toJson,
                  headers = Map.empty,
                  timeout = timeout,
                )
                .mapError(toEmbeddingError)
      parsed <- ZIO
                  .fromEither(body.fromJson[OllamaEmbeddingResponse])
                  .mapError(err => EmbeddingError.ParseError(s"Failed to parse Ollama embeddings: $err", body))
      normalized <- normalize(parsed.embedding)
    yield normalized

  override def embedBatch(texts: Chunk[String]): IO[EmbeddingError, Chunk[Vector[Double]]] =
    ZIO.foreach(texts)(embed).map(Chunk.fromIterable)

private final case class OpenAiEmbeddingResponse(data: List[OpenAiEmbeddingData]) derives JsonCodec
private final case class OpenAiEmbeddingData(index: Int, embedding: Vector[Double]) derives JsonCodec
private final case class OllamaEmbeddingResponse(embedding: Vector[Double]) derives JsonCodec

private def ensureNonEmpty(text: String): IO[EmbeddingError, Unit] =
  if text.trim.isEmpty then ZIO.fail(EmbeddingError.InvalidInput("Text for embedding must be non-empty"))
  else ZIO.unit

private def ensureNonEmptyBatch(texts: Chunk[String]): IO[EmbeddingError, Unit] =
  if texts.isEmpty then ZIO.fail(EmbeddingError.InvalidInput("Embedding batch must be non-empty"))
  else ZIO.foreachDiscard(texts)(ensureNonEmpty)

private def normalize(vector: Vector[Double]): IO[EmbeddingError, Vector[Double]] =
  if vector.isEmpty then ZIO.fail(EmbeddingError.InvalidInput("Embedding vector must be non-empty"))
  else
    val norm = math.sqrt(vector.map(v => v * v).sum)
    if norm <= 0.0 then ZIO.fail(EmbeddingError.InvalidInput("Embedding norm must be > 0"))
    else ZIO.succeed(vector.map(_ / norm))

private def toEmbeddingError(error: LlmError): EmbeddingError =
  error match
    case LlmError.ProviderError(message, _)    => EmbeddingError.ProviderError(message)
    case LlmError.AuthenticationError(message) => EmbeddingError.ProviderError(message)
    case LlmError.InvalidRequestError(message) => EmbeddingError.InvalidInput(message)
    case LlmError.TimeoutError(duration)       => EmbeddingError.ProviderError(s"Embedding request timeout after ${duration.toMillis}ms")
    case LlmError.ParseError(message, raw)     => EmbeddingError.ParseError(message, raw)
    case LlmError.RateLimitError(_)            => EmbeddingError.RateLimited("Embedding provider rate limited request")
    case LlmError.ConfigError(message)         => EmbeddingError.ProviderError(message)
    case LlmError.ToolError(toolName, message) => EmbeddingError.ProviderError(s"Unexpected tool error ($toolName): $message")
