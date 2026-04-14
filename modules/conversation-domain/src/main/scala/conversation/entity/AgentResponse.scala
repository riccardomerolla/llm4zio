package conversation.entity

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

final case class AgentResponse(
  content: String,
  concluded: Boolean,
  outcome: Option[DialogueOutcome],
) derives JsonCodec, Schema
