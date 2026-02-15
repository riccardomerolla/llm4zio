package gateway.models

import zio.json.*

/** Channel-specific response chunking configuration.
  */
case class ChunkingConfig(
  maxChars: Option[Int],
  protectMarkdownCodeFences: Boolean = true,
) derives JsonCodec

object ChunkingConfig:
  val Telegram: ChunkingConfig = ChunkingConfig(maxChars = Some(4000))
  val Web: ChunkingConfig      = ChunkingConfig(maxChars = None)

  def forChannel(channelName: String): ChunkingConfig =
    channelName.trim.toLowerCase match
      case "telegram"              => Telegram
      case "web" | "websocket"     => Web
      case "browser" | "dashboard" => Web
      case _                       => Web
