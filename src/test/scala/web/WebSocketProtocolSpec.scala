package web

import zio.json.*
import zio.test.*

import web.ws.{ ClientMessage, ServerMessage, SubscriptionTopic }

object WebSocketProtocolSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("WebSocketProtocolSpec")(
    suite("ClientMessage JSON codec")(
      test("round-trips Subscribe") {
        val msg     = ClientMessage.Subscribe("runs:1:progress", Map("key" -> "value"))
        val json    = msg.toJson
        val decoded = json.fromJson[ClientMessage]
        assertTrue(decoded == Right(msg))
      },
      test("round-trips Unsubscribe") {
        val msg     = ClientMessage.Unsubscribe("dashboard:recent-runs")
        val json    = msg.toJson
        val decoded = json.fromJson[ClientMessage]
        assertTrue(decoded == Right(msg))
      },
      test("round-trips Ping") {
        val msg     = ClientMessage.Ping(1234567890L)
        val json    = msg.toJson
        val decoded = json.fromJson[ClientMessage]
        assertTrue(decoded == Right(msg))
      },
      test("round-trips AbortChat") {
        val msg     = ClientMessage.AbortChat(42L)
        val json    = msg.toJson
        val decoded = json.fromJson[ClientMessage]
        assertTrue(decoded == Right(msg))
      },
    ),
    suite("ServerMessage JSON codec")(
      test("round-trips Event") {
        val msg     = ServerMessage.Event("runs:1:progress", "phase-progress", "<div>html</div>", 100L)
        val json    = msg.toJson
        val decoded = json.fromJson[ServerMessage]
        assertTrue(decoded == Right(msg))
      },
      test("round-trips Error") {
        val msg     = ServerMessage.Error("parse_error", "bad json", 200L)
        val json    = msg.toJson
        val decoded = json.fromJson[ServerMessage]
        assertTrue(decoded == Right(msg))
      },
      test("round-trips Pong") {
        val msg     = ServerMessage.Pong(300L)
        val json    = msg.toJson
        val decoded = json.fromJson[ServerMessage]
        assertTrue(decoded == Right(msg))
      },
      test("round-trips Subscribed") {
        val msg     = ServerMessage.Subscribed("runs:1:progress", 400L)
        val json    = msg.toJson
        val decoded = json.fromJson[ServerMessage]
        assertTrue(decoded == Right(msg))
      },
      test("round-trips Unsubscribed") {
        val msg     = ServerMessage.Unsubscribed("runs:1:progress", 500L)
        val json    = msg.toJson
        val decoded = json.fromJson[ServerMessage]
        assertTrue(decoded == Right(msg))
      },
    ),
    suite("SubscriptionTopic.parse")(
      test("parses run progress topic") {
        assertTrue(SubscriptionTopic.parse("runs:42:progress") == Right(SubscriptionTopic.RunProgress(42L)))
      },
      test("parses dashboard recent-runs topic") {
        assertTrue(SubscriptionTopic.parse("dashboard:recent-runs") == Right(SubscriptionTopic.DashboardRecentRuns))
      },
      test("parses chat messages topic") {
        assertTrue(SubscriptionTopic.parse("chat:7:messages") == Right(SubscriptionTopic.ChatMessages(7L)))
      },
      test("parses chat stream topic") {
        assertTrue(SubscriptionTopic.parse("chat:7:stream") == Right(SubscriptionTopic.ChatStream(7L)))
      },
      test("rejects invalid chat stream id") {
        assertTrue(SubscriptionTopic.parse("chat:abc:stream").isLeft)
      },
      test("rejects unknown topics") {
        assertTrue(SubscriptionTopic.parse("unknown:topic").isLeft)
      },
      test("rejects invalid run id") {
        assertTrue(SubscriptionTopic.parse("runs:abc:progress").isLeft)
      },
      test("rejects invalid conversation id") {
        assertTrue(SubscriptionTopic.parse("chat:xyz:messages").isLeft)
      },
      test("parses activity feed topic") {
        assertTrue(SubscriptionTopic.parse("activity:feed") == Right(SubscriptionTopic.ActivityFeed))
      },
      test("parses health metrics topic") {
        assertTrue(SubscriptionTopic.parse("health:metrics") == Right(SubscriptionTopic.HealthMetrics))
      },
      test("parses agents activity topic") {
        assertTrue(SubscriptionTopic.parse("agents:activity") == Right(SubscriptionTopic.AgentsActivity))
      },
      test("rejects empty string") {
        assertTrue(SubscriptionTopic.parse("").isLeft)
      },
    ),
  )
