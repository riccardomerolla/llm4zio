package cli.commands

import zio.*
import zio.http.*

object RemoteCommand:

  def status(gatewayUrl: String): ZIO[Client, Throwable, String] =
    for
      url      <- ZIO.fromEither(URL.decode(s"$gatewayUrl/api/health"))
                    .mapError(msg => new IllegalArgumentException(s"Invalid gateway URL: $msg"))
      response <- Client.batched(Request.get(url))
      body     <- response.body.asString
    yield
      if response.status.isSuccess then s"Gateway is healthy: $body"
      else s"Gateway returned status ${response.status.code}: $body"

  def chat(gatewayUrl: String, prompt: String): ZIO[Client, Throwable, String] =
    for
      url      <- ZIO.fromEither(URL.decode(s"$gatewayUrl/chat/new"))
                    .mapError(msg => new IllegalArgumentException(s"Invalid gateway URL: $msg"))
      formBody  = Body.fromString(
                    s"content=${java.net.URLEncoder.encode(prompt, "UTF-8")}&mode=chat",
                    java.nio.charset.Charset.forName("UTF-8"),
                  )
      request   = Request
                    .post(url, formBody)
                    .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
      response <- Client.batched(request)
      body     <- response.body.asString
    yield
      if response.status.isSuccess || response.status.code == 303 then
        response.headers
          .get(Header.Location)
          .map(loc => s"Conversation started. Follow up at: $gatewayUrl${loc.renderedValue}")
          .getOrElse(body)
      else s"Gateway returned status ${response.status.code}: $body"
