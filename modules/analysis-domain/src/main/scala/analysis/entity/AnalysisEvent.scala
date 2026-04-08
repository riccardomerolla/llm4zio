package analysis.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ AgentId, AnalysisDocId }

sealed trait AnalysisEvent derives JsonCodec, Schema:
  def docId: AnalysisDocId
  def occurredAt: Instant

object AnalysisEvent:
  final case class AnalysisCreated(
    docId: AnalysisDocId,
    workspaceId: String,
    analysisType: AnalysisType,
    content: String,
    filePath: String,
    generatedBy: AgentId,
    occurredAt: Instant,
  ) extends AnalysisEvent

  final case class AnalysisUpdated(
    docId: AnalysisDocId,
    content: String,
    updatedAt: Instant,
  ) extends AnalysisEvent:
    override def occurredAt: Instant = updatedAt

  final case class AnalysisDeleted(
    docId: AnalysisDocId,
    deletedAt: Instant,
  ) extends AnalysisEvent:
    override def occurredAt: Instant = deletedAt
