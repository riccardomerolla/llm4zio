package db

import java.time.Instant

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.gigamap.domain.GigaMapQuery
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import models.*
import store.*

final case class ChatRepositoryES(
  conversations: GigaMap[Long, ConversationRow],
  messages: GigaMap[Long, ChatMessageRow],
  sessionContexts: GigaMap[String, SessionContextRow],
  issues: GigaMap[Long, AgentIssueRow],
  assignments: GigaMap[Long, AgentAssignmentRow],
) extends ChatRepository:

  override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long] =
    for
      id <- nextId("createConversation")
      _  <- conversations.put(id, toConversationRow(id, conversation)).mapError(storeError("createConversation"))
    yield id

  override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]] =
    for
      maybeConversation <- conversations.get(id).mapError(storeError("getConversation"))
      hydrated          <- ZIO.foreach(maybeConversation) { row =>
                             fromConversationRowSafe(row, "getConversation").flatMap {
                               case Some(conversation) =>
                                 getMessages(id).map(messages => Some(conversation.copy(messages = messages)))
                               case None               => ZIO.succeed(None)
                             }
                           }
    yield hydrated.flatten

  override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]] =
    for
      rows   <- queryAll(conversations, "listConversations")
      mapped <- ZIO.foreach(rows.toList)(row => fromConversationRowSafe(row, "listConversations"))
      all     = mapped.flatten
      page    = all.sortBy(_.createdAt)(Ordering[Instant].reverse).slice(offset, offset + limit)
      filled <- ZIO.foreach(page)(conv =>
                  getMessages(conv.id.flatMap(_.toLongOption).getOrElse(0L)).map(msgs => conv.copy(messages = msgs))
                )
    yield filled

  override def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]] =
    for
      links <- listSessionContextLinks
      ids    = links
                 .filter(_.channelName == channelName.trim)
                 .flatMap(link => extractConversationId(link.contextJson))
                 .distinct
      convs <- ZIO.foreach(ids)(getConversation).map(_.flatten)
    yield convs.sortBy(_.updatedAt)(Ordering[Instant].reverse)

  override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]] =
    for
      rows   <- queryAll(conversations, "listConversationsByRun")
      mapped <- ZIO.foreach(rows.toList)(row => fromConversationRowSafe(row, "listConversationsByRun"))
      all     = mapped
                  .flatten
                  .filter(_.runId.contains(runId.toString))
                  .sortBy(_.createdAt)(Ordering[Instant].reverse)
      filled <- ZIO.foreach(all)(conv =>
                  getMessages(conv.id.flatMap(_.toLongOption).getOrElse(0L)).map(msgs => conv.copy(messages = msgs))
                )
    yield filled

  override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(conversation.id)
                    .map(_.toLongOption.getOrElse(0L))
                    .orElseFail(PersistenceError.QueryFailed("updateConversation", "Conversation ID required for update"))
      existing <- conversations.get(id).mapError(storeError("updateConversation"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("chat_conversations", id))
                    .when(existing.isEmpty)
      _        <- conversations.put(id, toConversationRow(id, conversation)).mapError(storeError("updateConversation"))
    yield ()

  override def deleteConversation(id: Long): IO[PersistenceError, Unit] =
    for
      existing    <- conversations.get(id).mapError(storeError("deleteConversation"))
      _           <- ZIO
                       .fail(PersistenceError.NotFound("chat_conversations", id))
                       .when(existing.isEmpty)
      messageRows <- queryMessagesByConversation(id, op = "deleteConversation")
      _           <-
        ZIO.foreachDiscard(messageRows)(row => messages.remove(row.id).unit.mapError(storeError("deleteConversation")))
      _           <- conversations.remove(id).unit.mapError(storeError("deleteConversation"))
    yield ()

  override def addMessage(message: ConversationEntry): IO[PersistenceError, Long] =
    for
      id <- nextId("addMessage")
      _  <- messages.put(id, toMessageRow(id, message)).mapError(storeError("addMessage"))
    yield id

  override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]] =
    queryMessagesByConversation(conversationId, op = "getMessages")
      .map(_.toList.map(fromMessageRow).sortBy(_.createdAt))

  override def getMessagesSince(conversationId: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]] =
    getMessages(conversationId).map(_.filter(msg => !msg.createdAt.isBefore(since)))

  override def createIssue(issue: AgentIssue): IO[PersistenceError, Long] =
    for
      id <- nextId("createIssue")
      _  <- issues.put(id, toIssueRow(id, issue)).mapError(storeError("createIssue"))
    yield id

  override def getIssue(id: Long): IO[PersistenceError, Option[AgentIssue]] =
    issues.get(id).map(_.map(fromIssueRow)).mapError(storeError("getIssue"))

  override def listIssues(offset: Int, limit: Int): IO[PersistenceError, List[AgentIssue]] =
    for
      rows <- queryAll(issues, "listIssues")
      all   = rows.toList.map(fromIssueRow)
      page  = all.sortBy(_.updatedAt)(Ordering[Instant].reverse).slice(offset, offset + limit)
    yield page

  override def listIssuesByRun(runId: Long): IO[PersistenceError, List[AgentIssue]] =
    issues
      .query(GigaMapQuery.ByIndex("runId", runId))
      .map(_.toList.map(fromIssueRow).sortBy(_.createdAt)(Ordering[Instant].reverse))
      .mapError(storeError("listIssuesByRun"))

  override def listIssuesByStatus(status: IssueStatus): IO[PersistenceError, List[AgentIssue]] =
    issues
      .query(GigaMapQuery.ByIndex("status", issueStatusToDb(status)))
      .map(_.toList.map(fromIssueRow).sortBy(_.createdAt)(Ordering[Instant].reverse))
      .mapError(storeError("listIssuesByStatus"))

  override def listUnassignedIssues(runId: Long): IO[PersistenceError, List[AgentIssue]] =
    listIssuesByRun(runId).map(_.filter(_.assignedAgent.isEmpty))

  override def updateIssue(issue: AgentIssue): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(issue.id)
                    .map(_.toLongOption.getOrElse(0L))
                    .orElseFail(PersistenceError.QueryFailed("updateIssue", "Issue ID required for update"))
      existing <- issues.get(id).mapError(storeError("updateIssue"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("agent_issues", id))
                    .when(existing.isEmpty)
      _        <- issues.put(id, toIssueRow(id, issue)).mapError(storeError("updateIssue"))
    yield ()

  override def assignIssueToAgent(issueId: Long, agentName: String): IO[PersistenceError, Unit] =
    for
      issue <- getIssue(issueId)
      now   <- Clock.instant
      _     <- issue match
                 case Some(existing) =>
                   updateIssue(
                     existing.copy(
                       assignedAgent = Some(agentName),
                       assignedAt = Some(now),
                       status = IssueStatus.Assigned,
                       updatedAt = now,
                     )
                   )
                 case None           => ZIO.fail(PersistenceError.NotFound("agent_issues", issueId))
    yield ()

  override def createAssignment(assignment: AgentAssignment): IO[PersistenceError, Long] =
    for
      id <- nextId("createAssignment")
      _  <- assignments.put(id, toAssignmentRow(id, assignment)).mapError(storeError("createAssignment"))
    yield id

  override def getAssignment(id: Long): IO[PersistenceError, Option[AgentAssignment]] =
    assignments.get(id).map(_.map(fromAssignmentRow)).mapError(storeError("getAssignment"))

  override def listAssignmentsByIssue(issueId: Long): IO[PersistenceError, List[AgentAssignment]] =
    assignments
      .query(GigaMapQuery.ByIndex("issueId", issueId))
      .map(_.toList.map(fromAssignmentRow).sortBy(_.assignedAt)(Ordering[Instant].reverse))
      .mapError(storeError("listAssignmentsByIssue"))

  override def updateAssignment(assignment: AgentAssignment): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(assignment.id)
                    .map(_.toLongOption.getOrElse(0L))
                    .orElseFail(PersistenceError.QueryFailed("updateAssignment", "Assignment ID required for update"))
      existing <- assignments.get(id).mapError(storeError("updateAssignment"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("agent_assignments", id))
                    .when(existing.isEmpty)
      _        <- assignments.put(id, toAssignmentRow(id, assignment)).mapError(storeError("updateAssignment"))
    yield ()

  override def upsertSessionContext(
    channelName: String,
    sessionKey: String,
    contextJson: String,
    updatedAt: Instant,
  ): IO[PersistenceError, Unit] =
    sessionContexts
      .put(
        sessionContextCompositeKey(channelName, sessionKey),
        SessionContextRow(channelName, sessionKey, contextJson, updatedAt),
      )
      .mapError(storeError("upsertSessionContext"))

  override def getSessionContext(
    channelName: String,
    sessionKey: String,
  ): IO[PersistenceError, Option[String]] =
    sessionContexts
      .get(sessionContextCompositeKey(channelName, sessionKey))
      .map(_.map(_.contextJson))
      .mapError(storeError("getSessionContext"))

  override def getSessionContextByConversation(conversationId: Long): IO[PersistenceError, Option[SessionContextLink]] =
    listSessionContextLinks.map(_.find(link => extractConversationId(link.contextJson).contains(conversationId)))

  override def getSessionContextByTaskRunId(taskRunId: Long): IO[PersistenceError, Option[SessionContextLink]] =
    listSessionContextLinks.map(_.find(link => extractRunId(link.contextJson).contains(taskRunId)))

  override def deleteSessionContext(
    channelName: String,
    sessionKey: String,
  ): IO[PersistenceError, Unit] =
    sessionContexts
      .remove(sessionContextCompositeKey(channelName, sessionKey))
      .unit
      .mapError(storeError("deleteSessionContext"))

  private def listSessionContextLinks: IO[PersistenceError, List[SessionContextLink]] =
    queryAll(
      sessionContexts,
      "listSessionContextLinks",
    ).map(_.toList.map(fromSessionContextRow).sortBy(_.updatedAt)(Ordering[Instant].reverse))

  private def queryAll[K, V](map: GigaMap[K, V], op: String): IO[PersistenceError, Chunk[V]] =
    map.query(GigaMapQuery.All()).mapError(storeError(op))

  private def queryMessagesByConversation(
    conversationId: Long,
    op: String,
  ): IO[PersistenceError, Chunk[ChatMessageRow]] =
    messages
      .query(GigaMapQuery.ByIndex("conversationId", conversationId))
      .catchSome {
        case GigaMapError.IndexNotDefined("conversationId") =>
          ZIO.logWarning("messages 'conversationId' index missing; falling back to full scan") *>
            messages.query(GigaMapQuery.All[ChatMessageRow]()).map(_.filter(_.conversationId == conversationId))
      }
      .mapError(storeError(op))

  private def nextId(op: String): IO[PersistenceError, Long] =
    ZIO
      .attempt(java.util.UUID.randomUUID().getMostSignificantBits & Long.MaxValue)
      .mapError(storeError(op))
      .flatMap(id => if id == 0L then nextId(op) else ZIO.succeed(id))

  private def toConversationRow(id: Long, conversation: ChatConversation): ConversationRow =
    ConversationRow(
      id = id,
      title = conversation.title,
      description = conversation.description,
      channelName = conversation.channel,
      status = conversation.status,
      createdAt = conversation.createdAt,
      updatedAt = conversation.updatedAt,
      runId = conversation.runId.flatMap(_.toLongOption),
      createdBy = conversation.createdBy,
    )

  private def fromConversationRow(row: ConversationRow): ChatConversation =
    ChatConversation(
      id = Some(row.id.toString),
      runId = optionalLongToString(row.runId),
      title = row.title,
      channel = sanitizeOptional(row.channelName),
      description = sanitizeOptional(row.description),
      status = row.status,
      messages = Nil,
      createdAt = row.createdAt,
      updatedAt = row.updatedAt,
      createdBy = sanitizeOptional(row.createdBy),
    )

  private def toMessageRow(id: Long, message: ConversationEntry): ChatMessageRow =
    ChatMessageRow(
      id = id,
      conversationId = message.conversationId.toLongOption.getOrElse(0L),
      sender = message.sender,
      senderType = senderTypeToDb(message.senderType),
      content = message.content,
      messageType = messageTypeToDb(message.messageType),
      metadata = message.metadata,
      createdAt = message.createdAt,
      updatedAt = message.updatedAt,
    )

  private def fromMessageRow(row: ChatMessageRow): ConversationEntry =
    ConversationEntry(
      id = Some(row.id.toString),
      conversationId = row.conversationId.toString,
      sender = row.sender,
      senderType = parseSenderType(row.senderType),
      content = row.content,
      messageType = parseMessageType(row.messageType),
      metadata = row.metadata,
      createdAt = row.createdAt,
      updatedAt = row.updatedAt,
    )

  private def toIssueRow(id: Long, issue: AgentIssue): AgentIssueRow =
    AgentIssueRow(
      id = id,
      runId = issue.runId.flatMap(_.toLongOption),
      conversationId = issue.conversationId.flatMap(_.toLongOption),
      title = issue.title,
      description = issue.description,
      issueType = issue.issueType,
      tags = issue.tags,
      preferredAgent = issue.preferredAgent,
      contextPath = issue.contextPath,
      sourceFolder = issue.sourceFolder,
      priority = issuePriorityToDb(issue.priority),
      status = issueStatusToDb(issue.status),
      assignedAgent = issue.assignedAgent,
      assignedAt = issue.assignedAt,
      completedAt = issue.completedAt,
      errorMessage = issue.errorMessage,
      resultData = issue.resultData,
      createdAt = issue.createdAt,
      updatedAt = issue.updatedAt,
    )

  private def fromIssueRow(row: AgentIssueRow): AgentIssue =
    AgentIssue(
      id = Some(row.id.toString),
      runId = row.runId.map(_.toString),
      conversationId = row.conversationId.map(_.toString),
      title = row.title,
      description = row.description,
      issueType = row.issueType,
      tags = row.tags,
      preferredAgent = row.preferredAgent,
      contextPath = row.contextPath,
      sourceFolder = row.sourceFolder,
      priority = parseIssuePriority(row.priority),
      status = parseIssueStatus(row.status),
      assignedAgent = row.assignedAgent,
      assignedAt = row.assignedAt,
      completedAt = row.completedAt,
      errorMessage = row.errorMessage,
      resultData = row.resultData,
      createdAt = row.createdAt,
      updatedAt = row.updatedAt,
    )

  private def toAssignmentRow(id: Long, assignment: AgentAssignment): AgentAssignmentRow =
    AgentAssignmentRow(
      id = id,
      issueId = assignment.issueId.toLongOption.getOrElse(0L),
      agentName = assignment.agentName,
      status = assignment.status,
      assignedAt = assignment.assignedAt,
      startedAt = assignment.startedAt,
      completedAt = assignment.completedAt,
      executionLog = assignment.executionLog,
      result = assignment.result,
    )

  private def fromAssignmentRow(row: AgentAssignmentRow): AgentAssignment =
    AgentAssignment(
      id = Some(row.id.toString),
      issueId = row.issueId.toString,
      agentName = row.agentName,
      status = row.status,
      assignedAt = row.assignedAt,
      startedAt = row.startedAt,
      completedAt = row.completedAt,
      executionLog = row.executionLog,
      result = row.result,
    )

  private def fromSessionContextRow(row: SessionContextRow): SessionContextLink =
    SessionContextLink(
      channelName = row.channelName,
      sessionKey = row.sessionKey,
      contextJson = row.contextJson,
      updatedAt = row.updatedAt,
    )

  private def sessionContextCompositeKey(channelName: String, sessionKey: String): String =
    s"$channelName:$sessionKey"

  private def extractConversationId(contextJson: String): Option[Long] =
    extractLongField(contextJson, "conversationId")

  private def extractRunId(contextJson: String): Option[Long] =
    extractLongField(contextJson, "runId")

  private def extractLongField(contextJson: String, field: String): Option[Long] =
    val marker = s"\"$field\":"
    val start  = contextJson.indexOf(marker)
    if start < 0 then None
    else
      val digits = contextJson
        .drop(start + marker.length)
        .dropWhile(_.isWhitespace)
        .takeWhile(_.isDigit)
      digits.toLongOption

  private def storeError(op: String)(throwable: Throwable): PersistenceError =
    PersistenceError.QueryFailed(op, Option(throwable.getMessage).getOrElse(throwable.toString))

  private def sanitizeOptional[A](value: Option[A]): Option[A] =
    Option(value).flatten

  private def optionalLongToString(value: Option[Long]): Option[String] =
    value.map(_.toString)

  private def fromConversationRowSafe(row: ConversationRow, op: String): UIO[Option[ChatConversation]] =
    ZIO
      .attempt(fromConversationRow(row))
      .tapError(throwable =>
        ZIO.logWarning(
          s"Skipping malformed conversation row [op=$op id=${row.id}]: ${Option(throwable.getMessage).getOrElse(throwable.toString)}"
        )
      )
      .option

  private def senderTypeToDb(value: SenderType): String = value match
    case SenderType.User      => "user"
    case SenderType.Assistant => "assistant"
    case SenderType.System    => "system"

  private def parseSenderType(value: String): SenderType = value.toLowerCase match
    case "user"      => SenderType.User
    case "assistant" => SenderType.Assistant
    case "system"    => SenderType.System
    case _           => SenderType.System

  private def messageTypeToDb(value: MessageType): String = value match
    case MessageType.Text   => "text"
    case MessageType.Code   => "code"
    case MessageType.Error  => "error"
    case MessageType.Status => "status"

  private def parseMessageType(value: String): MessageType = value.toLowerCase match
    case "text"   => MessageType.Text
    case "code"   => MessageType.Code
    case "error"  => MessageType.Error
    case "status" => MessageType.Status
    case _        => MessageType.Text

  private def issuePriorityToDb(value: IssuePriority): String = value match
    case IssuePriority.Low      => "low"
    case IssuePriority.Medium   => "medium"
    case IssuePriority.High     => "high"
    case IssuePriority.Critical => "critical"

  private def parseIssuePriority(value: String): IssuePriority = value.toLowerCase match
    case "low"      => IssuePriority.Low
    case "medium"   => IssuePriority.Medium
    case "high"     => IssuePriority.High
    case "critical" => IssuePriority.Critical
    case _          => IssuePriority.Medium

  private def issueStatusToDb(value: IssueStatus): String = value match
    case IssueStatus.Open       => "open"
    case IssueStatus.Assigned   => "assigned"
    case IssueStatus.InProgress => "in_progress"
    case IssueStatus.Completed  => "completed"
    case IssueStatus.Failed     => "failed"
    case IssueStatus.Skipped    => "skipped"

  private def parseIssueStatus(value: String): IssueStatus = value.toLowerCase match
    case "open"        => IssueStatus.Open
    case "assigned"    => IssueStatus.Assigned
    case "in_progress" => IssueStatus.InProgress
    case "completed"   => IssueStatus.Completed
    case "failed"      => IssueStatus.Failed
    case "skipped"     => IssueStatus.Skipped
    case _             => IssueStatus.Open

object ChatRepositoryES:
  val live
    : ZLayer[
      DataStoreModule.ConversationsStore & DataStoreModule.MessagesStore & DataStoreModule.SessionContextsStore &
        DataStoreModule.AgentIssuesStore & DataStoreModule.AgentAssignmentsStore,
      Nothing,
      ChatRepository,
    ] =
    ZLayer.fromZIO {
      for
        conversations   <- DataStoreModule.conversationsMap
        messages        <- DataStoreModule.messagesMap
        sessionContexts <- DataStoreModule.sessionContextsMap
        issues          <- DataStoreModule.agentIssuesMap
        assignments     <- DataStoreModule.agentAssignmentsMap
      yield ChatRepositoryES(conversations, messages, sessionContexts, issues, assignments)
    }
