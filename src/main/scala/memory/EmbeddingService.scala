package memory

import zio.*
import zio.json.*

import llm4zio.core.LlmService

trait EmbeddingService:
  def embed(text: String): IO[Throwable, Vector[Float]]
  def embedBatch(texts: List[String]): IO[Throwable, List[Vector[Float]]]

object EmbeddingService:
  val live: ZLayer[LlmService, Nothing, EmbeddingService] =
    ZLayer.fromFunction(EmbeddingServiceLive.apply)

final case class EmbeddingServiceLive(llmService: LlmService) extends EmbeddingService:

  private val defaultDimension: Int = 1536

  override def embed(text: String): IO[Throwable, Vector[Float]] =
    for
      providerHint <- providerFromEnvironment
      model         = modelForProvider(providerHint)
      dimension     = dimensionFromEnvironment
      _            <- warnWhenUnsupported(providerHint)
      prompt        = buildPrompt(text, model, dimension)
      response     <- llmService.execute(prompt).mapError(toThrowable)
      vector       <- parseEmbedding(response.content)
    yield vector

  override def embedBatch(texts: List[String]): IO[Throwable, List[Vector[Float]]] =
    ZIO.foreach(texts)(embed)

  private def providerFromEnvironment: UIO[String] =
    ZIO.succeed {
      sys.env
        .get("MIGRATION_AI_PROVIDER")
        .orElse(sys.env.get("AI_PROVIDER"))
        .map(_.trim.toLowerCase)
        .filter(_.nonEmpty)
        .getOrElse("unknown")
    }

  private def modelForProvider(provider: String): String =
    provider match
      case p if p.contains("anthropic") => "voyage-3"
      case p if p.contains("openai")    => "text-embedding-3-small"
      case _                            => "text-embedding-3-small"

  private def dimensionFromEnvironment: Int =
    sys.env
      .get("AI_EMBEDDING_DIMENSION")
      .flatMap(_.toIntOption)
      .filter(_ > 0)
      .getOrElse(defaultDimension)

  private def warnWhenUnsupported(provider: String): UIO[Unit] =
    if provider.contains("openai") || provider.contains("anthropic") || provider == "unknown" then ZIO.unit
    else
      ZIO.logWarning(
        s"Embedding endpoint for provider '$provider' is not directly supported yet; using prompt-based fallback"
      )

  private def buildPrompt(text: String, model: String, dimension: Int): String =
    s"""Return only a JSON array of $dimension floats for embedding model '$model'.
       |No markdown, no prose, no explanation.
       |Text:
       |$text
       |""".stripMargin

  private def parseEmbedding(content: String): IO[Throwable, Vector[Float]] =
    val normalized = content.trim

    decodeVector(normalized) match
      case Right(values) => ZIO.succeed(values)
      case Left(_)       =>
        normalized.fromJson[EmbeddingEnvelope] match
          case Right(envelope) => ZIO.succeed(envelope.embedding)
          case Left(error)     =>
            ZIO.fail(new RuntimeException(s"Unable to parse embedding vector: $error"))

  private def decodeVector(raw: String): Either[String, Vector[Float]] =
    raw.fromJson[Vector[Float]] match
      case Right(values) => Right(values)
      case Left(_)       => raw.fromJson[List[Float]].map(_.toVector)

  private def toThrowable(error: Any): Throwable =
    new RuntimeException(error.toString)

  final private case class EmbeddingEnvelope(embedding: Vector[Float]) derives JsonCodec
