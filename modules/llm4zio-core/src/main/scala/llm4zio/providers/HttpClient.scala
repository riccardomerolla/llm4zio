package llm4zio.providers

import scala.annotation.unused

import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.stream.{ ZPipeline, ZStream }

import llm4zio.core.LlmError

trait HttpClient:
  def get(
    @unused url: String,
    @unused headers: Map[String, String] = Map.empty,
    @unused timeout: Duration,
  ): ZIO[Any, LlmError, String] =
    ZIO.fail(LlmError.InvalidRequestError("GET is not supported by this HttpClient implementation"))

  def postJson(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZIO[Any, LlmError, String]

  /** Generic request with an arbitrary method, content-type, and optional body. Returns the response body on any 2xx
    * (200/201/204/…), and fails with a typed [[LlmError]] otherwise — for REST APIs (e.g. Azure DevOps) that use PATCH
    * with `application/json-patch+json` and return 201 on create. The default impl is unsupported, so existing mock
    * clients need not implement it.
    */
  def send(
    @unused method: String,
    @unused url: String,
    @unused body: Option[String] = None,
    @unused headers: Map[String, String] = Map.empty,
    @unused contentType: String = "application/json",
    @unused timeout: Duration,
  ): ZIO[Any, LlmError, String] =
    ZIO.fail(LlmError.InvalidRequestError("send is not supported by this HttpClient implementation"))

  def postJsonStream(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZStream[Any, LlmError, String] =
    ZStream.fromZIO(postJson(url, body, headers, timeout)).flatMap { raw =>
      ZStream.fromIterable(raw.split("\\r?\\n").toList)
    }

  /** Parse SSE (Server-Sent Events) stream: strips `data: ` prefix, skips `[DONE]` and empty lines */
  def postJsonStreamSSE(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZStream[Any, LlmError, String] =
    postJsonStream(url, body, headers, timeout)
      .filter(_.startsWith("data: "))
      .map(_.stripPrefix("data: ").trim)
      .filter(s => s.nonEmpty && s != "[DONE]")

object HttpClient:
  def get(
    url: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZIO[HttpClient, LlmError, String] =
    ZIO.serviceWithZIO[HttpClient](_.get(url, headers, timeout))

  def postJson(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZIO[HttpClient, LlmError, String] =
    ZIO.serviceWithZIO[HttpClient](_.postJson(url, body, headers, timeout))

  def send(
    method: String,
    url: String,
    body: Option[String] = None,
    headers: Map[String, String] = Map.empty,
    contentType: String = "application/json",
    timeout: Duration,
  ): ZIO[HttpClient, LlmError, String] =
    ZIO.serviceWithZIO[HttpClient](_.send(method, url, body, headers, contentType, timeout))

  def postJsonStream(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZStream[HttpClient, LlmError, String] =
    ZStream.serviceWithStream[HttpClient](_.postJsonStream(url, body, headers, timeout))

  def postJsonStreamSSE(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZStream[HttpClient, LlmError, String] =
    ZStream.serviceWithStream[HttpClient](_.postJsonStreamSSE(url, body, headers, timeout))

  val live: ZLayer[Client, Nothing, HttpClient] =
    ZLayer.fromFunction((client: Client) => fromRequestExecutor(request => client.batched(request)))

  /** zio-http client Config with the **idle timeout disabled**. zio-http's default idle timeout (50s) closes a
    * connection that has seen no traffic for that long — but a slow local model (e.g. a 20–30B model in LM Studio that
    * spends tens of seconds processing a prompt before emitting the first response byte) looks exactly like an idle
    * connection, so it gets dropped mid-generation and the request is needlessly retried. Disabling it makes the
    * per-request `timeout` (e.g. `ApiConnectorConfig.timeout`, default 300s) the single, intentional bound.
    */
  val reliableClientConfig: ZClient.Config = ZClient.Config.default.noIdleTimeout

  /** A live zio-http [[Client]] built from [[reliableClientConfig]] — use this in place of `Client.default` so slow
    * (local) backends aren't disconnected before they answer.
    */
  val reliableClient: ZLayer[Any, Throwable, Client] =
    (ZLayer.succeed(reliableClientConfig) ++ ZLayer.succeed(NettyConfig.default) ++ DnsResolver.default) >>> Client.live

  private[providers] def fromRequestExecutor(execute: Request => Task[Response]): HttpClient =
    new HttpClient {
      override def get(
        url: String,
        headers: Map[String, String],
        timeout: Duration,
      ): ZIO[Any, LlmError, String] =
        for
          urlObj       <-
            ZIO
              .fromEither(URL.decode(url).left.map(err => LlmError.InvalidRequestError(s"Invalid URL '$url': $err")))
          request       = addHeaders(Request.get(urlObj), headers)
          response     <- execute(request)
                            .timeoutFail(LlmError.TimeoutError(timeout))(timeout)
                            .mapError {
                              case llm: LlmError => llm
                              case e: Throwable  =>
                                LlmError.ProviderError(s"Provider unavailable: $url", Some(e))
                            }
          responseBody <- response.body.asString.mapError(err => LlmError.ParseError(err.getMessage, ""))
          result       <- response.status.code match
                            case 200                                     => ZIO.succeed(responseBody)
                            case 401 | 403                               => ZIO.fail(LlmError.AuthenticationError(url))
                            case 429                                     =>
                              ZIO.fail(LlmError.RateLimitError(Some(retryAfterDuration(response, timeout))))
                            case status if status >= 400 && status < 500 =>
                              ZIO.fail(LlmError.InvalidRequestError(s"HTTP $status: $responseBody"))
                            case status if status >= 500                 =>
                              ZIO.fail(LlmError.ProviderError(s"HTTP $status: $responseBody", None))
                            case status                                  =>
                              ZIO.fail(LlmError.ProviderError(s"HTTP $status: $responseBody", None))
        yield result

      override def postJson(
        url: String,
        body: String,
        headers: Map[String, String],
        timeout: Duration,
      ): ZIO[Any, LlmError, String] =
        for
          urlObj       <-
            ZIO
              .fromEither(URL.decode(url).left.map(err => LlmError.InvalidRequestError(s"Invalid URL '$url': $err")))
          request       = addHeaders(
                            Request.post(urlObj, Body.fromString(body))
                              .addHeader(Header.ContentType(MediaType.application.json)),
                            headers,
                          )
          response     <- execute(request)
                            .timeoutFail(LlmError.TimeoutError(timeout))(timeout)
                            .mapError {
                              case llm: LlmError => llm
                              case e: Throwable  =>
                                LlmError.ProviderError(s"Provider unavailable: $url", Some(e))
                            }
          responseBody <- response.body.asString.mapError(err => LlmError.ParseError(err.getMessage, ""))
          result       <- response.status.code match
                            case 200                                     => ZIO.succeed(responseBody)
                            case 401 | 403                               => ZIO.fail(LlmError.AuthenticationError(url))
                            case 429                                     =>
                              ZIO.fail(LlmError.RateLimitError(Some(retryAfterDuration(response, timeout))))
                            case status if status >= 400 && status < 500 =>
                              ZIO.fail(LlmError.InvalidRequestError(s"HTTP $status: $responseBody"))
                            case status if status >= 500                 =>
                              ZIO.fail(LlmError.ProviderError(s"HTTP $status: $responseBody", None))
                            case status                                  =>
                              ZIO.fail(LlmError.ProviderError(s"HTTP $status: $responseBody", None))
        yield result

      override def send(
        method: String,
        url: String,
        body: Option[String],
        headers: Map[String, String],
        contentType: String,
        timeout: Duration,
      ): ZIO[Any, LlmError, String] =
        for
          urlObj       <-
            ZIO
              .fromEither(URL.decode(url).left.map(err => LlmError.InvalidRequestError(s"Invalid URL '$url': $err")))
          base          = body.fold(Request(method = Method.fromString(method), url = urlObj))(b =>
                            Request(method = Method.fromString(method), url = urlObj, body = Body.fromString(b))
                          )
          // Content-type rides as a header so arbitrary types (e.g. application/json-patch+json) need no MediaType.
          request       = addHeaders(base, headers + ("Content-Type" -> contentType))
          response     <- execute(request)
                            .timeoutFail(LlmError.TimeoutError(timeout))(timeout)
                            .mapError {
                              case llm: LlmError => llm
                              case e: Throwable  =>
                                LlmError.ProviderError(s"Provider unavailable: $url", Some(e))
                            }
          responseBody <- response.body.asString.mapError(err => LlmError.ParseError(err.getMessage, ""))
          result       <- response.status.code match
                            case status if status >= 200 && status < 300 => ZIO.succeed(responseBody)
                            case 401 | 403                               => ZIO.fail(LlmError.AuthenticationError(url))
                            case 429                                     =>
                              ZIO.fail(LlmError.RateLimitError(Some(retryAfterDuration(response, timeout))))
                            case status if status >= 400 && status < 500 =>
                              ZIO.fail(LlmError.InvalidRequestError(s"HTTP $status: $responseBody"))
                            case status                                  =>
                              ZIO.fail(LlmError.ProviderError(s"HTTP $status: $responseBody", None))
        yield result

      override def postJsonStream(
        url: String,
        body: String,
        headers: Map[String, String],
        timeout: Duration,
      ): ZStream[Any, LlmError, String] =
        ZStream.unwrap {
          for
            urlObj   <- ZIO
                          .fromEither(URL.decode(url).left.map(err =>
                            LlmError.InvalidRequestError(
                              s"Invalid URL '$url': $err"
                            )
                          ))
            request   = addHeaders(
                          Request.post(urlObj, Body.fromString(body))
                            .addHeader(Header.ContentType(MediaType.application.json)),
                          headers,
                        )
            response <- execute(request)
                          .timeoutFail(LlmError.TimeoutError(timeout))(timeout)
                          .mapError {
                            case llm: LlmError => llm
                            case e: Throwable  =>
                              LlmError.ProviderError(s"Provider unavailable: $url", Some(e))
                          }
          yield response.status.code match
            case 200                                     =>
              response.body.asStream
                .via(ZPipeline.utf8Decode)
                .via(ZPipeline.splitLines)
                .mapError(err => LlmError.ProviderError(s"Failed to read streaming response from $url", Some(err)))
            case 401 | 403                               =>
              ZStream.fail(LlmError.AuthenticationError(url))
            case 429                                     =>
              ZStream.fail(LlmError.RateLimitError(Some(retryAfterDuration(response, timeout))))
            case status if status >= 400 && status < 500 =>
              ZStream.fromZIO(
                response.body.asString.mapError(err => LlmError.ParseError(err.getMessage, ""))
              ).flatMap(body => ZStream.fail(LlmError.InvalidRequestError(s"HTTP $status: $body")))
            case status if status >= 500                 =>
              ZStream.fromZIO(
                response.body.asString.mapError(err => LlmError.ParseError(err.getMessage, ""))
              ).flatMap(body => ZStream.fail(LlmError.ProviderError(s"HTTP $status: $body", None)))
            case status                                  =>
              ZStream.fromZIO(
                response.body.asString.mapError(err => LlmError.ParseError(err.getMessage, ""))
              ).flatMap(body => ZStream.fail(LlmError.ProviderError(s"HTTP $status: $body", None)))
        }
    }

  private def addHeaders(request: Request, headers: Map[String, String]): Request =
    headers.foldLeft(request) {
      case (req, (name, value)) =>
        req.addHeader(Header.Custom(name, value))
    }

  private def retryAfterDuration(response: Response, fallback: Duration): Duration =
    response.headers.headers
      .find(_.headerName.toString.equalsIgnoreCase("Retry-After"))
      .flatMap(h => scala.util.Try(h.renderedValue.toLong).toOption)
      .map(Duration.fromSeconds)
      .getOrElse(fallback)
