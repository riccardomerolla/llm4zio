package orchestration.control

import java.time.Instant

import zio.json.*

import shared.ids.Ids.AgentId

case class AgentWorkspace(
  agentId: AgentId,
  path: String,
  createdAt: Instant,
  sizeBytes: Long,
) derives JsonCodec
