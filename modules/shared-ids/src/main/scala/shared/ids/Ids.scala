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

  opaque type ProjectId = String
  object ProjectId:
    def apply(value: String): ProjectId = value
    def generate: ProjectId             = randomId()

    extension (id: ProjectId)
      def value: String = id

    given JsonCodec[ProjectId] = JsonCodec.string.transform(ProjectId.apply, _.value)
    given Schema[ProjectId]    = stringSchema.transform(ProjectId.apply, _.value)

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

  opaque type GovernancePolicyId = String
  object GovernancePolicyId:
    def apply(value: String): GovernancePolicyId = value
    def generate: GovernancePolicyId             = randomId()

    extension (id: GovernancePolicyId)
      def value: String = id

    given JsonCodec[GovernancePolicyId] = JsonCodec.string.transform(GovernancePolicyId.apply, _.value)
    given Schema[GovernancePolicyId]    = stringSchema.transform(GovernancePolicyId.apply, _.value)

  opaque type SpecificationId = String
  object SpecificationId:
    def apply(value: String): SpecificationId = value
    def generate: SpecificationId             = randomId()

    extension (id: SpecificationId)
      def value: String = id

    given JsonCodec[SpecificationId] = JsonCodec.string.transform(SpecificationId.apply, _.value)
    given Schema[SpecificationId]    = stringSchema.transform(SpecificationId.apply, _.value)

  opaque type PlanId = String
  object PlanId:
    def apply(value: String): PlanId = value
    def generate: PlanId             = randomId()

    extension (id: PlanId)
      def value: String = id

    given JsonCodec[PlanId] = JsonCodec.string.transform(PlanId.apply, _.value)
    given Schema[PlanId]    = stringSchema.transform(PlanId.apply, _.value)

  opaque type DecisionId = String
  object DecisionId:
    def apply(value: String): DecisionId = value
    def generate: DecisionId             = randomId()

    extension (id: DecisionId)
      def value: String = id

    given JsonCodec[DecisionId] = JsonCodec.string.transform(DecisionId.apply, _.value)
    given Schema[DecisionId]    = stringSchema.transform(DecisionId.apply, _.value)

  opaque type DecisionLogId = String
  object DecisionLogId:
    def apply(value: String): DecisionLogId = value
    def generate: DecisionLogId             = randomId()

    extension (id: DecisionLogId)
      def value: String = id

    given JsonCodec[DecisionLogId] = JsonCodec.string.transform(DecisionLogId.apply, _.value)
    given Schema[DecisionLogId]    = stringSchema.transform(DecisionLogId.apply, _.value)

  opaque type EvolutionProposalId = String
  object EvolutionProposalId:
    def apply(value: String): EvolutionProposalId = value
    def generate: EvolutionProposalId             = randomId()

    extension (id: EvolutionProposalId)
      def value: String = id

    given JsonCodec[EvolutionProposalId] = JsonCodec.string.transform(EvolutionProposalId.apply, _.value)
    given Schema[EvolutionProposalId]    = stringSchema.transform(EvolutionProposalId.apply, _.value)

  opaque type DaemonAgentSpecId = String
  object DaemonAgentSpecId:
    def apply(value: String): DaemonAgentSpecId = value
    def generate: DaemonAgentSpecId             = randomId()

    extension (id: DaemonAgentSpecId)
      def value: String = id

    given JsonCodec[DaemonAgentSpecId] = JsonCodec.string.transform(DaemonAgentSpecId.apply, _.value)
    given Schema[DaemonAgentSpecId]    = stringSchema.transform(DaemonAgentSpecId.apply, _.value)

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

  opaque type BoardIssueId = String
  object BoardIssueId:
    private val kebabCaseRegex = "^[a-z0-9]+(?:-[a-z0-9]+)*$".r

    def apply(value: String): BoardIssueId = value

    def fromString(value: String): Either[String, BoardIssueId] =
      val normalized = value.trim.toLowerCase
      if normalized.isEmpty then Left("Board issue id cannot be empty")
      else
        kebabCaseRegex.findFirstIn(normalized) match
          case Some(_) => Right(normalized)
          case None    => Left(s"Board issue id '$value' must be kebab-case")

    extension (id: BoardIssueId)
      def value: String = id

    given JsonCodec[BoardIssueId] = JsonCodec.string.transform(BoardIssueId.apply, _.value)
    given Schema[BoardIssueId]    = stringSchema.transform(BoardIssueId.apply, _.value)

  opaque type CanvasId = String
  object CanvasId:
    def apply(value: String): CanvasId = value
    def generate: CanvasId             = randomId()

    extension (id: CanvasId)
      def value: String = id

    given JsonCodec[CanvasId] = JsonCodec.string.transform(CanvasId.apply, _.value)
    given Schema[CanvasId]    = stringSchema.transform(CanvasId.apply, _.value)

  opaque type NormProfileId = String
  object NormProfileId:
    def apply(value: String): NormProfileId = value
    def generate: NormProfileId             = randomId()

    extension (id: NormProfileId)
      def value: String = id

    given JsonCodec[NormProfileId] = JsonCodec.string.transform(NormProfileId.apply, _.value)
    given Schema[NormProfileId]    = stringSchema.transform(NormProfileId.apply, _.value)

  opaque type SafeguardProfileId = String
  object SafeguardProfileId:
    def apply(value: String): SafeguardProfileId = value
    def generate: SafeguardProfileId             = randomId()

    extension (id: SafeguardProfileId)
      def value: String = id

    given JsonCodec[SafeguardProfileId] = JsonCodec.string.transform(SafeguardProfileId.apply, _.value)
    given Schema[SafeguardProfileId]    = stringSchema.transform(SafeguardProfileId.apply, _.value)

  opaque type CheckpointId = String
  object CheckpointId:
    def apply(value: String): CheckpointId = value
    def generate: CheckpointId             = randomId()

    extension (id: CheckpointId)
      def value: String = id

    given JsonCodec[CheckpointId] = JsonCodec.string.transform(CheckpointId.apply, _.value)
    given Schema[CheckpointId]    = stringSchema.transform(CheckpointId.apply, _.value)
