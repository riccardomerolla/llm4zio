package gateway.entity

/** Error ADT for channel operations. Lives in the entity layer so other entity-layer services (e.g., MessageRouter
  * trait) can reference it without depending on control-layer code.
  */
enum MessageChannelError:
  case ChannelNotFound(name: String)
  case UnsupportedSession(channelName: String, sessionKey: SessionKey)
  case SessionNotConnected(channelName: String, sessionKey: SessionKey)
  case ChannelClosed(channelName: String)
  case InvalidMessage(reason: String)
