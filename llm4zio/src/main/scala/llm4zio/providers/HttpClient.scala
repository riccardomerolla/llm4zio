package llm4zio.providers

import scala.annotation.unused

import zio.*
import zio.http.*
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
