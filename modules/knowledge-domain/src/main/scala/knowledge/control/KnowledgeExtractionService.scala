package knowledge.control

import zio.*
import zio.json.*

import analysis.entity.AnalysisRepository
import conversation.entity.ChatRepository
import conversation.entity.api.SenderType
import issues.entity.AgentIssue
import knowledge.entity.*
import llm4zio.core.{ LlmService, Streaming }
import shared.errors.PersistenceError
import shared.ids.Ids.DecisionLogId
import workspace.entity.WorkspaceRun

trait KnowledgeExtractionService:
  def extractFromCompletedRun(
    run: WorkspaceRun,
    issue: Option[AgentIssue],
  ): IO[PersistenceError, Option[DecisionLogId]]

object KnowledgeExtractionService:

  val live
    : ZLayer[
      ChatRepository & LlmService & DecisionLogRepository & AnalysisRepository,
      Nothing,
      KnowledgeExtractionService,
    ] =
    ZLayer.fromZIO {
      for
        chatRepo    <- ZIO.service[ChatRepository]
        llmService  <- ZIO.service[LlmService]
        decisionLog <- ZIO.service[DecisionLogRepository]
        analysis    <- ZIO.service[AnalysisRepository]
      yield KnowledgeExtractionServiceLive(chatRepo, llmService, decisionLog, analysis)
    }

  final private case class ExtractionPayload(
    title: Option[String] = None,
    context: Option[String] = None,
    decisionTaken: Option[String] = None,
    rationale: Option[String] = None,
    consequences: List[String] = Nil,
    optionsConsidered: List[ExtractionOption] = Nil,
    designConstraints: List[String] = Nil,
    lessonsLearned: List[String] = Nil,
    systemUnderstanding: List[String] = Nil,
    architecturalRationales: List[String] = Nil,
  ) derives JsonCodec

  final private case class ExtractionOption(
    name: String,
    summary: String,
    pros: List[String] = Nil,
    cons: List[String] = Nil,
    selected: Boolean = false,
  ) derives JsonCodec

  final private case class KnowledgeExtractionServiceLive(
    chatRepo: ChatRepository,
    llmService: LlmService,
    decisionLogs: DecisionLogRepository,
    analysisRepository: AnalysisRepository,
  ) extends KnowledgeExtractionService:

    override def extractFromCompletedRun(
      run: WorkspaceRun,
      issue: Option[AgentIssue],
    ): IO[PersistenceError, Option[DecisionLogId]] =
      decisionLogs
        .list(DecisionLogFilter(runId = Some(run.id), limit = 1))
        .flatMap {
          case _ :: _ => ZIO.none
          case Nil    =>
            for
              now       <- Clock.instant
              messages  <- chatRepo
                             .getMessages(run.conversationId.toLongOption.getOrElse(0L))
                             .mapError(err => PersistenceError.QueryFailed("knowledgeGetMessages", err.toString))
              analysis  <- analysisRepository.listByWorkspace(run.workspaceId).orElseSucceed(Nil)
              payload   <- extractPayload(run, issue, messages, analysis).orElseSucceed(
                             fallbackPayload(run, issue, messages)
                           )
              decisionId = DecisionLogId.generate
              _         <- decisionLogs.append(
                             DecisionLogEvent.Created(
                               decisionLogId = decisionId,
                               title = payload.title.getOrElse(defaultTitle(run, issue)),
                               context = payload.context.getOrElse(defaultContext(run, issue, messages)),
                               optionsConsidered = payload.optionsConsidered.map(option =>
                                 DecisionOption(
                                   name = option.name,
                                   summary = option.summary,
                                   pros = option.pros,
                                   cons = option.cons,
                                   selected = option.selected,
                                 )
                               ),
                               decisionTaken = payload.decisionTaken.getOrElse(defaultDecision(messages)),
                               rationale = payload.rationale.getOrElse(defaultRationale(issue)),
                               consequences = payload.consequences.filter(_.trim.nonEmpty),
                               decisionDate = now,
                               decisionMaker = DecisionMaker(DecisionMakerKind.Agent, run.agentName),
                               workspaceId = Some(run.workspaceId),
                               issueIds = issue.toList.map(_.id),
                               runId = Some(run.id),
                               conversationId = Some(run.conversationId),
                               designConstraints = payload.designConstraints.filter(_.trim.nonEmpty),
                               lessonsLearned = payload.lessonsLearned.filter(_.trim.nonEmpty),
                               systemUnderstanding = payload.systemUnderstanding.filter(_.trim.nonEmpty),
                               architecturalRationales = payload.architecturalRationales.filter(_.trim.nonEmpty),
                               occurredAt = now,
                             )
                           )
            yield Some(decisionId)
        }

    private def extractPayload(
      run: WorkspaceRun,
      issue: Option[AgentIssue],
      messages: List[conversation.entity.api.ConversationEntry],
      analysisDocs: List[analysis.entity.AnalysisDoc],
    ): IO[PersistenceError, ExtractionPayload] =
      val transcript = messages
        .takeRight(80)
        .map(message => s"[${message.senderType}:${message.sender}] ${message.content}")
        .mkString("\n")
      val analyses   = analysisDocs
        .take(3)
        .map(doc => s"[${doc.analysisType}] ${doc.content.take(1200)}")
        .mkString("\n\n")
      val prompt     =
        s"""Extract structured engineering knowledge from this completed agent run.
           |Return JSON only with keys:
           |title, context, decisionTaken, rationale, consequences, optionsConsidered,
           |designConstraints, lessonsLearned, systemUnderstanding, architecturalRationales.
           |
           |Issue title: ${issue.map(_.title).getOrElse(run.issueRef)}
           |Issue description: ${issue.map(_.description).getOrElse("")}
           |Workspace: ${run.workspaceId}
           |Branch: ${run.branchName}
           |
           |Conversation:
           |$transcript
           |
           |Relevant analysis:
           |$analyses
           |""".stripMargin
      Streaming
        .collect(llmService.executeStream(prompt))
        .mapError(err => PersistenceError.QueryFailed("knowledgeExtract", err.toString))
        .flatMap(response =>
          ZIO
            .fromEither(response.content.fromJson[ExtractionPayload])
            .mapError(err => PersistenceError.SerializationFailed("knowledgeExtract", err))
        )

    private def fallbackPayload(
      run: WorkspaceRun,
      issue: Option[AgentIssue],
      messages: List[conversation.entity.api.ConversationEntry],
    ): ExtractionPayload =
      val assistantMessages =
        messages.filter(_.senderType == SenderType.Assistant).map(_.content.trim).filter(_.nonEmpty)
      val finalAssistant    =
        assistantMessages.lastOption.getOrElse("Completed implementation work in the assigned workspace.")
      val selectedOption    = ExtractionOption(
        name = "Implemented approach",
        summary = finalAssistant.take(400),
        selected = true,
      )
      ExtractionPayload(
        title = Some(defaultTitle(run, issue)),
        context = Some(defaultContext(run, issue, messages)),
        decisionTaken = Some(finalAssistant.take(600)),
        rationale = Some(defaultRationale(issue)),
        optionsConsidered = List(selectedOption),
        architecturalRationales = List(defaultRationale(issue)),
      )

    private def defaultTitle(run: WorkspaceRun, issue: Option[AgentIssue]): String =
      issue.map(_.title).filter(_.trim.nonEmpty).getOrElse(s"Decision for ${run.issueRef}")

    private def defaultContext(
      run: WorkspaceRun,
      issue: Option[AgentIssue],
      messages: List[conversation.entity.api.ConversationEntry],
    ): String =
      val promptSummary = messages.headOption.map(_.content.take(500)).getOrElse(run.prompt.take(500))
      s"${issue.map(_.description).filter(_.trim.nonEmpty).getOrElse("Completed implementation run.")}\n\nPrompt:\n$promptSummary".trim

    private def defaultDecision(messages: List[conversation.entity.api.ConversationEntry]): String =
      messages
        .reverseIterator
        .find(message => message.senderType == SenderType.Assistant && message.content.trim.nonEmpty)
        .map(_.content.take(600))
        .getOrElse("The agent completed the assigned implementation and delivered the resulting workspace changes.")

    private def defaultRationale(issue: Option[AgentIssue]): String =
      issue
        .flatMap(_.acceptanceCriteria.filter(_.trim.nonEmpty))
        .map(criteria => s"Chosen to satisfy the documented acceptance criteria.\n\n$criteria")
        .getOrElse("Chosen to satisfy the assigned issue requirements and keep the workspace consistent.")
