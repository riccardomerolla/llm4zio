package web.ws

import zio.json.*

// Client -> Server
enum ClientMessage derives JsonCodec:
  case Subscribe(topic: String, params: Map[String, String] = Map.empty)
  case Unsubscribe(topic: String)
  case Ping(ts: Long)
  case AbortChat(conversationId: Long)

// Server -> Client
enum ServerMessage derives JsonCodec:
  case Event(topic: String, eventType: String, payload: String, ts: Long)
  case Error(code: String, message: String, ts: Long)
  case Pong(ts: Long)
  case Subscribed(topic: String, ts: Long)
  case Unsubscribed(topic: String, ts: Long)

// Subscription topic patterns
enum SubscriptionTopic:
  case RunProgress(runId: Long)
  case DashboardRecentRuns
  case ChatMessages(conversationId: Long)
  case ChatStream(conversationId: Long)
  case ActivityFeed
  case HealthMetrics
  case AgentsActivity

object SubscriptionTopic:

  def parse(raw: String): Either[String, SubscriptionTopic] =
    raw.split(":").toList match
      case "runs" :: runId :: "progress" :: Nil  =>
        runId.toLongOption.toRight(s"Invalid runId: $runId").map(RunProgress.apply)
      case "dashboard" :: "recent-runs" :: Nil   =>
        Right(DashboardRecentRuns)
      case "chat" :: convId :: "messages" :: Nil =>
        convId.toLongOption.toRight(s"Invalid conversationId: $convId").map(ChatMessages.apply)
      case "chat" :: convId :: "stream" :: Nil   =>
        convId.toLongOption.toRight(s"Invalid conversationId: $convId").map(ChatStream.apply)
      case "activity" :: "feed" :: Nil           =>
        Right(ActivityFeed)
      case "health" :: "metrics" :: Nil          =>
        Right(HealthMetrics)
      case "agents" :: "activity" :: Nil         =>
        Right(AgentsActivity)
      case _                                     =>
        Left(s"Unknown topic: $raw")
