package gateway.telegram

import scala.concurrent.{ ExecutionContext, Future }

import zio.*
import zio.test.*

import cats.instances.future.given
import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.methods.{ GetUpdates, Request, SendMessage }
import com.bot4s.telegram.models.{ Message, ParsedUpdate, Update }
import io.circe.parser.decode
import io.circe.{ Decoder, Encoder }

object TelegramClientSpec extends ZIOSpecDefault:

  private given ExecutionContext = ExecutionContext.global

  private def decodeEffect[A](json: String)(using Decoder[A]): IO[Throwable, A] =
    ZIO.fromEither(decode[A](json)).mapError(error => new RuntimeException(s"decode failed: $error"))

  private def requestHandler(
    sendMessageResult: => Future[Message],
    getUpdatesResult: => Future[Seq[ParsedUpdate]],
  ): RequestHandler[Future] =
    new RequestHandler[Future]:
      override def sendRequest[T <: Request](
        request: T
      )(implicit
        evidence$1: Encoder[T],
        d: Decoder[request.Response],
      ): Future[request.Response] =
        request match
          case _: SendMessage => sendMessageResult.asInstanceOf[Future[request.Response]]
          case _: GetUpdates  => getUpdatesResult.asInstanceOf[Future[request.Response]]
          case _              => Future.failed(new RuntimeException("unsupported method"))

  private val sampleMessageJson =
    """{
      | "messageId": 10,
      | "date": 1710000000,
      | "chat": {"id": 123, "type": "Private"},
      | "from": {"id": 1, "isBot": false, "firstName": "Alice"},
      | "text": "hello"
      |}""".stripMargin

  private val sampleUpdateJson =
    """{
      | "updateId": 99,
      | "message": {
      |   "messageId": 10,
      |   "date": 1710000000,
      |   "chat": {"id": 123, "type": "Private"},
      |   "text": "hello"
      | }
      |}""".stripMargin

  def spec: Spec[TestEnvironment, Any] = suite("TelegramClientSpec")(
    test("sendMessage maps bot4s message to local model") {
      for
        sampleMsg <- decodeEffect[Message](sampleMessageJson).orDie
        client     = TelegramClient.fromRequestHandler(
                       requestHandler(
                         sendMessageResult = Future.successful(sampleMsg),
                         getUpdatesResult = Future.successful(Nil),
                       )
                     )
        result    <- client.sendMessage(TelegramSendMessage(chat_id = 123L, text = "hello"))
      yield assertTrue(
        result.message_id == 10L,
        result.chat.id == 123L,
        result.from.exists(_.first_name == "Alice"),
      )
    },
    test("getUpdates maps bot4s updates to local model") {
      for
        sampleMsg         <- decodeEffect[Message](sampleMessageJson).orDie
        sampleUpdate      <- decodeEffect[Update](sampleUpdateJson).orDie
        sampleParsedUpdate = ParsedUpdate.Success(sampleUpdate)
        client             = TelegramClient.fromRequestHandler(
                               requestHandler(
                                 sendMessageResult = Future.successful(sampleMsg),
                                 getUpdatesResult = Future.successful(Seq(sampleParsedUpdate)),
                               )
                             )
        result            <- client.getUpdates()
      yield assertTrue(
        result.length == 1,
        result.head.update_id == 99L,
        result.head.message.flatMap(_.text).contains("hello"),
      )
    },
    test("rate-limit message is mapped to RateLimited") {
      val client = TelegramClient.fromRequestHandler(
        requestHandler(
          sendMessageResult = Future.failed(new RuntimeException("429 Too Many Requests")),
          getUpdatesResult = Future.successful(Nil),
        )
      )
      for
        result <- client.sendMessage(TelegramSendMessage(chat_id = 123L, text = "hello")).either
      yield assertTrue(
        result.left.exists {
          case TelegramClientError.RateLimited(_, message) => message.contains("429")
          case _                                           => false
        }
      )
    },
    test("long request maps to timeout") {
      val client = TelegramClient.fromRequestHandler(
        requestHandler(
          sendMessageResult = Future.never,
          getUpdatesResult = Future.successful(Nil),
        )
      )
      for
        fiber  <-
          client.sendMessage(TelegramSendMessage(chat_id = 123L, text = "hello"), timeout = 100.millis).either.fork
        _      <- TestClock.adjust(200.millis)
        result <- fiber.join
      yield assertTrue(
        result.left.exists {
          case TelegramClientError.Timeout(d) => d == 100.millis
          case _                              => false
        }
      )
    },
  )
