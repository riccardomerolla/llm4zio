package core

import zio.*

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor

/** TracingService - OpenTelemetry distributed tracing with ZIO
  *
  * Features:
  *   - Distributed tracing with OpenTelemetry
  *   - OTLP JSON export for observability
  *   - Span creation and management
  *   - Aspect-based tracing using @@ operator
  *   - Automatic trace/span ID correlation with logs
  *   - Service name and resource attributes
  */
trait TracingService:
  /** Create a root span for a top-level operation */
  def root[R, E, A](spanName: String)(effect: ZIO[R, E, A]): ZIO[R, E, A]

  /** Create a child span within the current trace */
  def span[R, E, A](spanName: String)(effect: ZIO[R, E, A]): ZIO[R, E, A]

  /** Add an attribute to the current span */
  def setAttribute(key: String, value: String): UIO[Unit]

  /** Add an event to the current span */
  def addEvent(name: String): UIO[Unit]

  /** Add an event with attributes to the current span */
  def addEvent(name: String, attributes: Map[String, String]): UIO[Unit]

  /** Record an exception in the current span */
  def recordException(throwable: Throwable): UIO[Unit]

object TracingService:
  def root[R, E, A](spanName: String)(effect: ZIO[R, E, A]): ZIO[R & TracingService, E, A] =
    ZIO.serviceWithZIO[TracingService](_.root(spanName)(effect))

  def span[R, E, A](spanName: String)(effect: ZIO[R, E, A]): ZIO[R & TracingService, E, A] =
    ZIO.serviceWithZIO[TracingService](_.span(spanName)(effect))

  def setAttribute(key: String, value: String): URIO[TracingService, Unit] =
    ZIO.serviceWithZIO[TracingService](_.setAttribute(key, value))

  def addEvent(name: String): URIO[TracingService, Unit] =
    ZIO.serviceWithZIO[TracingService](_.addEvent(name))

  def addEvent(name: String, attributes: Map[String, String]): URIO[TracingService, Unit] =
    ZIO.serviceWithZIO[TracingService](_.addEvent(name, attributes))

  def recordException(throwable: Throwable): URIO[TracingService, Unit] =
    ZIO.serviceWithZIO[TracingService](_.recordException(throwable))

  /** Live implementation with OTLP JSON logging exporter
    *
    * @param serviceName
    *   The name of the service for resource identification
    * @param serviceVersion
    *   The version of the service
    */
  def live(serviceName: String = "legacy-modernization-agent", serviceVersion: String = "1.0.0"): ZLayer[
    Any,
    Nothing,
    TracingService,
  ] =
    ZLayer.scoped {
      for
        pair          <- initializeOpenTelemetry(serviceName, serviceVersion)
        (_, tracer)    = pair
        tracingService = makeSimpleTracingService(tracer)
      yield tracingService
    }

  /** Initialize OpenTelemetry SDK with OTLP JSON exporter */
  private def initializeOpenTelemetry(serviceName: String, serviceVersion: String)
    : ZIO[Scope, Nothing, (OpenTelemetry, Tracer)] =
    ZIO.acquireRelease {
      ZIO.succeed {
        val resource = Resource
          .getDefault
          .toBuilder
          .put(AttributeKey.stringKey("service.name"), serviceName)
          .put(AttributeKey.stringKey("service.version"), serviceVersion)
          .build()

        val spanExporter = OtlpJsonLoggingSpanExporter.create()

        val tracerProvider = SdkTracerProvider
          .builder()
          .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
          .setResource(resource)
          .build()

        val sdk = OpenTelemetrySdk
          .builder()
          .setTracerProvider(tracerProvider)
          .build()

        val tracer = sdk.getTracer(serviceName, serviceVersion)
        (sdk, tracer)
      }
    } {
      case (sdk, _) =>
        ZIO.succeed(sdk.close()).ignore
    }

  private def makeSimpleTracingService(tracer: Tracer): TracingService = new TracingService:
    import io.opentelemetry.api.trace.{ Span, SpanKind, StatusCode }
    import io.opentelemetry.context.Context

    override def root[R, E, A](spanName: String)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
      ZIO.scoped {
        for
          span   <- ZIO.acquireRelease {
                      ZIO.succeed {
                        tracer
                          .spanBuilder(spanName)
                          .setSpanKind(SpanKind.INTERNAL)
                          .startSpan()
                      }
                    } { span =>
                      ZIO.succeed(span.end())
                    }
          _      <- ZIO.succeed(span.makeCurrent())
          result <- effect.tapErrorCause { cause =>
                      ZIO.succeed {
                        cause.defects.foreach { throwable =>
                          val _ = span.recordException(throwable)
                          val _ = span.setStatus(StatusCode.ERROR, throwable.getMessage)
                        }
                        if cause.failures.nonEmpty then
                          val _ = span.setStatus(StatusCode.ERROR, cause.prettyPrint)
                      }
                    }
        yield result
      }

    override def span[R, E, A](spanName: String)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
      ZIO.scoped {
        for
          currentContext <- ZIO.succeed(Context.current())
          span           <- ZIO.acquireRelease {
                              ZIO.succeed {
                                tracer
                                  .spanBuilder(spanName)
                                  .setParent(currentContext)
                                  .setSpanKind(SpanKind.INTERNAL)
                                  .startSpan()
                              }
                            } { span =>
                              ZIO.succeed(span.end())
                            }
          _              <- ZIO.succeed(span.makeCurrent())
          result         <- effect.tapErrorCause { cause =>
                              ZIO.succeed {
                                cause.defects.foreach { throwable =>
                                  val _ = span.recordException(throwable)
                                  val _ = span.setStatus(StatusCode.ERROR, throwable.getMessage)
                                }
                                if cause.failures.nonEmpty then
                                  val _ = span.setStatus(StatusCode.ERROR, cause.prettyPrint)
                              }
                            }
        yield result
      }

    override def setAttribute(key: String, value: String): UIO[Unit] =
      ZIO.succeed {
        val span = Span.current()
        val _    = span.setAttribute(key, value)
      }

    override def addEvent(name: String): UIO[Unit] =
      ZIO.succeed {
        val span = Span.current()
        val _    = span.addEvent(name)
      }

    override def addEvent(name: String, attributes: Map[String, String]): UIO[Unit] =
      ZIO.succeed {
        val span  = Span.current()
        val attrs = Attributes.builder()
        attributes.foreach { case (k, v) => attrs.put(AttributeKey.stringKey(k), v) }
        val _     = span.addEvent(name, attrs.build())
      }

    override def recordException(throwable: Throwable): UIO[Unit] =
      ZIO.succeed {
        val span = Span.current()
        val _    = span.recordException(throwable)
      }

  /** Tracing aspect that can be applied to any ZIO effect using @@ operator
    *
    * Usage:
    * {{{
    * val myEffect = ZIO.succeed(42) @@ TracingService.aspects.root("my-operation")
    * }}}
    */
  object aspects:
    def root(spanName: String): ZIOAspect[Nothing, TracingService, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, TracingService, Nothing, Any, Nothing, Any]:
        override def apply[R <: TracingService, E, A](
          zio: ZIO[R, E, A]
        )(implicit
          trace: Trace
        ): ZIO[R, E, A] =
          TracingService.root(spanName)(zio)

    def span(spanName: String): ZIOAspect[Nothing, TracingService, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, TracingService, Nothing, Any, Nothing, Any]:
        override def apply[R <: TracingService, E, A](
          zio: ZIO[R, E, A]
        )(implicit
          trace: Trace
        ): ZIO[R, E, A] =
          TracingService.span(spanName)(zio)

    def withAttribute(key: String, value: String): ZIOAspect[Nothing, TracingService, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, TracingService, Nothing, Any, Nothing, Any]:
        override def apply[R <: TracingService, E, A](
          zio: ZIO[R, E, A]
        )(implicit
          trace: Trace
        ): ZIO[R, E, A] =
          TracingService.setAttribute(key, value) *> zio
