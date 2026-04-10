package config.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ AgentId, WorkflowId }

sealed trait SettingValue derives JsonCodec, Schema
object SettingValue:
  final case class Text(value: String)    extends SettingValue
  final case class Flag(value: Boolean)   extends SettingValue
  final case class Whole(value: Long)     extends SettingValue
  final case class Decimal(value: Double) extends SettingValue

final case class Setting(
  key: String,
  value: SettingValue,
  updatedAt: Instant,
) derives JsonCodec, Schema

final case class Workflow(
  id: WorkflowId,
  name: String,
  description: String,
  steps: List[String],
  isBuiltin: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

final case class CustomAgent(
  id: AgentId,
  name: String,
  displayName: String,
  description: String,
  systemPrompt: String,
  tags: List[String],
  enabled: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema
