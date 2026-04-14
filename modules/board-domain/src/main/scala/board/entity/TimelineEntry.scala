package board.entity

import java.time.Instant

import shared.ids.Ids.BoardIssueId

sealed trait TimelineEntry:
  def occurredAt: Instant

object TimelineEntry:
  final case class IssueCreated(
    issueId: BoardIssueId,
    title: String,
    description: String,
    priority: IssuePriority,
    tags: List[String],
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class MovedToTodo(
    issueId: BoardIssueId,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class AgentAssigned(
    issueId: BoardIssueId,
    agentName: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class RunStarted(
    runId: String,
    branchName: String,
    conversationId: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class ChatMessages(
    runId: String,
    conversationId: String,
    messages: List[ChatMessageSummary],
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class ChatMessageSummary(
    role: String,
    contentPreview: String,
    fullContent: String,
    timestamp: Instant,
  )

  final case class RunCompleted(
    runId: String,
    summary: String,
    durationSeconds: Long,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class GitChanges(
    runId: String,
    workspaceId: String,
    branchName: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class DecisionRaised(
    decisionId: String,
    title: String,
    urgency: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class ReviewAction(
    decisionId: String,
    action: String,
    actor: String,
    summary: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class ReworkRequested(
    reworkComment: String,
    actor: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class Merged(
    branchName: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class IssueDone(
    result: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class IssueFailed(
    reason: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class AnalysisDocAttached(
    title: String,
    analysisType: String,
    content: String,
    filePath: String,
    vscodeUrl: Option[String],
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class A2ADialogueStarted(
    conversationId: String,
    participantNames: List[String],
    topic: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class A2ADialogueConcluded(
    conversationId: String,
    outcomeType: String,
    outcomeSummary: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class PlanningRecommendation(
    recommendations: List[IssueRecommendationSummary],
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class IssueRecommendationSummary(
    issueId: BoardIssueId,
    title: String,
    rank: Int,
    score: Double,
    reasoning: String,
  )

  final case class TriageCompleted(
    issueId: BoardIssueId,
    suggestedLabels: List[String],
    suggestedPriority: String,
    suggestedCapabilities: List[String],
    reasoning: String,
    occurredAt: Instant,
  ) extends TimelineEntry
