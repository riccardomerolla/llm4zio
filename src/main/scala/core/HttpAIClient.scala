package core

import zio.*
import zio.http.*

import models.AIError

trait HttpAIClient:
  def get(
    url: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZIO[Any, AIError, String] =
    ZIO.fail(AIError.InvalidResponse("GET is not supported by this HttpAIClient implementation"))

  def postJson(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZIO[Any, AIError, String]

object HttpAIClient:
  def get(
    url: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZIO[HttpAIClient, AIError, String] =
    ZIO.serviceWithZIO[HttpAIClient](_.get(url, headers, timeout))

  def postJson(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    timeout: Duration,
  ): ZIO[HttpAIClient, AIError, String] =
    ZIO.serviceWithZIO[HttpAIClient](_.postJson(url, body, headers, timeout))

  val live: ZLayer[Client, Nothing, HttpAIClient] =
    ZLayer.fromFunction((client: Client) => fromRequestExecutor(request => client.batched(request)))

  private[core] def fromRequestExecutor(execute: Request => Task[Response]): HttpAIClient =
    new HttpAIClient {
      override def get(
        url: String,
        headers: Map[String, String],
        timeout: Duration,
      ): ZIO[Any, AIError, String] =
        for
          urlObj       <- ZIO
                            .fromEither(URL.decode(url).left.map(err => AIError.InvalidResponse(s"Invalid URL '$url': $err")))
          request       = addHeaders(Request.get(urlObj), headers)
          response     <- execute(request)
                            .timeoutFail(AIError.Timeout(timeout))(timeout)
                            .mapError {
                              case ai: AIError  => ai
                              case e: Throwable =>
                                AIError.ProviderUnavailable(url, Option(e.getMessage).getOrElse(e.toString))
                            }
          responseBody <- response.body.asString.mapError(err => AIError.OutputReadFailed(err.getMessage))
          result       <- response.status.code match
                            case 200                                     => ZIO.succeed(responseBody)
                            case 401 | 403                               => ZIO.fail(AIError.AuthenticationFailed(url))
                            case 429                                     =>
                              ZIO.fail(AIError.RateLimitExceeded(retryAfterDuration(response, timeout)))
                            case status if status >= 400 && status < 500 =>
                              ZIO.fail(AIError.HttpError(status, responseBody))
                            case status if status >= 500                 =>
                              ZIO.fail(AIError.ProviderUnavailable(url, s"HTTP $status: $responseBody"))
                            case status                                  =>
                              ZIO.fail(AIError.HttpError(status, responseBody))
        yield result

      override def postJson(
        url: String,
        body: String,
        headers: Map[String, String],
        timeout: Duration,
      ): ZIO[Any, AIError, String] =
        for
          urlObj       <- ZIO
                            .fromEither(URL.decode(url).left.map(err => AIError.InvalidResponse(s"Invalid URL '$url': $err")))
          request       = addHeaders(
                            Request.post(urlObj, Body.fromString(body))
                              .addHeader(Header.ContentType(MediaType.application.json)),
                            headers,
                          )
          response     <- execute(request)
                            .timeoutFail(AIError.Timeout(timeout))(timeout)
                            .mapError {
                              case ai: AIError  => ai
                              case e: Throwable =>
                                AIError.ProviderUnavailable(url, Option(e.getMessage).getOrElse(e.toString))
                            }
          responseBody <- response.body.asString.mapError(err => AIError.OutputReadFailed(err.getMessage))
          result       <- response.status.code match
                            case 200                                     => ZIO.succeed(responseBody)
                            case 401 | 403                               => ZIO.fail(AIError.AuthenticationFailed(url))
                            case 429                                     =>
                              ZIO.fail(AIError.RateLimitExceeded(retryAfterDuration(response, timeout)))
                            case status if status >= 400 && status < 500 =>
                              ZIO.fail(AIError.HttpError(status, responseBody))
                            case status if status >= 500                 =>
                              ZIO.fail(AIError.ProviderUnavailable(url, s"HTTP $status: $responseBody"))
                            case status                                  =>
                              ZIO.fail(AIError.HttpError(status, responseBody))
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
