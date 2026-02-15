package gateway.models

import zio.json.*

enum SessionScopeStrategy derives JsonCodec:
  case PerConversation
  case PerRun
  case PerUser
  case PerChannel
  case Custom(prefix: String)

  def build(channelName: String, raw: String): SessionKey =
    val normalized = raw.trim
    this match
      case SessionScopeStrategy.PerConversation => SessionKey(channelName, s"conversation:$normalized")
      case SessionScopeStrategy.PerRun          => SessionKey(channelName, s"run:$normalized")
      case SessionScopeStrategy.PerUser         => SessionKey(channelName, s"user:$normalized")
      case SessionScopeStrategy.PerChannel      => SessionKey(channelName, "channel:global")
      case SessionScopeStrategy.Custom(prefix)  => SessionKey(channelName, s"${prefix.trim}:$normalized")

case class SessionKey(
  channelName: String,
  value: String,
) derives JsonCodec:
  def asString: String = s"${channelName.trim}:${value.trim}"
