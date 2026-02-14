package llm4zio.observability

import zio.*
import zio.json.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

enum TraceStatus derives JsonCodec:
  case Ok, Error

case class TraceAttributes(
  provider: Option[String] = None,
  model: Option[String] = None,
  promptHash: Option[String] = None,
  promptTokens: Option[Int] = None,
  completionTokens: Option[Int] = None,
  totalTokens: Option[Int] = None,
  latencyMs: Option[Long] = None,
  costUsd: Option[Double] = None,
  success: Option[Boolean] = None,
  agentName: Option[String] = None,
  runId: Option[String] = None,
  workflowStep: Option[String] = None,
  extra: Map[String, String] = Map.empty,
) derives JsonCodec

case class TraceSpan(
  traceId: String,
  spanId: String,
  parentSpanId: Option[String],
  correlationId: String,
  name: String,
  status: TraceStatus,
  startedAt: Instant,
  endedAt: Instant,
  attributes: TraceAttributes,
  errorMessage: Option[String] = None,
) derives JsonCodec

trait TracingService:
  def withCorrelationId[R, E, A](id: Option[String])(effect: ZIO[R, E, A]): ZIO[R, E, A]
  def correlationId: UIO[String]
  def inSpan[R, E, A](name: String, attributes: TraceAttributes = TraceAttributes())(effect: ZIO[R, E, A]): ZIO[R, E, A]
  def recordedSpans: UIO[List[TraceSpan]]

object TracingService:
  def inMemory: ZIO[Scope, Nothing, TracingService] =
    for
      correlationRef <- FiberRef.make(Option.empty[String])
      spanRef <- FiberRef.make(List.empty[String])
      spans <- Ref.make(List.empty[TraceSpan])
    yield InMemoryTracingService(correlationRef, spanRef, spans)

  val inMemoryLayer: ZLayer[Any, Nothing, TracingService] = ZLayer.scoped(inMemory)

  def withCorrelationId[R, E, A](id: Option[String])(effect: ZIO[R & TracingService, E, A]): ZIO[R & TracingService, E, A] =
    ZIO.serviceWithZIO[TracingService](_.withCorrelationId(id)(effect))

  def correlationId: ZIO[TracingService, Nothing, String] =
    ZIO.serviceWithZIO[TracingService](_.correlationId)

  def inSpan[R, E, A](name: String, attributes: TraceAttributes = TraceAttributes())(
    effect: ZIO[R & TracingService, E, A]
  ): ZIO[R & TracingService, E, A] =
    ZIO.serviceWithZIO[TracingService](_.inSpan(name, attributes)(effect))

  def recordedSpans: ZIO[TracingService, Nothing, List[TraceSpan]] =
    ZIO.serviceWithZIO[TracingService](_.recordedSpans)

  def promptHash(prompt: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8))
    digest.take(8).map(b => f"${b & 0xff}%02x").mkString

private final case class InMemoryTracingService(
  correlationRef: FiberRef[Option[String]],
  spanStackRef: FiberRef[List[String]],
  spans: Ref[List[TraceSpan]],
) extends TracingService:

  override def withCorrelationId[R, E, A](id: Option[String])(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    correlationRef.locally(id.orElse(Some(randomId("corr"))))(effect)

  override def correlationId: UIO[String] =
    correlationRef.get.map(_.getOrElse(randomId("corr")))

  override def inSpan[R, E, A](name: String, attributes: TraceAttributes = TraceAttributes())(
    effect: ZIO[R, E, A]
  ): ZIO[R, E, A] =
    for
      correlation <- correlationId
      traceId = correlation
      spanId = randomId("span")
      parent <- spanStackRef.get.map(_.headOption)
      started <- Clock.instant
      result <- spanStackRef.locallyWith(spanId :: _) {
                  effect.exit
                }
      ended <- Clock.instant
      span = result match
               case Exit.Success(_)     =>
                 TraceSpan(
                   traceId = traceId,
                   spanId = spanId,
                   parentSpanId = parent,
                   correlationId = correlation,
                   name = name,
                   status = TraceStatus.Ok,
                   startedAt = started,
                   endedAt = ended,
                   attributes = attributes.copy(success = attributes.success.orElse(Some(true))),
                 )
               case Exit.Failure(cause) =>
                 TraceSpan(
                   traceId = traceId,
                   spanId = spanId,
                   parentSpanId = parent,
                   correlationId = correlation,
                   name = name,
                   status = TraceStatus.Error,
                   startedAt = started,
                   endedAt = ended,
                   attributes = attributes.copy(success = attributes.success.orElse(Some(false))),
                   errorMessage = cause.failureOption.map(_.toString).orElse(Some(cause.prettyPrint)),
                 )
      _ <- spans.update(current => (span :: current).take(5000))
      output <- result match
                  case Exit.Success(value) => ZIO.succeed(value)
                  case Exit.Failure(cause) => ZIO.failCause(cause)
    yield output

  override def recordedSpans: UIO[List[TraceSpan]] =
    spans.get.map(_.reverse)

  private def randomId(prefix: String): String =
    s"$prefix-${UUID.randomUUID().toString.replace("-", "")}".take(32)
