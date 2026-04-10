package gateway.entity

import zio.json.*

enum GatewayQueueCommand:
  case Inbound(message: NormalizedMessage)
  case Outbound(message: NormalizedMessage)

case class ChannelMetrics(
  inboundEnqueued: Long = 0L,
  outboundEnqueued: Long = 0L,
  inboundProcessed: Long = 0L,
  outboundProcessed: Long = 0L,
  failed: Long = 0L,
  lastActivityTs: Option[Long] = None,
) derives JsonCodec

case class GatewayMetricsSnapshot(
  enqueued: Long = 0L,
  processed: Long = 0L,
  failed: Long = 0L,
  chunkedMessages: Long = 0L,
  emittedChunks: Long = 0L,
  steeringForwarded: Long = 0L,
  perChannel: Map[String, ChannelMetrics] = Map.empty,
)
