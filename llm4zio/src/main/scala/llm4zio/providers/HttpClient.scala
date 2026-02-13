package llm4zio.providers

import zio.*
import zio.http.*
import llm4zio.core.LlmError

trait HttpClient:
  def get(
    url: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZIO[Any, LlmError, String] =
    ZIO.fail(LlmError.InvalidRequestError("GET is not supported by this HttpClient implementation"))

  def postJson(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZIO[Any, LlmError, String]

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
          urlObj       <- ZIO
                            .fromEither(URL.decode(url).left.map(err => LlmError.InvalidRequestError(s"Invalid URL '$url': $err")))
          request       = addHeaders(Request.get(urlObj), headers)
          response     <- execute(request)
                            .timeoutFail(LlmError.TimeoutError(timeout))(timeout)
                            .mapError {
                              case llm: LlmError  => llm
                              case e: Throwable =>
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
          urlObj       <- ZIO
                            .fromEither(URL.decode(url).left.map(err => LlmError.InvalidRequestError(s"Invalid URL '$url': $err")))
          request       = addHeaders(
                            Request.post(urlObj, Body.fromString(body))
                              .addHeader(Header.ContentType(MediaType.application.json)),
                            headers,
                          )
          response     <- execute(request)
                            .timeoutFail(LlmError.TimeoutError(timeout))(timeout)
                            .mapError {
                              case llm: LlmError  => llm
                              case e: Throwable =>
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
