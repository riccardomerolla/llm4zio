package gateway.entity

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

object SessionScopeStrategy:
  val selectable: List[SessionScopeStrategy] = List(
    SessionScopeStrategy.PerConversation,
    SessionScopeStrategy.PerRun,
    SessionScopeStrategy.PerUser,
    SessionScopeStrategy.PerChannel,
  )

  def fromString(raw: String): Option[SessionScopeStrategy] =
    val value = raw.trim
    value.toLowerCase match
      case "perconversation" | "per_conversation" => Some(SessionScopeStrategy.PerConversation)
      case "perrun" | "per_run"                   => Some(SessionScopeStrategy.PerRun)
      case "peruser" | "per_user"                 => Some(SessionScopeStrategy.PerUser)
      case "perchannel" | "per_channel"           => Some(SessionScopeStrategy.PerChannel)
      case _                                      =>
        if value.startsWith("Custom(") && value.endsWith(")") then
          val prefix = value.drop("Custom(".length).dropRight(1).trim
          if prefix.nonEmpty then Some(SessionScopeStrategy.Custom(prefix)) else None
        else None

case class SessionKey(
  channelName: String,
  value: String,
) derives JsonCodec:
  def asString: String = s"${channelName.trim}:${value.trim}"
