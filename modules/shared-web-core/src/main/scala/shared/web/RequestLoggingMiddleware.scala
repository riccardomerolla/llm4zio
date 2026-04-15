package shared.web

import zio.*
import zio.http.*

/** Logs all HTTP error responses (4xx, 5xx) server-side, so developers don't need to check browser DevTools. Applied
  * once at the WebServer level via `routes @@ RequestLoggingMiddleware.live`.
  */
object RequestLoggingMiddleware:

  val live: Middleware[Any] =
    Middleware.intercept { (request, response) =>
      val status = response.status.code
      if status >= 400 then
        val method = request.method.name
        val path   = request.url.encode
        Unsafe.unsafe { implicit u =>
          val _ = Runtime.default.unsafe.run(
            response.body.asString.orElseSucceed("").flatMap { body =>
              val truncated = if body.length > 300 then body.take(300) + "..." else body
              if status >= 500 then ZIO.logError(s"HTTP $status $method $path — $truncated")
              else ZIO.logWarning(s"HTTP $status $method $path — $truncated")
            }
          )
        }
      response
    }
