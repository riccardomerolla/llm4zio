package db

import java.sql.{ Connection, ResultSet, Statement, Types }
import java.time.Instant
import javax.sql.DataSource

import zio.*

import models.*

trait ChatRepository:
  // Chat Conversations
  def createConversation(conversation: ChatConversation): IO[PersistenceError, Long]
  def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]]
  def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]]
  def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]]
  def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit]
  def deleteConversation(id: Long): IO[PersistenceError, Unit]

  // Chat Messages
  def addMessage(message: ConversationMessage): IO[PersistenceError, Long]
  def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationMessage]]
  def getMessagesSince(conversationId: Long, since: Instant): IO[PersistenceError, List[ConversationMessage]]

  // Agent Issues
  def createIssue(issue: AgentIssue): IO[PersistenceError, Long]
  def getIssue(id: Long): IO[PersistenceError, Option[AgentIssue]]
  def listIssues(offset: Int, limit: Int): IO[PersistenceError, List[AgentIssue]]
  def listIssuesByRun(runId: Long): IO[PersistenceError, List[AgentIssue]]
  def listIssuesByStatus(status: IssueStatus): IO[PersistenceError, List[AgentIssue]]
  def listUnassignedIssues(runId: Long): IO[PersistenceError, List[AgentIssue]]
  def updateIssue(issue: AgentIssue): IO[PersistenceError, Unit]
  def assignIssueToAgent(issueId: Long, agentName: String): IO[PersistenceError, Unit]

  // Agent Assignments
  def createAssignment(assignment: AgentAssignment): IO[PersistenceError, Long]
  def getAssignment(id: Long): IO[PersistenceError, Option[AgentAssignment]]
  def listAssignmentsByIssue(issueId: Long): IO[PersistenceError, List[AgentAssignment]]
  def updateAssignment(assignment: AgentAssignment): IO[PersistenceError, Unit]

  // Channel session context storage (gateway integration)
  def upsertSessionContext(
    channelName: String,
    sessionKey: String,
    contextJson: String,
    updatedAt: Instant,
  ): IO[PersistenceError, Unit] =
    ZIO.fail(PersistenceError.QueryFailed("upsertSessionContext", "Not implemented"))

  def getSessionContext(
    channelName: String,
    sessionKey: String,
  ): IO[PersistenceError, Option[String]] =
    ZIO.fail(PersistenceError.QueryFailed("getSessionContext", "Not implemented"))

  def deleteSessionContext(
    channelName: String,
    sessionKey: String,
  ): IO[PersistenceError, Unit] =
    ZIO.fail(PersistenceError.QueryFailed("deleteSessionContext", "Not implemented"))

object ChatRepository:
  def createConversation(conversation: ChatConversation): ZIO[ChatRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ChatRepository](_.createConversation(conversation))

  def getConversation(id: Long): ZIO[ChatRepository, PersistenceError, Option[ChatConversation]] =
    ZIO.serviceWithZIO[ChatRepository](_.getConversation(id))

  def listConversations(offset: Int, limit: Int): ZIO[ChatRepository, PersistenceError, List[ChatConversation]] =
    ZIO.serviceWithZIO[ChatRepository](_.listConversations(offset, limit))

  def listConversationsByRun(runId: Long): ZIO[ChatRepository, PersistenceError, List[ChatConversation]] =
    ZIO.serviceWithZIO[ChatRepository](_.listConversationsByRun(runId))

  def updateConversation(conversation: ChatConversation): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.updateConversation(conversation))

  def deleteConversation(id: Long): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.deleteConversation(id))

  def addMessage(message: ConversationMessage): ZIO[ChatRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ChatRepository](_.addMessage(message))

  def getMessages(conversationId: Long): ZIO[ChatRepository, PersistenceError, List[ConversationMessage]] =
    ZIO.serviceWithZIO[ChatRepository](_.getMessages(conversationId))

  def getMessagesSince(conversationId: Long, since: Instant)
    : ZIO[ChatRepository, PersistenceError, List[ConversationMessage]] =
    ZIO.serviceWithZIO[ChatRepository](_.getMessagesSince(conversationId, since))

  def createIssue(issue: AgentIssue): ZIO[ChatRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ChatRepository](_.createIssue(issue))

  def getIssue(id: Long): ZIO[ChatRepository, PersistenceError, Option[AgentIssue]] =
    ZIO.serviceWithZIO[ChatRepository](_.getIssue(id))

  def listIssues(offset: Int, limit: Int): ZIO[ChatRepository, PersistenceError, List[AgentIssue]] =
    ZIO.serviceWithZIO[ChatRepository](_.listIssues(offset, limit))

  def listIssuesByRun(runId: Long): ZIO[ChatRepository, PersistenceError, List[AgentIssue]] =
    ZIO.serviceWithZIO[ChatRepository](_.listIssuesByRun(runId))

  def listIssuesByStatus(status: IssueStatus): ZIO[ChatRepository, PersistenceError, List[AgentIssue]] =
    ZIO.serviceWithZIO[ChatRepository](_.listIssuesByStatus(status))

  def listUnassignedIssues(runId: Long): ZIO[ChatRepository, PersistenceError, List[AgentIssue]] =
    ZIO.serviceWithZIO[ChatRepository](_.listUnassignedIssues(runId))

  def updateIssue(issue: AgentIssue): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.updateIssue(issue))

  def assignIssueToAgent(issueId: Long, agentName: String): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.assignIssueToAgent(issueId, agentName))

  def createAssignment(assignment: AgentAssignment): ZIO[ChatRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ChatRepository](_.createAssignment(assignment))

  def getAssignment(id: Long): ZIO[ChatRepository, PersistenceError, Option[AgentAssignment]] =
    ZIO.serviceWithZIO[ChatRepository](_.getAssignment(id))

  def listAssignmentsByIssue(issueId: Long): ZIO[ChatRepository, PersistenceError, List[AgentAssignment]] =
    ZIO.serviceWithZIO[ChatRepository](_.listAssignmentsByIssue(issueId))

  def updateAssignment(assignment: AgentAssignment): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.updateAssignment(assignment))

  def upsertSessionContext(
    channelName: String,
    sessionKey: String,
    contextJson: String,
    updatedAt: Instant,
  ): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.upsertSessionContext(channelName, sessionKey, contextJson, updatedAt))

  def getSessionContext(
    channelName: String,
    sessionKey: String,
  ): ZIO[ChatRepository, PersistenceError, Option[String]] =
    ZIO.serviceWithZIO[ChatRepository](_.getSessionContext(channelName, sessionKey))

  def deleteSessionContext(
    channelName: String,
    sessionKey: String,
  ): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.deleteSessionContext(channelName, sessionKey))

  val live: ZLayer[DataSource, PersistenceError, ChatRepository] =
    ZLayer.fromZIO {
      for
        dataSource  <- ZIO.service[DataSource]
        initialized <- Ref.Synchronized.make(false)
      yield ChatRepositoryLive(dataSource, initialized)
    }

final case class ChatRepositoryLive(
  ds: DataSource,
  initialized: Ref.Synchronized[Boolean],
) extends ChatRepository:

  import db.ChatRepositoryLive.*

  override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO chat_conversations (run_id, title, description, status, created_at, updated_at, created_by)
        |VALUES (?, ?, ?, ?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "chat_conversations") { stmt =>
        setOptionalLong(stmt, 1, conversation.runId)
        stmt.setString(2, conversation.title)
        setOptionalString(stmt, 3, conversation.description)
        stmt.setString(4, conversation.status)
        stmt.setString(5, conversation.createdAt.toString)
        stmt.setString(6, conversation.updatedAt.toString)
        setOptionalString(stmt, 7, conversation.createdBy)
      }
    }

  override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]] =
    val sql =
      """SELECT id, run_id, title, description, status, created_at, updated_at, created_by
        |FROM chat_conversations
        |WHERE id = ?
        |""".stripMargin

    withConnection { conn =>
      for
        maybeConversation <- queryOne(conn, sql)(_.setLong(1, id))(readChatConversationRow(_, sql))
        withMessages      <- ZIO.foreach(maybeConversation)(hydrateConversationMessages(conn, _))
      yield withMessages
    }

  override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]] =
    val sql =
      """SELECT id, run_id, title, description, status, created_at, updated_at, created_by
        |FROM chat_conversations
        |ORDER BY created_at DESC
        |LIMIT ? OFFSET ?
        |""".stripMargin

    withConnection { conn =>
      for
        conversations <- queryMany(conn, sql) { stmt =>
                           stmt.setInt(1, limit)
                           stmt.setInt(2, offset)
                         }(readChatConversationRow(_, sql))
        hydrated      <- ZIO.foreach(conversations)(hydrateConversationMessages(conn, _))
      yield hydrated
    }

  override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]] =
    val sql =
      """SELECT id, run_id, title, description, status, created_at, updated_at, created_by
        |FROM chat_conversations
        |WHERE run_id = ?
        |ORDER BY created_at DESC
        |""".stripMargin

    withConnection { conn =>
      for
        conversations <- queryMany(conn, sql)(_.setLong(1, runId))(readChatConversationRow(_, sql))
        hydrated      <- ZIO.foreach(conversations)(hydrateConversationMessages(conn, _))
      yield hydrated
    }

  override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit] =
    val sql =
      """UPDATE chat_conversations
        |SET title = ?, description = ?, status = ?, updated_at = ?
        |WHERE id = ?
        |""".stripMargin

    for
      conversationId <- ZIO
                          .fromOption(conversation.id)
                          .orElseFail(PersistenceError.QueryFailed(sql, "Conversation ID required for update"))
      _              <- withConnection { conn =>
                          executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("chat_conversations", conversationId)) {
                            stmt =>
                              stmt.setString(1, conversation.title)
                              setOptionalString(stmt, 2, conversation.description)
                              stmt.setString(3, conversation.status)
                              stmt.setString(4, conversation.updatedAt.toString)
                              stmt.setLong(5, conversationId)
                          }
                        }
    yield ()

  override def deleteConversation(id: Long): IO[PersistenceError, Unit] =
    val sql = "DELETE FROM chat_conversations WHERE id = ?"
    withConnection { conn =>
      executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("chat_conversations", id)) { stmt =>
        stmt.setLong(1, id)
      }
    }

  override def addMessage(message: ConversationMessage): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO chat_messages (conversation_id, sender, sender_type, content, message_type, metadata, created_at, updated_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "chat_messages") { stmt =>
        stmt.setLong(1, message.conversationId)
        stmt.setString(2, message.sender)
        stmt.setString(3, toDbSenderType(message.senderType))
        stmt.setString(4, message.content)
        stmt.setString(5, toDbMessageType(message.messageType))
        setOptionalString(stmt, 6, message.metadata)
        stmt.setString(7, message.createdAt.toString)
        stmt.setString(8, message.updatedAt.toString)
      }
    }

  override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationMessage]] =
    val sql =
      """SELECT id, conversation_id, sender, sender_type, content, message_type, metadata, created_at, updated_at
        |FROM chat_messages
        |WHERE conversation_id = ?
        |ORDER BY created_at ASC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql)(_.setLong(1, conversationId))(readConversationMessageRow(_, sql))
    }

  override def getMessagesSince(conversationId: Long, since: Instant): IO[PersistenceError, List[ConversationMessage]] =
    val sql =
      """SELECT id, conversation_id, sender, sender_type, content, message_type, metadata, created_at, updated_at
        |FROM chat_messages
        |WHERE conversation_id = ? AND created_at >= ?
        |ORDER BY created_at ASC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql) { stmt =>
        stmt.setLong(1, conversationId)
        stmt.setString(2, since.toString)
      }(readConversationMessageRow(_, sql))
    }

  override def createIssue(issue: AgentIssue): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO agent_issues (
        |  run_id, conversation_id, title, description, issue_type,
        |  tags, preferred_agent, context_path, source_folder,
        |  priority, status, assigned_agent, assigned_at, completed_at,
        |  error_message, result_data, created_at, updated_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "agent_issues") { stmt =>
        setOptionalLong(stmt, 1, issue.runId)
        setOptionalLong(stmt, 2, issue.conversationId)
        stmt.setString(3, issue.title)
        stmt.setString(4, issue.description)
        stmt.setString(5, issue.issueType)
        setOptionalString(stmt, 6, issue.tags)
        setOptionalString(stmt, 7, issue.preferredAgent)
        setOptionalString(stmt, 8, issue.contextPath)
        setOptionalString(stmt, 9, issue.sourceFolder)
        stmt.setString(10, toDbIssuePriority(issue.priority))
        stmt.setString(11, toDbIssueStatus(issue.status))
        setOptionalString(stmt, 12, issue.assignedAgent)
        setOptionalString(stmt, 13, issue.assignedAt.map(_.toString))
        setOptionalString(stmt, 14, issue.completedAt.map(_.toString))
        setOptionalString(stmt, 15, issue.errorMessage)
        setOptionalString(stmt, 16, issue.resultData)
        stmt.setString(17, issue.createdAt.toString)
        stmt.setString(18, issue.updatedAt.toString)
      }
    }

  override def getIssue(id: Long): IO[PersistenceError, Option[AgentIssue]] =
    val sql =
      """SELECT id, run_id, conversation_id, title, description, issue_type,
        |       tags, preferred_agent, context_path, source_folder,
        |       priority, status, assigned_agent, assigned_at, completed_at,
        |       error_message, result_data, created_at, updated_at
        |FROM agent_issues
        |WHERE id = ?
        |""".stripMargin

    withConnection { conn =>
      queryOne(conn, sql)(_.setLong(1, id))(readAgentIssueRow(_, sql))
    }

  override def listIssues(offset: Int, limit: Int): IO[PersistenceError, List[AgentIssue]] =
    val sql =
      """SELECT id, run_id, conversation_id, title, description, issue_type,
        |       tags, preferred_agent, context_path, source_folder,
        |       priority, status, assigned_agent, assigned_at, completed_at,
        |       error_message, result_data, created_at, updated_at
        |FROM agent_issues
        |ORDER BY updated_at DESC
        |LIMIT ? OFFSET ?
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql) { stmt =>
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      }(readAgentIssueRow(_, sql))
    }

  override def listIssuesByRun(runId: Long): IO[PersistenceError, List[AgentIssue]] =
    val sql =
      """SELECT id, run_id, conversation_id, title, description, issue_type,
        |       tags, preferred_agent, context_path, source_folder,
        |       priority, status, assigned_agent, assigned_at, completed_at,
        |       error_message, result_data, created_at, updated_at
        |FROM agent_issues
        |WHERE run_id = ?
        |ORDER BY created_at DESC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql)(_.setLong(1, runId))(readAgentIssueRow(_, sql))
    }

  override def listIssuesByStatus(status: IssueStatus): IO[PersistenceError, List[AgentIssue]] =
    val sql =
      """SELECT id, run_id, conversation_id, title, description, issue_type,
        |       tags, preferred_agent, context_path, source_folder,
        |       priority, status, assigned_agent, assigned_at, completed_at,
        |       error_message, result_data, created_at, updated_at
        |FROM agent_issues
        |WHERE status = ?
        |ORDER BY created_at DESC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql)(_.setString(1, toDbIssueStatus(status)))(readAgentIssueRow(_, sql))
    }

  override def listUnassignedIssues(runId: Long): IO[PersistenceError, List[AgentIssue]] =
    val sql =
      """SELECT id, run_id, conversation_id, title, description, issue_type,
        |       tags, preferred_agent, context_path, source_folder,
        |       priority, status, assigned_agent, assigned_at, completed_at,
        |       error_message, result_data, created_at, updated_at
        |FROM agent_issues
        |WHERE run_id = ? AND assigned_agent IS NULL
        |ORDER BY created_at DESC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql)(_.setLong(1, runId))(readAgentIssueRow(_, sql))
    }

  override def updateIssue(issue: AgentIssue): IO[PersistenceError, Unit] =
    val sql =
      """UPDATE agent_issues
        |SET run_id = ?, conversation_id = ?, title = ?, description = ?, issue_type = ?, tags = ?,
        |    preferred_agent = ?, context_path = ?, source_folder = ?, priority = ?, status = ?,
        |    assigned_agent = ?, assigned_at = ?, completed_at = ?, error_message = ?, result_data = ?, updated_at = ?
        |WHERE id = ?
        |""".stripMargin

    for
      issueId <- ZIO
                   .fromOption(issue.id)
                   .orElseFail(PersistenceError.QueryFailed(sql, "Issue ID required for update"))
      _       <- withConnection { conn =>
                   executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("agent_issues", issueId)) { stmt =>
                     setOptionalLong(stmt, 1, issue.runId)
                     setOptionalLong(stmt, 2, issue.conversationId)
                     stmt.setString(3, issue.title)
                     stmt.setString(4, issue.description)
                     stmt.setString(5, issue.issueType)
                     setOptionalString(stmt, 6, issue.tags)
                     setOptionalString(stmt, 7, issue.preferredAgent)
                     setOptionalString(stmt, 8, issue.contextPath)
                     setOptionalString(stmt, 9, issue.sourceFolder)
                     stmt.setString(10, toDbIssuePriority(issue.priority))
                     stmt.setString(11, toDbIssueStatus(issue.status))
                     setOptionalString(stmt, 12, issue.assignedAgent)
                     setOptionalString(stmt, 13, issue.assignedAt.map(_.toString))
                     setOptionalString(stmt, 14, issue.completedAt.map(_.toString))
                     setOptionalString(stmt, 15, issue.errorMessage)
                     setOptionalString(stmt, 16, issue.resultData)
                     stmt.setString(17, issue.updatedAt.toString)
                     stmt.setLong(18, issueId)
                   }
                 }
    yield ()

  override def assignIssueToAgent(issueId: Long, agentName: String): IO[PersistenceError, Unit] =
    val sql = "UPDATE agent_issues SET assigned_agent = ?, assigned_at = ?, status = ? WHERE id = ?"
    for
      now <- Clock.instant
      _   <- withConnection { conn =>
               executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("agent_issues", issueId)) { stmt =>
                 stmt.setString(1, agentName)
                 stmt.setString(2, now.toString)
                 stmt.setString(3, toDbIssueStatus(IssueStatus.Assigned))
                 stmt.setLong(4, issueId)
               }
             }
    yield ()

  override def createAssignment(assignment: AgentAssignment): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO agent_assignments (issue_id, agent_name, status, assigned_at, started_at, completed_at, execution_log, result)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "agent_assignments") { stmt =>
        stmt.setLong(1, assignment.issueId)
        stmt.setString(2, assignment.agentName)
        stmt.setString(3, assignment.status)
        stmt.setString(4, assignment.assignedAt.toString)
        setOptionalString(stmt, 5, assignment.startedAt.map(_.toString))
        setOptionalString(stmt, 6, assignment.completedAt.map(_.toString))
        setOptionalString(stmt, 7, assignment.executionLog)
        setOptionalString(stmt, 8, assignment.result)
      }
    }

  override def getAssignment(id: Long): IO[PersistenceError, Option[AgentAssignment]] =
    val sql =
      """SELECT id, issue_id, agent_name, status, assigned_at, started_at, completed_at, execution_log, result
        |FROM agent_assignments
        |WHERE id = ?
        |""".stripMargin

    withConnection { conn =>
      queryOne(conn, sql)(_.setLong(1, id))(readAgentAssignmentRow(_, sql))
    }

  override def listAssignmentsByIssue(issueId: Long): IO[PersistenceError, List[AgentAssignment]] =
    val sql =
      """SELECT id, issue_id, agent_name, status, assigned_at, started_at, completed_at, execution_log, result
        |FROM agent_assignments
        |WHERE issue_id = ?
        |ORDER BY assigned_at DESC
        |""".stripMargin

    withConnection { conn =>
      queryMany(conn, sql)(_.setLong(1, issueId))(readAgentAssignmentRow(_, sql))
    }

  override def updateAssignment(assignment: AgentAssignment): IO[PersistenceError, Unit] =
    val sql =
      """UPDATE agent_assignments
        |SET status = ?, started_at = ?, completed_at = ?, execution_log = ?, result = ?
        |WHERE id = ?
        |""".stripMargin

    for
      assignmentId <- ZIO
                        .fromOption(assignment.id)
                        .orElseFail(PersistenceError.QueryFailed(sql, "Assignment ID required for update"))
      _            <- withConnection { conn =>
                        executeUpdateExpectingRows(conn, sql, PersistenceError.NotFound("agent_assignments", assignmentId)) {
                          stmt =>
                            stmt.setString(1, assignment.status)
                            setOptionalString(stmt, 2, assignment.startedAt.map(_.toString))
                            setOptionalString(stmt, 3, assignment.completedAt.map(_.toString))
                            setOptionalString(stmt, 4, assignment.executionLog)
                            setOptionalString(stmt, 5, assignment.result)
                            stmt.setLong(6, assignmentId)
                        }
                      }
    yield ()

  override def upsertSessionContext(
    channelName: String,
    sessionKey: String,
    contextJson: String,
    updatedAt: Instant,
  ): IO[PersistenceError, Unit] =
    val sql =
      """INSERT INTO chat_session_context (channel_name, session_key, context_json, updated_at)
        |VALUES (?, ?, ?, ?)
        |ON CONFLICT(channel_name, session_key)
        |DO UPDATE SET context_json = excluded.context_json, updated_at = excluded.updated_at
        |""".stripMargin
    withConnection { conn =>
      withPreparedStatement(conn, sql) { stmt =>
        for
          _ <- executeBlocking(sql) {
                 stmt.setString(1, channelName)
                 stmt.setString(2, sessionKey)
                 stmt.setString(3, contextJson)
                 stmt.setString(4, updatedAt.toString)
               }
          _ <- executeBlocking(sql)(stmt.executeUpdate()).unit
        yield ()
      }
    }

  override def getSessionContext(
    channelName: String,
    sessionKey: String,
  ): IO[PersistenceError, Option[String]] =
    val sql =
      """SELECT context_json
        |FROM chat_session_context
        |WHERE channel_name = ? AND session_key = ?
        |""".stripMargin
    withConnection { conn =>
      queryOne(conn, sql) { stmt =>
        stmt.setString(1, channelName)
        stmt.setString(2, sessionKey)
      }(rs => executeBlocking(sql)(rs.getString("context_json")))
    }

  override def deleteSessionContext(
    channelName: String,
    sessionKey: String,
  ): IO[PersistenceError, Unit] =
    val sql =
      """DELETE FROM chat_session_context
        |WHERE channel_name = ? AND session_key = ?
        |""".stripMargin
    withConnection { conn =>
      withPreparedStatement(conn, sql) { stmt =>
        for
          _ <- executeBlocking(sql) {
                 stmt.setString(1, channelName)
                 stmt.setString(2, sessionKey)
               }
          _ <- executeBlocking(sql)(stmt.executeUpdate()).unit
        yield ()
      }
    }

  private def hydrateConversationMessages(
    conn: Connection,
    conversation: ChatConversation,
  ): IO[PersistenceError, ChatConversation] =
    val sql =
      """SELECT id, conversation_id, sender, sender_type, content, message_type, metadata, created_at, updated_at
        |FROM chat_messages
        |WHERE conversation_id = ?
        |ORDER BY created_at ASC
        |""".stripMargin

    for
      messages <- queryMany(conn, sql)(_.setLong(1, conversation.id.getOrElse(0L)))(readConversationMessageRow(_, sql))
    yield conversation.copy(messages = messages)

  private def withConnection[A](use: Connection => IO[PersistenceError, A]): IO[PersistenceError, A] =
    ensureSchemaInitialized *> ZIO.acquireReleaseWith(acquireConnection)(closeConnection)(use)

  private def acquireConnection: IO[PersistenceError, Connection] =
    ZIO
      .attemptBlocking {
        val conn = ds.getConnection
        val stmt = conn.createStatement()
        try stmt.execute("PRAGMA foreign_keys = ON")
        finally stmt.close()
        conn
      }
      .mapError(e => PersistenceError.ConnectionFailed(e.getMessage))

  private def closeConnection(conn: Connection): UIO[Unit] =
    ZIO.attemptBlocking(conn.close()).ignore

  private def ensureSchemaInitialized: IO[PersistenceError, Unit] =
    initialized.modifyZIO {
      case true  => ZIO.succeed(((), true))
      case false => initializeSchema.as(((), true))
    }

  private def initializeSchema: IO[PersistenceError, Unit] =
    for
      statements <- loadSchemaStatements(List("db/V1__init_schema.sql", "db/V2__chat_and_issues.sql"))
      _          <- ZIO.acquireReleaseWith(acquireConnection)(closeConnection) { conn =>
                      ZIO.foreachDiscard(statements) { sql =>
                        withStatement(conn, sql)(stmt => executeBlocking(sql)(stmt.execute(sql)).unit)
                      } *> ensureAgentIssueColumns(conn) *> ensureSessionContextTable(conn)
                    }
    yield ()

  private def ensureSessionContextTable(conn: Connection): IO[PersistenceError, Unit] =
    val sql =
      """CREATE TABLE IF NOT EXISTS chat_session_context (
        |  channel_name TEXT NOT NULL,
        |  session_key TEXT NOT NULL,
        |  context_json TEXT NOT NULL,
        |  updated_at TEXT NOT NULL,
        |  PRIMARY KEY (channel_name, session_key)
        |)""".stripMargin
    withStatement(conn, sql)(stmt => executeBlocking(sql)(stmt.execute(sql)).unit)

  private def ensureAgentIssueColumns(conn: Connection): IO[PersistenceError, Unit] =
    for
      info          <- readAgentIssueColumns(conn)
      _             <- if info.exists(c => c._1 == "run_id" && c._3 == 1) then rebuildAgentIssuesTable(conn) else ZIO.unit
      now           <- readAgentIssueColumns(conn)
      missingColumns = List(
                         "tags"            -> "TEXT",
                         "preferred_agent" -> "TEXT",
                         "context_path"    -> "TEXT",
                         "source_folder"   -> "TEXT",
                       ).filterNot { case (name, _) => now.exists(_._1 == name) }
      _             <- ZIO.foreachDiscard(missingColumns) {
                         case (name, ddl) =>
                           val alterSql = s"ALTER TABLE agent_issues ADD COLUMN $name $ddl"
                           withStatement(conn, alterSql)(stmt => executeBlocking(alterSql)(stmt.execute(alterSql)).unit)
                       }
    yield ()

  private def readAgentIssueColumns(conn: Connection): IO[PersistenceError, List[(String, String, Int)]] =
    val pragmaSql = "PRAGMA table_info(agent_issues)"
    withStatement(conn, pragmaSql) { stmt =>
      ZIO.acquireReleaseWith(executeBlocking(pragmaSql)(stmt.executeQuery(pragmaSql)))(rs =>
        executeBlocking(pragmaSql)(rs.close()).ignore
      ) { rs =>
        def loop(acc: List[(String, String, Int)]): IO[PersistenceError, List[(String, String, Int)]] =
          executeBlocking(pragmaSql)(rs.next()).flatMap {
            case true  =>
              for
                name    <- executeBlocking(pragmaSql)(rs.getString("name"))
                colType <- executeBlocking(pragmaSql)(rs.getString("type"))
                notNull <- executeBlocking(pragmaSql)(rs.getInt("notnull"))
                next    <- loop((name, colType, notNull) :: acc)
              yield next
            case false => ZIO.succeed(acc.reverse)
          }
        loop(Nil)
      }
    }

  private def rebuildAgentIssuesTable(conn: Connection): IO[PersistenceError, Unit] =
    val statements = List(
      "PRAGMA foreign_keys = OFF",
      "DROP TABLE IF EXISTS agent_issues_new",
      """CREATE TABLE agent_issues_new (
        |  id INTEGER PRIMARY KEY AUTOINCREMENT,
        |  run_id INTEGER,
        |  conversation_id INTEGER,
        |  title TEXT NOT NULL,
        |  description TEXT NOT NULL,
        |  issue_type TEXT NOT NULL,
        |  tags TEXT,
        |  preferred_agent TEXT,
        |  context_path TEXT,
        |  source_folder TEXT,
        |  priority TEXT NOT NULL DEFAULT 'medium' CHECK (priority IN ('low', 'medium', 'high', 'critical')),
        |  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'assigned', 'in_progress', 'completed', 'failed', 'skipped')),
        |  assigned_agent TEXT,
        |  assigned_at TEXT,
        |  completed_at TEXT,
        |  error_message TEXT,
        |  result_data TEXT,
        |  created_at TEXT NOT NULL,
        |  updated_at TEXT NOT NULL,
        |  FOREIGN KEY (run_id) REFERENCES migration_runs(id) ON DELETE CASCADE,
        |  FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE SET NULL
        |)""".stripMargin,
      """INSERT INTO agent_issues_new (
        |  id, run_id, conversation_id, title, description, issue_type,
        |  tags, preferred_agent, context_path, source_folder,
        |  priority, status, assigned_agent, assigned_at, completed_at,
        |  error_message, result_data, created_at, updated_at
        |)
        |SELECT
        |  id, run_id, conversation_id, title, description, issue_type,
        |  NULL, NULL, NULL, NULL,
        |  priority, status, assigned_agent, assigned_at, completed_at,
        |  error_message, result_data, created_at, updated_at
        |FROM agent_issues""".stripMargin,
      "DROP TABLE agent_issues",
      "ALTER TABLE agent_issues_new RENAME TO agent_issues",
      "CREATE INDEX IF NOT EXISTS idx_agent_issues_run_id ON agent_issues(run_id)",
      "CREATE INDEX IF NOT EXISTS idx_agent_issues_status ON agent_issues(status)",
      "CREATE INDEX IF NOT EXISTS idx_agent_issues_assigned_agent ON agent_issues(assigned_agent)",
      "CREATE INDEX IF NOT EXISTS idx_agent_issues_conversation_id ON agent_issues(conversation_id)",
      "PRAGMA foreign_keys = ON",
    )
    ZIO.foreachDiscard(statements) { sql =>
      withStatement(conn, sql)(stmt => executeBlocking(sql)(stmt.execute(sql)).unit)
    }

object ChatRepositoryLive:

  private def loadSchemaStatements(resources: List[String]): IO[PersistenceError, List[String]] =
    ZIO.foreach(resources)(loadStatementsFromResource).map(_.flatten)

  private def loadStatementsFromResource(resource: String): IO[PersistenceError, List[String]] =
    for
      maybeScript <- ZIO
                       .attempt(Option(getClass.getClassLoader.getResourceAsStream(resource)))
                       .mapError(e => PersistenceError.SchemaInitFailed(e.getMessage))
      stream      <- ZIO
                       .fromOption(maybeScript)
                       .orElseFail(PersistenceError.SchemaInitFailed(s"Schema resource $resource not found"))
      script      <- ZIO
                       .acquireReleaseWith(
                         ZIO.succeed(scala.io.Source.fromInputStream(stream, "UTF-8"))
                       )(src => ZIO.attempt(src.close()).orDie)(src => ZIO.attempt(src.mkString))
                       .mapError(e => PersistenceError.SchemaInitFailed(e.getMessage))
    yield script.split(";").map(_.trim).filter(_.nonEmpty).toList

  private def executeBlocking[A](sql: String)(thunk: => A): IO[PersistenceError, A] =
    ZIO.attemptBlocking(thunk).mapError(e => PersistenceError.QueryFailed(sql, e.getMessage))

  private def withStatement[A](
    conn: Connection,
    sql: String,
  )(
    use: Statement => IO[PersistenceError, A]
  ): IO[PersistenceError, A] =
    ZIO.acquireReleaseWith(executeBlocking(sql)(conn.createStatement()))(stmt =>
      executeBlocking(sql)(stmt.close()).ignore
    )(
      use
    )

  private def withPreparedStatement[A](
    conn: Connection,
    sql: String,
    returnGeneratedKeys: Int = Statement.NO_GENERATED_KEYS,
  )(
    use: java.sql.PreparedStatement => IO[PersistenceError, A]
  ): IO[PersistenceError, A] =
    ZIO.acquireReleaseWith(
      executeBlocking(sql)(conn.prepareStatement(sql, returnGeneratedKeys))
    )(stmt => executeBlocking(sql)(stmt.close()).ignore)(use)

  private def executeUpdateReturningKey(
    conn: Connection,
    sql: String,
    entity: String,
  )(
    bind: java.sql.PreparedStatement => Unit
  ): IO[PersistenceError, Long] =
    withPreparedStatement(conn, sql, Statement.RETURN_GENERATED_KEYS) { stmt =>
      for
        _        <- executeBlocking(sql)(bind(stmt))
        _        <- executeBlocking(sql)(stmt.executeUpdate()).unit
        maybeKey <- executeBlocking(sql) {
                      val keys = stmt.getGeneratedKeys
                      try if keys.next() then Some(keys.getLong(1)) else None
                      finally keys.close()
                    }
        key      <- ZIO
                      .fromOption(maybeKey)
                      .orElseFail(PersistenceError.QueryFailed(sql, s"No generated key returned for $entity"))
      yield key
    }

  private def executeUpdateExpectingRows(
    conn: Connection,
    sql: String,
    notFound: PersistenceError,
  )(
    bind: java.sql.PreparedStatement => Unit
  ): IO[PersistenceError, Unit] =
    withPreparedStatement(conn, sql) { stmt =>
      for
        _       <- executeBlocking(sql)(bind(stmt))
        updated <- executeBlocking(sql)(stmt.executeUpdate())
        _       <- if updated == 0 then ZIO.fail(notFound) else ZIO.unit
      yield ()
    }

  private def queryOne[A](
    conn: Connection,
    sql: String,
  )(
    bind: java.sql.PreparedStatement => Unit
  )(
    read: ResultSet => IO[PersistenceError, A]
  ): IO[PersistenceError, Option[A]] =
    withPreparedStatement(conn, sql) { stmt =>
      for
        _      <- executeBlocking(sql)(bind(stmt))
        result <- executeQuery(stmt, sql) { rs =>
                    for
                      hasNext <- executeBlocking(sql)(rs.next())
                      value   <- if hasNext then read(rs).map(Some(_)) else ZIO.none
                    yield value
                  }
      yield result
    }

  private def queryMany[A](
    conn: Connection,
    sql: String,
  )(
    bind: java.sql.PreparedStatement => Unit
  )(
    read: ResultSet => IO[PersistenceError, A]
  ): IO[PersistenceError, List[A]] =
    withPreparedStatement(conn, sql) { stmt =>
      for
        _      <- executeBlocking(sql)(bind(stmt))
        result <- executeQuery(stmt, sql) { rs =>
                    def loop(acc: List[A]): IO[PersistenceError, List[A]] =
                      executeBlocking(sql)(rs.next()).flatMap {
                        case true  =>
                          read(rs).flatMap { value =>
                            loop(value :: acc)
                          }
                        case false => ZIO.succeed(acc.reverse)
                      }
                    loop(Nil)
                  }
      yield result
    }

  private def executeQuery[A](
    stmt: java.sql.PreparedStatement,
    sql: String,
  )(
    use: ResultSet => IO[PersistenceError, A]
  ): IO[PersistenceError, A] =
    ZIO.acquireReleaseWith(
      executeBlocking(sql)(stmt.executeQuery())
    )(rs => executeBlocking(sql)(rs.close()).ignore)(use)

  private def setOptionalLong(stmt: java.sql.PreparedStatement, idx: Int, value: Option[Long]): Unit =
    value match
      case Some(v) => stmt.setLong(idx, v)
      case None    => stmt.setNull(idx, Types.BIGINT)

  private def setOptionalString(stmt: java.sql.PreparedStatement, idx: Int, value: Option[String]): Unit =
    value match
      case Some(v) => stmt.setString(idx, v)
      case None    => stmt.setNull(idx, Types.VARCHAR)

  private def getOptionalLong(rs: ResultSet, column: String): Option[Long] =
    val value = rs.getLong(column)
    if rs.wasNull() then None else Some(value)

  private def parseInstant(sql: String, raw: String): IO[PersistenceError, Instant] =
    ZIO.attempt(Instant.parse(raw)).mapError(e => PersistenceError.QueryFailed(sql, e.getMessage))

  private def readChatConversationRow(rs: ResultSet, sql: String): IO[PersistenceError, ChatConversation] =
    for
      createdAt <- parseInstant(sql, rs.getString("created_at"))
      updatedAt <- parseInstant(sql, rs.getString("updated_at"))
    yield ChatConversation(
      id = Some(rs.getLong("id")),
      runId = getOptionalLong(rs, "run_id"),
      title = rs.getString("title"),
      description = Option(rs.getString("description")),
      status = rs.getString("status"),
      messages = List.empty,
      createdAt = createdAt,
      updatedAt = updatedAt,
      createdBy = Option(rs.getString("created_by")),
    )

  private def readConversationMessageRow(rs: ResultSet, sql: String): IO[PersistenceError, ConversationMessage] =
    for
      senderType  <- parseSenderType(sql, rs.getString("sender_type"))
      messageType <- parseMessageType(sql, rs.getString("message_type"))
      createdAt   <- parseInstant(sql, rs.getString("created_at"))
      updatedAt   <- parseInstant(sql, rs.getString("updated_at"))
    yield ConversationMessage(
      id = Some(rs.getLong("id")),
      conversationId = rs.getLong("conversation_id"),
      sender = rs.getString("sender"),
      senderType = senderType,
      content = rs.getString("content"),
      messageType = messageType,
      metadata = Option(rs.getString("metadata")),
      createdAt = createdAt,
      updatedAt = updatedAt,
    )

  private def readAgentIssueRow(rs: ResultSet, sql: String): IO[PersistenceError, AgentIssue] =
    for
      priority    <- parseIssuePriority(sql, rs.getString("priority"))
      status      <- parseIssueStatus(sql, rs.getString("status"))
      createdAt   <- parseInstant(sql, rs.getString("created_at"))
      updatedAt   <- parseInstant(sql, rs.getString("updated_at"))
      assignedAt  <- parseOptionalInstant(sql, rs.getString("assigned_at"))
      completedAt <- parseOptionalInstant(sql, rs.getString("completed_at"))
    yield AgentIssue(
      id = Some(rs.getLong("id")),
      runId = getOptionalLong(rs, "run_id"),
      conversationId = getOptionalLong(rs, "conversation_id"),
      title = rs.getString("title"),
      description = rs.getString("description"),
      issueType = rs.getString("issue_type"),
      tags = Option(rs.getString("tags")),
      preferredAgent = Option(rs.getString("preferred_agent")),
      contextPath = Option(rs.getString("context_path")),
      sourceFolder = Option(rs.getString("source_folder")),
      priority = priority,
      status = status,
      assignedAgent = Option(rs.getString("assigned_agent")),
      assignedAt = assignedAt,
      completedAt = completedAt,
      errorMessage = Option(rs.getString("error_message")),
      resultData = Option(rs.getString("result_data")),
      createdAt = createdAt,
      updatedAt = updatedAt,
    )

  private def readAgentAssignmentRow(rs: ResultSet, sql: String): IO[PersistenceError, AgentAssignment] =
    for
      assignedAt  <- parseInstant(sql, rs.getString("assigned_at"))
      startedAt   <- parseOptionalInstant(sql, rs.getString("started_at"))
      completedAt <- parseOptionalInstant(sql, rs.getString("completed_at"))
    yield AgentAssignment(
      id = Some(rs.getLong("id")),
      issueId = rs.getLong("issue_id"),
      agentName = rs.getString("agent_name"),
      status = rs.getString("status"),
      assignedAt = assignedAt,
      startedAt = startedAt,
      completedAt = completedAt,
      executionLog = Option(rs.getString("execution_log")),
      result = Option(rs.getString("result")),
    )

  private def parseOptionalInstant(sql: String, raw: String | Null): IO[PersistenceError, Option[Instant]] =
    Option(raw) match
      case Some(value) => parseInstant(sql, value).map(Some(_))
      case None        => ZIO.none

  private def toDbSenderType(senderType: SenderType): String = senderType match
    case SenderType.User      => "user"
    case SenderType.Assistant => "assistant"
    case SenderType.System    => "system"

  private def toDbMessageType(messageType: MessageType): String = messageType match
    case MessageType.Text   => "text"
    case MessageType.Code   => "code"
    case MessageType.Error  => "error"
    case MessageType.Status => "status"

  private def toDbIssuePriority(priority: IssuePriority): String = priority match
    case IssuePriority.Low      => "low"
    case IssuePriority.Medium   => "medium"
    case IssuePriority.High     => "high"
    case IssuePriority.Critical => "critical"

  private def toDbIssueStatus(status: IssueStatus): String = status match
    case IssueStatus.Open       => "open"
    case IssueStatus.Assigned   => "assigned"
    case IssueStatus.InProgress => "in_progress"
    case IssueStatus.Completed  => "completed"
    case IssueStatus.Failed     => "failed"
    case IssueStatus.Skipped    => "skipped"

  private def parseSenderType(sql: String, value: String): IO[PersistenceError, SenderType] =
    value.toLowerCase match
      case "user"      => ZIO.succeed(SenderType.User)
      case "assistant" => ZIO.succeed(SenderType.Assistant)
      case "system"    => ZIO.succeed(SenderType.System)
      case other       => ZIO.fail(PersistenceError.QueryFailed(sql, s"Unknown sender_type: $other"))

  private def parseMessageType(sql: String, value: String): IO[PersistenceError, MessageType] =
    value.toLowerCase match
      case "text"   => ZIO.succeed(MessageType.Text)
      case "code"   => ZIO.succeed(MessageType.Code)
      case "error"  => ZIO.succeed(MessageType.Error)
      case "status" => ZIO.succeed(MessageType.Status)
      case other    => ZIO.fail(PersistenceError.QueryFailed(sql, s"Unknown message_type: $other"))

  private def parseIssuePriority(sql: String, value: String): IO[PersistenceError, IssuePriority] =
    value.toLowerCase match
      case "low"      => ZIO.succeed(IssuePriority.Low)
      case "medium"   => ZIO.succeed(IssuePriority.Medium)
      case "high"     => ZIO.succeed(IssuePriority.High)
      case "critical" => ZIO.succeed(IssuePriority.Critical)
      case other      => ZIO.fail(PersistenceError.QueryFailed(sql, s"Unknown priority: $other"))

  private def parseIssueStatus(sql: String, value: String): IO[PersistenceError, IssueStatus] =
    value.toLowerCase match
      case "open"        => ZIO.succeed(IssueStatus.Open)
      case "assigned"    => ZIO.succeed(IssueStatus.Assigned)
      case "in_progress" => ZIO.succeed(IssueStatus.InProgress)
      case "completed"   => ZIO.succeed(IssueStatus.Completed)
      case "failed"      => ZIO.succeed(IssueStatus.Failed)
      case "skipped"     => ZIO.succeed(IssueStatus.Skipped)
      case other         => ZIO.fail(PersistenceError.QueryFailed(sql, s"Unknown issue status: $other"))
