package gateway.control

import zio.*

import gateway.entity.{ MessageChannelError, NormalizedMessage }

final case class PollingBatch(messages: List[NormalizedMessage], nextOffset: Option[Long])

trait PollingCapability:
  def pollInboundBatch(
    offset: Option[Long] = None,
    limit: Int = 100,
    timeoutSeconds: Int = 30,
    timeout: Duration = 60.seconds,
  ): IO[MessageChannelError, PollingBatch]
