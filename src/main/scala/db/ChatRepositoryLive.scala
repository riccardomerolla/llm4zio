package db

import java.time.Instant

import zio.*
import zio.json.*
import zio.schema.Schema

import conversation.entity.api.*
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.service.{ LifecycleCommand, LifecycleStatus }
import issues.entity.api.{ AgentAssignment, AgentIssue, IssuePriority, IssueStatus }
import shared.store.*

final case class ChatRepositoryLive(
  dataStore: DataStoreModule.DataStoreService
) extends ChatRepository:

  private val kv = dataStore.store

  // Key helpers — prefix-based KV namespace per entity type
  private def convKey(id: String): String        = s"conv:$id"
  private def msgKey(id: String): String         = s"msg:$id"
  private def issueKey(id: String): String       = s"issue:$id"
  private def assignmentKey(id: String): String  = s"assignment:$id"
  private def sessionKey(ch: String, sk: String) = s"session:$ch:$sk"

  override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long] =
    for
      id <- nextId
      key = convKey(id.toString)
      _  <- kv.store(key, toConversationRow(id.toString, conversation)).mapError(storeErr("createConversation"))
      _  <- checkpoint("createConversation")
    yield id

  override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]] =
    for
      row      <- kv.fetch[String, ConversationRow](convKey(id.toString)).mapError(storeErr("getConversation"))
      hydrated <- ZIO.foreach(row)(r => getMessages(id).map(msgs => fromConversationRow(r).copy(messages = msgs)))
    yield hydrated

  override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]] =
    for
      rows  <- fetchAllByPrefix[ConversationRow]("conv:", "listConversations")
      page   = rows.sortBy(_.createdAt)(Ordering[Instant].reverse).slice(offset, offset + limit)
      convs <- ZIO.foreach(page)(r =>
                 ZIO.foreach(r.id.toLongOption)(id =>
                   getMessages(id).map(msgs => fromConversationRow(r).copy(messages = msgs))
                 )
               )
    yield convs.flatten

  override def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]] =
    for
      links <- allSessionContextLinks
      ids    = links
                 .filter(_.channelName == channelName.trim)
                 .flatMap(l => decodeSessionContext(l.contextJson).flatMap(_.conversationId))
                 .distinct
      convs <- ZIO.foreach(ids)(getConversation).map(_.flatten)
    yield convs.sortBy(_.updatedAt)(Ordering[Instant].reverse)

  override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]] =
    for
      rows  <- fetchAllByPrefix[ConversationRow]("conv:", "listConversationsByRun")
      page   = rows.filter(_.runId.contains(runId.toString)).sortBy(_.createdAt)(Ordering[Instant].reverse)
      convs <- ZIO.foreach(page)(r =>
                 ZIO.foreach(r.id.toLongOption)(id =>
                   getMessages(id).map(msgs => fromConversationRow(r).copy(messages = msgs))
                 )
               )
    yield convs.flatten

  override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit] =
    for
      id <- idFromModel(conversation.id, "updateConversation")
      key = convKey(id)
      _  <- requireExists[ConversationRow](key, "chat_conversations", "updateConversation")
      _  <- kv.store(key, toConversationRow(id, conversation)).mapError(storeErr("updateConversation"))
      _  <- checkpoint("updateConversation")
    yield ()

  override def deleteConversation(id: Long): IO[PersistenceError, Unit] =
    for
      _    <- requireExists[ConversationRow](convKey(id.toString), "chat_conversations", "deleteConversation")
      msgs <- fetchAllByPrefix[ChatMessageRow]("msg:", "deleteConversation")
                .map(_.filter(_.conversationId == id.toString))
      _    <- ZIO.foreachDiscard(msgs) { row =>
                kv.remove[String](msgKey(row.id)).mapError(storeErr("deleteConversation"))
              }
      _    <- kv.remove[String](convKey(id.toString)).mapError(storeErr("deleteConversation"))
      _    <- checkpoint("deleteConversation")
    yield ()

  override def addMessage(message: ConversationEntry): IO[PersistenceError, Long] =
    for
      id <- nextId
      key = msgKey(id.toString)
      _  <- kv.store(key, toMessageRow(id.toString, message)).mapError(storeErr("addMessage"))
      _  <- checkpoint("addMessage")
    yield id

  override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]] =
    fetchAllByPrefix[ChatMessageRow]("msg:", "getMessages")
      .map(_.filter(_.conversationId == conversationId.toString).map(fromMessageRow).sortBy(_.createdAt))

  override def getMessagesSince(conversationId: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]] =
    getMessages(conversationId).map(_.filter(m => !m.createdAt.isBefore(since)))

  override def createIssue(issue: AgentIssue): IO[PersistenceError, Long] =
    for
      id <- nextId
      key = issueKey(id.toString)
      _  <- kv.store(key, toIssueRow(id.toString, issue)).mapError(storeErr("createIssue"))
      _  <- checkpoint("createIssue")
    yield id

  override def getIssue(id: Long): IO[PersistenceError, Option[AgentIssue]] =
    kv.fetch[String, AgentIssueRow](issueKey(id.toString)).mapError(storeErr("getIssue")).map(_.flatMap(fromIssueRow))

  override def listIssues(offset: Int, limit: Int): IO[PersistenceError, List[AgentIssue]] =
    fetchAllByPrefix[AgentIssueRow]("issue:", "listIssues")
      .map(_.flatMap(fromIssueRow).sortBy(_.updatedAt)(Ordering[Instant].reverse).slice(offset, offset + limit))

  override def listIssuesByRun(runId: Long): IO[PersistenceError, List[AgentIssue]] =
    fetchAllByPrefix[AgentIssueRow]("issue:", "listIssuesByRun")
      .map(
        _.filter(_.runId.contains(runId.toString)).flatMap(fromIssueRow).sortBy(_.createdAt)(Ordering[Instant].reverse)
      )

  override def listIssuesByStatus(status: IssueStatus): IO[PersistenceError, List[AgentIssue]] =
    fetchAllByPrefix[AgentIssueRow]("issue:", "listIssuesByStatus")
      .map(_.filter(_.status == status.toString).flatMap(fromIssueRow).sortBy(_.createdAt)(Ordering[Instant].reverse))

  override def listUnassignedIssues(runId: Long): IO[PersistenceError, List[AgentIssue]] =
    listIssuesByRun(runId).map(_.filter(_.assignedAgent.isEmpty))

  override def updateIssue(issue: AgentIssue): IO[PersistenceError, Unit] =
    for
      id <- idFromModel(issue.id, "updateIssue")
      key = issueKey(id)
      _  <- requireExists[AgentIssueRow](key, "agent_issues", "updateIssue")
      _  <- kv.store(key, toIssueRow(id, issue)).mapError(storeErr("updateIssue"))
      _  <- checkpoint("updateIssue")
    yield ()

  override def assignIssueToAgent(issueId: Long, agentName: String): IO[PersistenceError, Unit] =
    for
      now      <- Clock.instant
      existing <- getIssue(issueId).someOrFail(PersistenceError.NotFound("agent_issues", issueId))
      _        <- updateIssue(
                    existing.copy(
                      assignedAgent = Some(agentName),
                      assignedAt = Some(now),
                      status = IssueStatus.Assigned,
                      updatedAt = now,
                    )
                  )
    yield ()

  override def createAssignment(assignment: AgentAssignment): IO[PersistenceError, Long] =
    for
      id <- nextId
      key = assignmentKey(id.toString)
      _  <- kv.store(key, toAssignmentRow(id.toString, assignment)).mapError(storeErr("createAssignment"))
      _  <- checkpoint("createAssignment")
    yield id

  override def getAssignment(id: Long): IO[PersistenceError, Option[AgentAssignment]] =
    kv.fetch[String, AgentAssignmentRow](assignmentKey(id.toString))
      .mapError(storeErr("getAssignment"))
      .map(_.map(fromAssignmentRow))

  override def listAssignmentsByIssue(issueId: Long): IO[PersistenceError, List[AgentAssignment]] =
    fetchAllByPrefix[AgentAssignmentRow]("assignment:", "listAssignmentsByIssue")
      .map(
        _.filter(_.issueId == issueId.toString).map(fromAssignmentRow).sortBy(_.assignedAt)(Ordering[Instant].reverse)
      )

  override def updateAssignment(assignment: AgentAssignment): IO[PersistenceError, Unit] =
    for
      id <- idFromModel(assignment.id, "updateAssignment")
      key = assignmentKey(id)
      _  <- requireExists[AgentAssignmentRow](key, "agent_assignments", "updateAssignment")
      _  <- kv.store(key, toAssignmentRow(id, assignment)).mapError(storeErr("updateAssignment"))
      _  <- checkpoint("updateAssignment")
    yield ()

  override def upsertSessionContext(
    channelName: String,
    sessionKey: String,
    contextJson: String,
    updatedAt: Instant,
  ): IO[PersistenceError, Unit] =
    val key = this.sessionKey(channelName, sessionKey)
    for
      _ <- kv
             .store(key, SessionContextRow(channelName, sessionKey, contextJson, updatedAt))
             .mapError(storeErr("upsertSessionContext"))
      _ <- checkpoint("upsertSessionContext")
    yield ()

  override def getSessionContext(channelName: String, sessionKey: String): IO[PersistenceError, Option[String]] =
    kv.fetch[String, SessionContextRow](this.sessionKey(channelName, sessionKey))
      .map(_.map(_.contextJson))
      .mapError(storeErr("getSessionContext"))

  override def getSessionContextByConversation(conversationId: Long): IO[PersistenceError, Option[SessionContextLink]] =
    allSessionContextLinks.map(_.find(l =>
      decodeSessionContext(l.contextJson).flatMap(_.conversationId).contains(conversationId)
    ))

  override def getSessionContextByTaskRunId(taskRunId: Long): IO[PersistenceError, Option[SessionContextLink]] =
    allSessionContextLinks.map(_.find(l => decodeSessionContext(l.contextJson).flatMap(_.runId).contains(taskRunId)))

  override def deleteSessionContext(channelName: String, sessionKey: String): IO[PersistenceError, Unit] =
    val key = this.sessionKey(channelName, sessionKey)
    kv.remove[String](key).mapError(storeErr("deleteSessionContext")) *>
      checkpoint("deleteSessionContext")

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private def allSessionContextLinks: IO[PersistenceError, List[SessionContextLink]] =
    fetchAllByPrefix[SessionContextRow]("session:", "allSessionContextLinks").map(
      _.map(r => SessionContextLink(r.channelName, r.sessionKey, r.contextJson, r.updatedAt))
    )

  /** Scan all keys with the given prefix using streamKeys and fetch each value. This is the same approach
    * ConfigRepositoryES uses — no secondary index needed.
    */
  private def fetchAllByPrefix[V](prefix: String, op: String)(using Schema[V]): IO[PersistenceError, List[V]] =
    for
      keys <- dataStore.rawStore
                .streamKeys[String]
                .filter(_.startsWith(prefix))
                .runCollect
                .map(_.toList)
                .mapError(storeErr(op))
      vals <- ZIO.foreach(keys) { key =>
                kv.fetch[String, V](key)
                  .mapError(storeErr(op))
                  .catchAllCause { cause =>
                    val reason = cause.prettyPrint
                    ZIO.logWarning(s"$op skipped unreadable row '$key': $reason").as(None)
                  }
                  .map {
                    case Some(value) => value :: Nil
                    case _           => Nil
                  }
              }
    yield vals.flatten

  private def requireExists[V](key: String, table: String, op: String)(using Schema[V]): IO[PersistenceError, Unit] =
    kv.fetch[String, V](key).mapError(storeErr(op)).flatMap {
      case None    =>
        ZIO
          .fromOption(key.drop(key.indexOf(':') + 1).toLongOption)
          .orElseFail(PersistenceError.QueryFailed(op, s"invalid numeric id key: $key"))
          .flatMap(id => ZIO.fail(PersistenceError.NotFound(table, id)))
      case Some(_) => ZIO.unit
    }

  private def idFromModel(id: Option[String], op: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(id)
      .orElseFail(PersistenceError.QueryFailed(op, "valid ID required"))

  private def nextId: IO[PersistenceError, Long] =
    ZIO
      .attempt(java.util.UUID.randomUUID().getMostSignificantBits & Long.MaxValue)
      .mapError(storeErr("nextId"))
      .flatMap(id => if id == 0L then nextId else ZIO.succeed(id))

  private def checkpoint(op: String): IO[PersistenceError, Unit] =
    for
      status <- dataStore.rawStore.maintenance(LifecycleCommand.Checkpoint).mapError(storeErr(op))
      _      <- status match
                  case LifecycleStatus.Failed(msg) =>
                    ZIO.fail(PersistenceError.QueryFailed(op, s"checkpoint failed: $msg"))
                  case _                           => ZIO.unit
    yield ()

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def storeErr(op: String)(t: Throwable): PersistenceError =
    PersistenceError.QueryFailed(op, Option(t.getMessage).getOrElse(t.toString))

  private def decodeSessionContext(json: String): Option[SessionContextFields] =
    json.fromJson[SessionContextFields].toOption

  // ---------------------------------------------------------------------------
  // Row ↔ Domain conversions
  // ---------------------------------------------------------------------------

  private def toConversationRow(id: String, c: ChatConversation): ConversationRow =
    ConversationRow(
      id = id,
      title = c.title,
      description = c.description,
      channelName = c.channel,
      status = c.status,
      createdAt = c.createdAt,
      updatedAt = c.updatedAt,
      runId = c.runId,
      createdBy = c.createdBy,
    )

  private def fromConversationRow(r: ConversationRow): ChatConversation =
    ChatConversation(
      id = Some(r.id),
      runId = r.runId,
      title = r.title,
      channel = r.channelName,
      description = r.description,
      status = r.status,
      messages = Nil,
      createdAt = r.createdAt,
      updatedAt = r.updatedAt,
      createdBy = r.createdBy,
    )

  private def toMessageRow(id: String, m: ConversationEntry): ChatMessageRow =
    ChatMessageRow(
      id = id,
      conversationId = m.conversationId,
      sender = m.sender,
      senderType = m.senderType.toString,
      content = m.content,
      messageType = m.messageType.toString,
      metadata = m.metadata,
      createdAt = m.createdAt,
      updatedAt = m.updatedAt,
    )

  private def fromMessageRow(r: ChatMessageRow): ConversationEntry =
    val senderType  = SenderType.values.find(_.toString == r.senderType) match
      case Some(value) => value
      case None        => SenderType.System
    val messageType = MessageType.values.find(_.toString == r.messageType) match
      case Some(value) => value
      case None        => MessageType.Text
    ConversationEntry(
      id = Some(r.id),
      conversationId = r.conversationId,
      sender = r.sender,
      senderType = senderType,
      content = r.content,
      messageType = messageType,
      metadata = r.metadata,
      createdAt = r.createdAt,
      updatedAt = r.updatedAt,
    )

  private def toIssueRow(id: String, i: AgentIssue): AgentIssueRow =
    AgentIssueRow(
      id = id,
      runId = i.runId,
      conversationId = i.conversationId,
      title = i.title,
      description = i.description,
      issueType = i.issueType,
      tags = i.tags,
      preferredAgent = i.preferredAgent,
      contextPath = i.contextPath,
      sourceFolder = i.sourceFolder,
      priority = i.priority.toString,
      status = i.status.toString,
      assignedAgent = i.assignedAgent,
      assignedAt = i.assignedAt,
      completedAt = i.completedAt,
      errorMessage = i.errorMessage,
      resultData = i.resultData,
      createdAt = i.createdAt,
      updatedAt = i.updatedAt,
    )

  private def fromIssueRow(r: AgentIssueRow): Option[AgentIssue] =
    for
      priority <- IssuePriority.values.find(_.toString == r.priority)
      status   <- IssueStatus.values.find(_.toString == r.status)
    yield AgentIssue(
      id = Some(r.id),
      runId = r.runId,
      conversationId = r.conversationId,
      title = r.title,
      description = r.description,
      issueType = r.issueType,
      tags = r.tags,
      preferredAgent = r.preferredAgent,
      contextPath = r.contextPath,
      sourceFolder = r.sourceFolder,
      priority = priority,
      status = status,
      assignedAgent = r.assignedAgent,
      assignedAt = r.assignedAt,
      completedAt = r.completedAt,
      errorMessage = r.errorMessage,
      resultData = r.resultData,
      createdAt = r.createdAt,
      updatedAt = r.updatedAt,
    )

  private def toAssignmentRow(id: String, a: AgentAssignment): AgentAssignmentRow =
    AgentAssignmentRow(
      id = id,
      issueId = a.issueId,
      agentName = a.agentName,
      status = a.status,
      assignedAt = a.assignedAt,
      startedAt = a.startedAt,
      completedAt = a.completedAt,
      executionLog = a.executionLog,
      result = a.result,
    )

  private def fromAssignmentRow(r: AgentAssignmentRow): AgentAssignment =
    AgentAssignment(
      id = Some(r.id),
      issueId = r.issueId,
      agentName = r.agentName,
      status = r.status,
      assignedAt = r.assignedAt,
      startedAt = r.startedAt,
      completedAt = r.completedAt,
      executionLog = r.executionLog,
      result = r.result,
    )

object ChatRepositoryLive:
  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, ChatRepository] =
    ZLayer.fromFunction(ChatRepositoryLive.apply)

final private case class SessionContextFields(
  conversationId: Option[Long] = None,
  runId: Option[Long] = None,
) derives JsonDecoder
