package app.boundary

import zio.*
import zio.http.{ Client, Request, URL }
import zio.json.ast.Json

/** Live-test for the Telegram bot token entered in the onboarding wizard.
  *
  * Calls Telegram's `getMe` endpoint and reports back a short, human-readable
  * verdict that the wizard renders inline. The trait exists so the controller
  * can be tested without standing up a real HTTP client.
  */
trait TelegramTokenTester:
  def test(token: String): UIO[TelegramTokenTester.Result]

object TelegramTokenTester:
  final case class Result(ok: Boolean, message: String, detail: Option[String])

  val live: ZLayer[Client, Nothing, TelegramTokenTester] =
    ZLayer.fromFunction(Live.apply)

  final case class Live(client: Client) extends TelegramTokenTester:
    override def test(token: String): UIO[Result] =
      val urlString = s"https://api.telegram.org/bot$token/getMe"
      ZIO
        .fromEither(URL.decode(urlString))
        .mapError(err => new RuntimeException(err.getMessage))
        .flatMap(url => client.batched(Request.get(url)))
        .timeoutFail(new RuntimeException("Timed out talking to api.telegram.org"))(10.seconds)
        .flatMap(response => response.body.asString.map(body => response.status.code -> body))
        .map { case (status, body) =>
          if status >= 200 && status < 300 then
            val name = Json.decoder
              .decodeJson(body)
              .toOption
              .flatMap(_.asObject)
              .flatMap(_.get("result"))
              .flatMap(_.asObject)
              .flatMap(_.get("username"))
              .flatMap(_.asString)
            Result(
              ok = true,
              name
                .map(u => s"Connected to @$u — Telegram will accept this token.")
                .getOrElse("Token accepted by Telegram."),
              name.map(u => s"https://t.me/$u"),
            )
          else if status == 401 then
            Result(ok = false, "Telegram rejected the token (401). Double-check it.", None)
          else if status == 404 then
            Result(ok = false, "Telegram returned 404 — the bot id segment is malformed.", None)
          else Result(ok = false, s"Telegram returned HTTP $status.", Some(body.take(140)))
        }
        .catchAll(throwable =>
          ZIO.succeed(
            Result(
              ok = false,
              "Couldn't reach api.telegram.org — check your network or proxy.",
              Option(throwable.getMessage),
            )
          )
        )
