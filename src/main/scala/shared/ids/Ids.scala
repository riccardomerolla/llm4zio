package shared.ids

import java.util.UUID

import zio.json.JsonCodec
import zio.schema.Schema

object Ids:
  private def randomId(): String           = UUID.randomUUID().toString
  private val stringSchema: Schema[String] = Schema[String]

  opaque type TaskRunId = String
  object TaskRunId:
    def apply(value: String): TaskRunId = value
    def generate: TaskRunId             = randomId()

    extension (id: TaskRunId)
      def value: String = id

    given JsonCodec[TaskRunId] = JsonCodec.string.transform(TaskRunId.apply, _.value)
    given Schema[TaskRunId]    = stringSchema.transform(TaskRunId.apply, _.value)

  opaque type ConversationId = String
  object ConversationId:
    def apply(value: String): ConversationId = value
    def generate: ConversationId             = randomId()

    extension (id: ConversationId)
      def value: String = id

    given JsonCodec[ConversationId] = JsonCodec.string.transform(ConversationId.apply, _.value)
    given Schema[ConversationId]    = stringSchema.transform(ConversationId.apply, _.value)

  opaque type MessageId = String
  object MessageId:
    def apply(value: String): MessageId = value
    def generate: MessageId             = randomId()

    extension (id: MessageId)
      def value: String = id

    given JsonCodec[MessageId] = JsonCodec.string.transform(MessageId.apply, _.value)
    given Schema[MessageId]    = stringSchema.transform(MessageId.apply, _.value)

  opaque type IssueId = String
  object IssueId:
    def apply(value: String): IssueId = value
    def generate: IssueId             = randomId()

    extension (id: IssueId)
      def value: String = id

    given JsonCodec[IssueId] = JsonCodec.string.transform(IssueId.apply, _.value)
    given Schema[IssueId]    = stringSchema.transform(IssueId.apply, _.value)

  opaque type AnalysisDocId = String
  object AnalysisDocId:
    def apply(value: String): AnalysisDocId = value
    def generate: AnalysisDocId             = randomId()

    extension (id: AnalysisDocId)
      def value: String = id

    given JsonCodec[AnalysisDocId] = JsonCodec.string.transform(AnalysisDocId.apply, _.value)
    given Schema[AnalysisDocId]    = stringSchema.transform(AnalysisDocId.apply, _.value)

  opaque type AssignmentId = String
  object AssignmentId:
    def apply(value: String): AssignmentId = value
    def generate: AssignmentId             = randomId()

    extension (id: AssignmentId)
      def value: String = id

    given JsonCodec[AssignmentId] = JsonCodec.string.transform(AssignmentId.apply, _.value)
    given Schema[AssignmentId]    = stringSchema.transform(AssignmentId.apply, _.value)

  opaque type WorkflowId = String
  object WorkflowId:
    def apply(value: String): WorkflowId = value
    def generate: WorkflowId             = randomId()

    extension (id: WorkflowId)
      def value: String = id

    given JsonCodec[WorkflowId] = JsonCodec.string.transform(WorkflowId.apply, _.value)
    given Schema[WorkflowId]    = stringSchema.transform(WorkflowId.apply, _.value)

  opaque type AgentId = String
  object AgentId:
    def apply(value: String): AgentId = value
    def generate: AgentId             = randomId()

    extension (id: AgentId)
      def value: String = id

    given JsonCodec[AgentId] = JsonCodec.string.transform(AgentId.apply, _.value)
    given Schema[AgentId]    = stringSchema.transform(AgentId.apply, _.value)

  opaque type ReportId = String
  object ReportId:
    def apply(value: String): ReportId = value
    def generate: ReportId             = randomId()

    extension (id: ReportId)
      def value: String = id

    given JsonCodec[ReportId] = JsonCodec.string.transform(ReportId.apply, _.value)
    given Schema[ReportId]    = stringSchema.transform(ReportId.apply, _.value)

  opaque type ArtifactId = String
  object ArtifactId:
    def apply(value: String): ArtifactId = value
    def generate: ArtifactId             = randomId()

    extension (id: ArtifactId)
      def value: String = id

    given JsonCodec[ArtifactId] = JsonCodec.string.transform(ArtifactId.apply, _.value)
    given Schema[ArtifactId]    = stringSchema.transform(ArtifactId.apply, _.value)

  opaque type EventId = String
  object EventId:
    def apply(value: String): EventId = value
    def generate: EventId             = randomId()

    extension (id: EventId)
      def value: String = id

    given JsonCodec[EventId] = JsonCodec.string.transform(EventId.apply, _.value)
    given Schema[EventId]    = stringSchema.transform(EventId.apply, _.value)
