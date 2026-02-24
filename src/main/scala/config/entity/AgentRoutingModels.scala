package config.entity

import zio.json.*

import shared.ids.Ids.AgentId

case class AgentChannelBinding(
  agentId: AgentId,
  channelName: String,
  accountId: Option[String] = None,
) derives JsonCodec
