package shared.store

import io.github.riccardomerolla.zio.eclipsestore.service.EclipseStoreService

object StoreTag:
  trait Config
  trait Data

type ConfigStore = EclipseStoreService & StoreTag.Config
type DataStore   = EclipseStoreService & StoreTag.Data

final case class ConfigStoreRef(raw: EclipseStoreService)
final case class DataStoreRef(raw: EclipseStoreService)

final case class StoreConfig(
  configStorePath: String,
  dataStorePath: String,
)

opaque type TaskRunId = String
object TaskRunId:
  def apply(value: String): TaskRunId         = value
  extension (id: TaskRunId) def value: String = id

opaque type WorkflowId = String
object WorkflowId:
  def apply(value: String): WorkflowId         = value
  extension (id: WorkflowId) def value: String = id

opaque type AgentId = String
object AgentId:
  def apply(value: String): AgentId         = value
  extension (id: AgentId) def value: String = id

opaque type ConvId = String
object ConvId:
  def apply(value: String): ConvId         = value
  extension (id: ConvId) def value: String = id

opaque type MessageId = String
object MessageId:
  def apply(value: String): MessageId         = value
  extension (id: MessageId) def value: String = id

opaque type ArtifactId = String
object ArtifactId:
  def apply(value: String): ArtifactId         = value
  extension (id: ArtifactId) def value: String = id

opaque type ReportId = String
object ReportId:
  def apply(value: String): ReportId         = value
  extension (id: ReportId) def value: String = id

opaque type EventId = String
object EventId:
  def apply(value: String): EventId         = value
  extension (id: EventId) def value: String = id

opaque type MemoryId = String
object MemoryId:
  def apply(value: String): MemoryId         = value
  extension (id: MemoryId) def value: String = id
