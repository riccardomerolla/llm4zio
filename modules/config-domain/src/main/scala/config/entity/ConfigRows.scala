package config.entity

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

final case class SettingRow(
  key: String,
  value: String,
  updatedAt: Instant,
) derives JsonCodec

final case class WorkflowRow(
  id: Option[Long] = None,
  name: String,
  description: Option[String] = None,
  steps: String,
  isBuiltin: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

final case class CustomAgentRow(
  id: Option[Long] = None,
  name: String,
  displayName: String,
  description: Option[String] = None,
  systemPrompt: String,
  tags: Option[String] = None,
  enabled: Boolean = true,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

final case class StoredWorkflowRow(
  id: String,
  name: String,
  description: Option[String],
  stepsJson: String,
  isBuiltin: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

final case class StoredCustomAgentRow(
  id: String,
  name: String,
  displayName: String,
  description: Option[String],
  systemPrompt: String,
  tagsJson: Option[String],
  enabled: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema
