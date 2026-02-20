package db

import java.time.Instant

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.gigamap.domain.GigaMapQuery
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import io.github.riccardomerolla.zio.eclipsestore.service.{ LifecycleCommand, LifecycleStatus }
import models.*
import store.*

final case class ChatRepositoryES(
  conversations: GigaMap[Long, ConversationRow],
  messages: GigaMap[Long, ChatMessageRow],
  sessionContexts: GigaMap[String, SessionContextRow],
  issues: GigaMap[Long, AgentIssueRow],
  assignments: GigaMap[Long, AgentAssignmentRow],
  dataStore: store.DataStoreModule.DataStoreService,
) extends ChatRepository:

  override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long] =
    for
      id <- nextId
      _  <- conversations.put(id, toConversationRow(id, conversation)).mapError(storeErr("createConversation"))
      _  <- checkpoint("createConversation")
    yield id

  override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]] =
    for
      row      <- conversations.get(id).mapError(storeErr("getConversation"))
      hydrated <- ZIO.foreach(row)(r =>
                    fromConversationRow(r).flatMap(conv => getMessages(r.id).map(msgs => conv.copy(messages = msgs)))
                  )
    yield hydrated

  override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]] =
    for
      rows  <- queryAll(conversations, "listConversations")
      page   = rows.toList.sortBy(_.createdAt)(Ordering[Instant].reverse).slice(offset, offset + limit)
      convs <- ZIO.foreach(page)(r =>
                 fromConversationRow(r).flatMap(conv => getMessages(r.id).map(msgs => conv.copy(messages = msgs)))
               )
    yield convs

  override def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]] =
    for
      links <- allSessionContextLinks
      ids    = links
                 .filter(_.channelName == channelName.trim)
                 .flatMap(l => extractLongField(l.contextJson, "conversationId"))
                 .distinct
      convs <- ZIO.foreach(ids)(getConversation).map(_.flatten)
    yield convs.sortBy(_.updatedAt)(Ordering[Instant].reverse)

  override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]] =
    for
      rows  <- queryAll(conversations, "listConversationsByRun")
      page   = rows.toList.filter(_.runId.contains(runId)).sortBy(_.createdAt)(Ordering[Instant].reverse)
      convs <- ZIO.foreach(page)(r =>
                 fromConversationRow(r).flatMap(conv => getMessages(r.id).map(msgs => conv.copy(messages = msgs)))
               )
    yield convs

  override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit] =
    for
      id <- idFromModel(conversation.id, "updateConversation")
      _  <- requireExists(conversations, id, "chat_conversations", "updateConversation")
      _  <- conversations.put(id, toConversationRow(id, conversation)).mapError(storeErr("updateConversation"))
      _  <- checkpoint("updateConversation")
    yield ()

  override def deleteConversation(id: Long): IO[PersistenceError, Unit] =
    for
      _    <- requireExists(conversations, id, "chat_conversations", "deleteConversation")
      msgs <- queryMessagesByConversation(id, "deleteConversation")
      _    <- ZIO.foreachDiscard(msgs)(row => messages.remove(row.id).unit.mapError(storeErr("deleteConversation")))
      _    <- conversations.remove(id).unit.mapError(storeErr("deleteConversation"))
      _    <- checkpoint("deleteConversation")
    yield ()

  override def addMessage(message: ConversationEntry): IO[PersistenceError, Long] =
    for
      id <- nextId
      _  <- messages.put(id, toMessageRow(id, message)).mapError(storeErr("addMessage"))
      _  <- checkpoint("addMessage")
    yield id

  override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]] =
    queryMessagesByConversation(conversationId, "getMessages")
      .flatMap(rows => ZIO.foreach(rows.toList)(fromMessageRow))
      .map(_.sortBy(_.createdAt))

  override def getMessagesSince(conversationId: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]] =
    getMessages(conversationId).map(_.filter(m => !m.createdAt.isBefore(since)))

  override def createIssue(issue: AgentIssue): IO[PersistenceError, Long] =
    for
      id <- nextId
      _  <- issues.put(id, toIssueRow(id, issue)).mapError(storeErr("createIssue"))
      _  <- checkpoint("createIssue")
    yield id

  override def getIssue(id: Long): IO[PersistenceError, Option[AgentIssue]] =
    issues.get(id).mapError(storeErr("getIssue")).flatMap(ZIO.foreach(_)(fromIssueRow))

  override def listIssues(offset: Int, limit: Int): IO[PersistenceError, List[AgentIssue]] =
    queryAll(issues, "listIssues")
      .flatMap(rows => ZIO.foreach(rows.toList)(fromIssueRow))
      .map(_.sortBy(_.updatedAt)(Ordering[Instant].reverse).slice(offset, offset + limit))

  override def listIssuesByRun(runId: Long): IO[PersistenceError, List[AgentIssue]] =
    issues
      .query(GigaMapQuery.ByIndex("runId", runId))
      .catchSome {
        case GigaMapError.IndexNotDefined("runId") =>
          ZIO.logWarning("issues 'runId' index missing; falling back to full scan") *>
            issues.query(GigaMapQuery.All[AgentIssueRow]()).map(_.filter(_.runId.contains(runId)))
      }
      .mapError(storeErr("listIssuesByRun"))
      .flatMap(rows => ZIO.foreach(rows.toList)(fromIssueRow))
      .map(_.sortBy(_.createdAt)(Ordering[Instant].reverse))

  override def listIssuesByStatus(status: IssueStatus): IO[PersistenceError, List[AgentIssue]] =
    val statusStr = issueStatusToDb(status)
    issues
      .query(GigaMapQuery.ByIndex("status", statusStr))
      .catchSome {
        case GigaMapError.IndexNotDefined("status") =>
          ZIO.logWarning("issues 'status' index missing; falling back to full scan") *>
            issues.query(GigaMapQuery.All[AgentIssueRow]()).map(_.filter(_.status == statusStr))
      }
      .mapError(storeErr("listIssuesByStatus"))
      .flatMap(rows => ZIO.foreach(rows.toList)(fromIssueRow))
      .map(_.sortBy(_.createdAt)(Ordering[Instant].reverse))

  override def listUnassignedIssues(runId: Long): IO[PersistenceError, List[AgentIssue]] =
    listIssuesByRun(runId).map(_.filter(_.assignedAgent.isEmpty))

  override def updateIssue(issue: AgentIssue): IO[PersistenceError, Unit] =
    for
      id <- idFromModel(issue.id, "updateIssue")
      _  <- requireExists(issues, id, "agent_issues", "updateIssue")
      _  <- issues.put(id, toIssueRow(id, issue)).mapError(storeErr("updateIssue"))
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
      _  <- assignments.put(id, toAssignmentRow(id, assignment)).mapError(storeErr("createAssignment"))
      _  <- checkpoint("createAssignment")
    yield id

  override def getAssignment(id: Long): IO[PersistenceError, Option[AgentAssignment]] =
    assignments.get(id).mapError(storeErr("getAssignment")).flatMap(ZIO.foreach(_)(fromAssignmentRow))

  override def listAssignmentsByIssue(issueId: Long): IO[PersistenceError, List[AgentAssignment]] =
    assignments
      .query(GigaMapQuery.ByIndex("issueId", issueId))
      .catchSome {
        case GigaMapError.IndexNotDefined("issueId") =>
          ZIO.logWarning("assignments 'issueId' index missing; falling back to full scan") *>
            assignments.query(GigaMapQuery.All[AgentAssignmentRow]()).map(_.filter(_.issueId == issueId))
      }
      .mapError(storeErr("listAssignmentsByIssue"))
      .flatMap(rows => ZIO.foreach(rows.toList)(fromAssignmentRow))
      .map(_.sortBy(_.assignedAt)(Ordering[Instant].reverse))

  override def updateAssignment(assignment: AgentAssignment): IO[PersistenceError, Unit] =
    for
      id <- idFromModel(assignment.id, "updateAssignment")
      _  <- requireExists(assignments, id, "agent_assignments", "updateAssignment")
      _  <- assignments.put(id, toAssignmentRow(id, assignment)).mapError(storeErr("updateAssignment"))
      _  <- checkpoint("updateAssignment")
    yield ()

  override def upsertSessionContext(
    channelName: String,
    sessionKey: String,
    contextJson: String,
    updatedAt: Instant,
  ): IO[PersistenceError, Unit] =
    for
      _ <- sessionContexts
             .put(ctxKey(channelName, sessionKey), SessionContextRow(channelName, sessionKey, contextJson, updatedAt))
             .mapError(storeErr("upsertSessionContext"))
      _ <- checkpoint("upsertSessionContext")
    yield ()

  override def getSessionContext(channelName: String, sessionKey: String): IO[PersistenceError, Option[String]] =
    sessionContexts.get(ctxKey(
      channelName,
      sessionKey,
    )).map(_.map(_.contextJson)).mapError(storeErr("getSessionContext"))

  override def getSessionContextByConversation(conversationId: Long): IO[PersistenceError, Option[SessionContextLink]] =
    allSessionContextLinks.map(_.find(l => extractLongField(l.contextJson, "conversationId").contains(conversationId)))

  override def getSessionContextByTaskRunId(taskRunId: Long): IO[PersistenceError, Option[SessionContextLink]] =
    allSessionContextLinks.map(_.find(l => extractLongField(l.contextJson, "runId").contains(taskRunId)))

  override def deleteSessionContext(channelName: String, sessionKey: String): IO[PersistenceError, Unit] =
    sessionContexts.remove(ctxKey(channelName, sessionKey)).unit.mapError(storeErr("deleteSessionContext"))

  private def allSessionContextLinks: IO[PersistenceError, List[SessionContextLink]] =
    queryAll(sessionContexts, "allSessionContextLinks")
      .map(
        _.toList.flatMap(r =>
          // EclipseStore lazily materialises plain String fields – guard each access so
          // a single corrupt row is dropped rather than bringing down the whole query.
          try
            Some(SessionContextLink(
              channelName = Option(r.channelName).getOrElse(""),
              sessionKey = Option(r.sessionKey).getOrElse(""),
              contextJson = Option(r.contextJson).getOrElse("{}"),
              updatedAt = Option(r.updatedAt).getOrElse(Instant.EPOCH),
            ))
          catch case _: Throwable => None
        )
      )

  private def queryAll[K, V](map: GigaMap[K, V], op: String): IO[PersistenceError, Chunk[V]] =
    map.query(GigaMapQuery.All()).mapError(storeErr(op))

  private def queryMessagesByConversation(conversationId: Long, op: String)
    : IO[PersistenceError, Chunk[ChatMessageRow]] =
    messages
      .query(GigaMapQuery.ByIndex("conversationId", conversationId))
      .catchSome {
        case GigaMapError.IndexNotDefined("conversationId") =>
          ZIO.logWarning("messages 'conversationId' index missing; falling back to full scan") *>
            messages.query(GigaMapQuery.All[ChatMessageRow]()).map(_.filter(_.conversationId == conversationId))
      }
      .mapError(storeErr(op))

  private def requireExists[K, V](map: GigaMap[K, V], id: Long, table: String, op: String): IO[PersistenceError, Unit] =
    map.get(id.asInstanceOf[K]).mapError(storeErr(op)).flatMap {
      case None    => ZIO.fail(PersistenceError.NotFound(table, id))
      case Some(_) => ZIO.unit
    }

  private def idFromModel(id: Option[String], op: String): IO[PersistenceError, Long] =
    ZIO
      .fromOption(id.flatMap(_.toLongOption))
      .orElseFail(PersistenceError.QueryFailed(op, "valid numeric ID required"))

  private def nextId: IO[PersistenceError, Long] =
    ZIO
      .attempt(java.util.UUID.randomUUID().getMostSignificantBits & Long.MaxValue)
      .mapError(storeErr("nextId"))
      .flatMap(id => if id == 0L then nextId else ZIO.succeed(id))

  /** Normalise an Option that may be `Some(null)` (from EclipseStore) to `None`. */
  private def norm(opt: Option[String]): Option[String]    = opt.flatMap(Option(_)).filter(_.nonEmpty)
  private def normI(opt: Option[Instant]): Option[Instant] = opt.flatMap(v => Option(v))

  private def toConversationRow(id: Long, conversation: ChatConversation): ConversationRow =
    ConversationRow(
      id = id,
      title = Option(conversation.title).getOrElse(""),
      description = norm(conversation.description),
      channelName = norm(conversation.channel),
      status = Option(conversation.status).getOrElse("active"),
      createdAt = conversation.createdAt,
      updatedAt = conversation.updatedAt,
      runId = conversation.runId.flatMap(_.toLongOption),
      createdBy = norm(conversation.createdBy),
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

  private def fromMessageRow(r: ChatMessageRow): IO[PersistenceError, ConversationEntry] =
    ZIO
      .attempt {
        ConversationEntry(
          id = Some(r.id.toString),
          conversationId = r.conversationId.toString,
          sender =
            try Option(r.sender).getOrElse("unknown")
            catch case _: Throwable => "unknown",
          senderType =
            try parseSenderType(Option(r.senderType).getOrElse("system"))
            catch case _: Throwable => SenderType.System,
          content =
            try Option(r.content).getOrElse("")
            catch case _: Throwable => "",
          messageType =
            try parseMessageType(Option(r.messageType).getOrElse("text"))
            catch case _: Throwable => MessageType.Text,
          metadata =
            try Option(r.metadata).flatten
            catch case _: Throwable => None,
          createdAt =
            try Option(r.createdAt).getOrElse(Instant.EPOCH)
            catch case _: Throwable => Instant.EPOCH,
          updatedAt =
            try Option(r.updatedAt).getOrElse(Instant.EPOCH)
            catch case _: Throwable => Instant.EPOCH,
        )
      }
      .mapError(ex =>
        PersistenceError.QueryFailed(
          "fromMessageRow",
          s"Failed to hydrate message [id=${r.id}]: ${Option(ex.getMessage).getOrElse(ex.toString)}",
        )
      )

  private def toIssueRow(id: Long, issue: AgentIssue): AgentIssueRow =
    AgentIssueRow(
      id = id,
      runId = issue.runId.flatMap(_.toLongOption),
      conversationId = issue.conversationId.flatMap(_.toLongOption),
      title = Option(issue.title).getOrElse(""),
      description = Option(issue.description).getOrElse(""),
      issueType = Option(issue.issueType).getOrElse(""),
      tags = norm(issue.tags),
      preferredAgent = norm(issue.preferredAgent),
      contextPath = norm(issue.contextPath),
      sourceFolder = norm(issue.sourceFolder),
      priority = issuePriorityToDb(issue.priority),
      status = issueStatusToDb(issue.status),
      assignedAgent = norm(issue.assignedAgent),
      assignedAt = normI(issue.assignedAt),
      completedAt = normI(issue.completedAt),
      errorMessage = norm(issue.errorMessage),
      resultData = norm(issue.resultData),
      createdAt = issue.createdAt,
      updatedAt = issue.updatedAt,
    )

  private def fromIssueRow(r: AgentIssueRow): IO[PersistenceError, AgentIssue] =
    ZIO
      .attempt {
        AgentIssue(
          id = Some(r.id.toString),
          runId =
            try Option(r.runId).flatten.map(_.toString)
            catch case _: Throwable => None,
          conversationId =
            try Option(r.conversationId).flatten.map(_.toString)
            catch case _: Throwable => None,
          title =
            try Option(r.title).getOrElse("")
            catch case _: Throwable => "",
          description =
            try Option(r.description).getOrElse("")
            catch case _: Throwable => "",
          issueType =
            try Option(r.issueType).getOrElse("")
            catch case _: Throwable => "",
          tags =
            try Option(r.tags).flatten
            catch case _: Throwable => None,
          preferredAgent =
            try Option(r.preferredAgent).flatten
            catch case _: Throwable => None,
          contextPath =
            try Option(r.contextPath).flatten
            catch case _: Throwable => None,
          sourceFolder =
            try Option(r.sourceFolder).flatten
            catch case _: Throwable => None,
          priority =
            try parseIssuePriority(Option(r.priority).getOrElse("medium"))
            catch case _: Throwable => IssuePriority.Medium,
          status =
            try parseIssueStatus(Option(r.status).getOrElse("open"))
            catch case _: Throwable => IssueStatus.Open,
          assignedAgent =
            try Option(r.assignedAgent).flatten
            catch case _: Throwable => None,
          assignedAt =
            try Option(r.assignedAt).flatten
            catch case _: Throwable => None,
          completedAt =
            try Option(r.completedAt).flatten
            catch case _: Throwable => None,
          errorMessage =
            try Option(r.errorMessage).flatten
            catch case _: Throwable => None,
          resultData =
            try Option(r.resultData).flatten
            catch case _: Throwable => None,
          createdAt =
            try Option(r.createdAt).getOrElse(Instant.EPOCH)
            catch case _: Throwable => Instant.EPOCH,
          updatedAt =
            try Option(r.updatedAt).getOrElse(Instant.EPOCH)
            catch case _: Throwable => Instant.EPOCH,
        )
      }
      .mapError(ex =>
        PersistenceError.QueryFailed(
          "fromIssueRow",
          s"Failed to hydrate issue [id=${r.id}]: ${Option(ex.getMessage).getOrElse(ex.toString)}",
        )
      )

  private def toAssignmentRow(id: Long, assignment: AgentAssignment): AgentAssignmentRow =
    AgentAssignmentRow(
      id = id,
      issueId = assignment.issueId.toLongOption.getOrElse(0L),
      agentName = Option(assignment.agentName).getOrElse(""),
      status = Option(assignment.status).getOrElse("pending"),
      assignedAt = assignment.assignedAt,
      startedAt = normI(assignment.startedAt),
      completedAt = normI(assignment.completedAt),
      executionLog = norm(assignment.executionLog),
      result = norm(assignment.result),
    )

  private def fromAssignmentRow(r: AgentAssignmentRow): IO[PersistenceError, AgentAssignment] =
    ZIO
      .attempt {
        AgentAssignment(
          id = Some(r.id.toString),
          issueId = r.issueId.toString,
          agentName =
            try Option(r.agentName).getOrElse("")
            catch case _: Throwable => "",
          status =
            try Option(r.status).getOrElse("")
            catch case _: Throwable => "",
          assignedAt =
            try Option(r.assignedAt).getOrElse(Instant.EPOCH)
            catch case _: Throwable => Instant.EPOCH,
          startedAt =
            try Option(r.startedAt).flatten
            catch case _: Throwable => None,
          completedAt =
            try Option(r.completedAt).flatten
            catch case _: Throwable => None,
          executionLog =
            try Option(r.executionLog).flatten
            catch case _: Throwable => None,
          result =
            try Option(r.result).flatten
            catch case _: Throwable => None,
        )
      }
      .mapError(ex =>
        PersistenceError.QueryFailed(
          "fromAssignmentRow",
          s"Failed to hydrate assignment [id=${r.id}]: ${Option(ex.getMessage).getOrElse(ex.toString)}",
        )
      )

  private def checkpoint(op: String): IO[PersistenceError, Unit] =
    for
      status <- dataStore.store.maintenance(LifecycleCommand.Checkpoint).mapError(err =>
                  PersistenceError.QueryFailed(op, err.toString)
                )
      _      <- status match
                  case LifecycleStatus.Failed(msg) =>
                    ZIO.fail(PersistenceError.QueryFailed(op, s"checkpoint failed: $msg"))
                  case _                           => ZIO.unit
    yield ()

  private def storeErr(op: String)(t: Throwable): PersistenceError =
    PersistenceError.QueryFailed(op, Option(t.getMessage).getOrElse(t.toString))

  private def ctxKey(channelName: String, sessionKey: String): String = s"$channelName:$sessionKey"

  private def extractLongField(json: String, field: String): Option[Long] =
    val marker = s"\"$field\":"
    val i      = json.indexOf(marker)
    Option
      .when(i >= 0)(json.drop(i + marker.length).dropWhile(_.isWhitespace).takeWhile(_.isDigit))
      .flatMap(_.toLongOption)

  /** Convert a {@link ConversationRow} from the store into the domain model.
    *
    * EclipseStore uses lazy, on-demand deserialization: object fields are only materialised when first accessed. If a
    * field was added to the case class AFTER the row was originally persisted, EclipseStore will produce a JVM null
    * reference (or may throw internally) when the field is first read. We therefore guard every individual field access
    * so that a single bad field cannot abort the whole hydration.
    */
  private def fromConversationRow(r: ConversationRow): IO[PersistenceError, ChatConversation] =
    Clock.instant.flatMap { now =>
      ZIO
        .attempt {
          ChatConversation(
            id = Some(r.id.toString),
            runId =
              try Option(r.runId).flatten.map(_.toString)
              catch case _: Throwable => None,
            title =
              try Option(r.title).getOrElse("")
              catch case _: Throwable => "",
            channel =
              try Option(r.channelName).flatten.flatMap(Option(_))
              catch case _: Throwable => None,
            description =
              try Option(r.description).flatten.flatMap(Option(_))
              catch case _: Throwable => None,
            status =
              try Option(r.status).getOrElse("active")
              catch case _: Throwable => "active",
            messages = Nil,
            createdAt =
              try Option(r.createdAt).getOrElse(now)
              catch case _: Throwable => now,
            updatedAt =
              try Option(r.updatedAt).getOrElse(now)
              catch case _: Throwable => now,
            createdBy =
              try Option(r.createdBy).flatten.flatMap(Option(_))
              catch case _: Throwable => None,
          )
        }
        .mapError(ex =>
          PersistenceError.QueryFailed(
            "fromConversationRow",
            s"Failed to hydrate conversation [id=${r.id}]: ${Option(ex.getMessage).getOrElse(ex.toString)}",
          )
        )
    }

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
        DataStoreModule.AgentIssuesStore & DataStoreModule.AgentAssignmentsStore & DataStoreModule.DataStoreService,
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
        dataStore       <- ZIO.service[DataStoreModule.DataStoreService]
      yield ChatRepositoryES(conversations, messages, sessionContexts, issues, assignments, dataStore)
    }
