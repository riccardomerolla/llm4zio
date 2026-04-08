package config.entity

import java.time.Instant

import zio.json.*

enum ConfigFormat derives JsonCodec:
  case Hocon
  case Json

case class ConfigDocument(
  path: String,
  format: ConfigFormat,
  content: String,
  lastModified: Instant,
) derives JsonCodec

case class ConfigValidationIssue(
  message: String,
  path: Option[String] = None,
) derives JsonCodec

case class ConfigValidationResult(
  valid: Boolean,
  issues: List[ConfigValidationIssue],
) derives JsonCodec

case class ConfigHistoryEntry(
  id: String,
  path: String,
  savedAt: Instant,
  format: ConfigFormat,
  sizeBytes: Long,
) derives JsonCodec

case class ConfigDiffRequest(
  original: String,
  updated: String,
) derives JsonCodec

case class ConfigDiffLine(
  kind: String,
  text: String,
) derives JsonCodec

case class ConfigDiffResponse(
  lines: List[ConfigDiffLine],
  added: Int,
  removed: Int,
  unchanged: Int,
) derives JsonCodec

case class ConfigContentRequest(
  content: String,
  format: ConfigFormat,
) derives JsonCodec

case class ConfigSaveResponse(
  saved: Boolean,
  reloaded: Boolean,
  message: String,
  validation: ConfigValidationResult,
  current: ConfigDocument,
) derives JsonCodec
