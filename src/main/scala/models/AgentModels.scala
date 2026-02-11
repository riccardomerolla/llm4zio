package models

import zio.json.*

enum AgentType derives JsonCodec:
  case BuiltIn, Custom

case class AgentInfo(
  name: String,
  displayName: String,
  description: String,
  agentType: AgentType,
  usesAI: Boolean,
  tags: List[String],
) derives JsonCodec
