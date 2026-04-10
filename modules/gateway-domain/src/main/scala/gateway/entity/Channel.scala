package gateway.entity

import java.time.Instant

import zio.*
import zio.json.*

enum ChannelReachability derives JsonCodec:
  case Reachable
  case Unreachable
  case Unknown

final case class ChannelStatus(
  name: String,
  reachability: ChannelReachability,
  running: Boolean,
  activeSessions: Int,
  lastMessageAt: Option[Instant] = None,
  lastMessagePreview: Option[String] = None,
  lastError: Option[String] = None,
) derives JsonCodec

trait Channel:
  def name: String
  def start: UIO[Unit]
  def stop: UIO[Unit]
  def status: UIO[ChannelStatus]
