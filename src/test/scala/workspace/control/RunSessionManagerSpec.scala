package workspace.control

import java.io.{ BufferedWriter, ByteArrayInputStream, ByteArrayOutputStream, StringWriter }
import java.time.Instant

import zio.*
import zio.stream.ZStream
import zio.test.*

import activity.entity.ActivityEvent
import conversation.entity.api.{ ChatConversation, ConversationEntry }
import db.{ ChatRepository, PersistenceError as ChatPersistenceError }
import shared.errors.PersistenceError
import workspace.entity.*

object RunSessionManagerSpec extends ZIOSpecDefault:

  final private class InMemoryWorkspaceRepo(eventsRef: Ref[Map[String, List[WorkspaceRunEvent]]])
    extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit] = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]               = ZIO.succeed(Nil)
    override def get(id: String): IO[PersistenceError, Option[Workspace]]  =
      ZIO.succeed(
        Some(
          Workspace(
            id = "ws-1",
            name = "repo",
            localPath = "/tmp",
            defaultAgent = None,
            description = None,
            enabled = true,
            runMode = RunMode.Host,
            cliTool = "claude",
            createdAt = baseAssignedAt,
            updatedAt = baseAssignedAt,
          )
        ).filter(_.id == id)
      )
    override def delete(id: String): IO[PersistenceError, Unit]            = ZIO.unit

    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit] =
      eventsRef.update(current => current.updated(event.runId, current.getOrElse(event.runId, Nil) :+ event))

    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] =
      eventsRef.get.map(
        _.values.toList.flatMap(events => WorkspaceRun.fromEvents(events).toOption).filter(_.workspaceId == workspaceId)
      )

    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] =
      eventsRef.get.map(_.get(id).flatMap(events => WorkspaceRun.fromEvents(events).toOption))

  final private class StubInteractiveRunner(
    processRef: Ref[Option[AgentProcess]],
    pausedRef: Ref[Int],
    resumedRef: Ref[Int],
    sentRef: Ref[List[String]],
  ) extends InteractiveAgentRunner:
    override def start(argv: List[String], cwd: String): Task[AgentProcess]           = ZIO.fail(new RuntimeException("unused"))
    override def sendInput(process: AgentProcess, message: String): Task[Unit]        =
      sentRef.update(_ :+ message)
    override def isAlive(process: AgentProcess): Task[Boolean]                        = ZIO.succeed(true)
    override def pause(process: AgentProcess): Task[Unit]                             = pausedRef.update(_ + 1)
    override def resume(process: AgentProcess): Task[Unit]                            = resumedRef.update(_ + 1)
    override def terminate(process: AgentProcess): Task[Unit]                         = ZIO.unit
    override def register(runId: String, process: AgentProcess): UIO[AgentProcessRef] =
      processRef.set(Some(process)).as(AgentProcessRef(runId))
    override def resolve(ref: AgentProcessRef): Task[Option[AgentProcess]]            = processRef.get
    override def unregister(ref: AgentProcessRef): UIO[Unit]                          = processRef.set(None)

  final private class InMemoryChatRepo(messagesRef: Ref[List[ConversationEntry]]) extends ChatRepository:
    override def createConversation(conversation: ChatConversation): IO[ChatPersistenceError, Long]               = ZIO.succeed(1L)
    override def getConversation(id: Long): IO[ChatPersistenceError, Option[ChatConversation]]                    = ZIO.none
    override def listConversations(offset: Int, limit: Int): IO[ChatPersistenceError, List[ChatConversation]]     =
      ZIO.succeed(Nil)
    override def getConversationsByChannel(channelName: String): IO[ChatPersistenceError, List[ChatConversation]] =
      ZIO.succeed(Nil)
    override def listConversationsByRun(runId: Long): IO[ChatPersistenceError, List[ChatConversation]]            =
      ZIO.succeed(Nil)
    override def updateConversation(conversation: ChatConversation): IO[ChatPersistenceError, Unit]               = ZIO.unit
    override def deleteConversation(id: Long): IO[ChatPersistenceError, Unit]                                     = ZIO.unit
    override def addMessage(message: ConversationEntry): IO[ChatPersistenceError, Long]                           =
      messagesRef.modify(messages => (messages.size.toLong + 1L, messages :+ message))
    override def getMessages(conversationId: Long): IO[ChatPersistenceError, List[ConversationEntry]]             = messagesRef.get
    override def getMessagesSince(conversationId: Long, since: Instant)
      : IO[ChatPersistenceError, List[ConversationEntry]] =
      messagesRef.get.map(_.filter(_.createdAt.isAfter(since)))

  private val baseAssignedAt = Instant.parse("2026-03-02T08:00:00Z")

  private def baseRunEvents(runId: String): List[WorkspaceRunEvent] =
    List(
      WorkspaceRunEvent.Assigned(
        runId = runId,
        workspaceId = "ws-1",
        parentRunId = None,
        issueRef = "#1",
        agentName = "claude",
        prompt = "fix",
        conversationId = "conv-1",
        worktreePath = "/tmp/wt",
        branchName = "agent/1-aaaa",
        occurredAt = baseAssignedAt,
      ),
      WorkspaceRunEvent.StatusChanged(
        runId = runId,
        status = RunStatus.Running(RunSessionMode.Autonomous),
        occurredAt = baseAssignedAt.plusSeconds(2),
      ),
    )

  private object DummyProcess extends Process:
    private val in                                     = ByteArrayInputStream(Array.emptyByteArray)
    private val err                                    = ByteArrayInputStream(Array.emptyByteArray)
    private val out                                    = ByteArrayOutputStream()
    override def getInputStream: java.io.InputStream   = in
    override def getErrorStream: java.io.InputStream   = err
    override def getOutputStream: java.io.OutputStream = out
    override def waitFor(): Int                        = 0
    override def exitValue(): Int                      = 0
    override def destroy(): Unit                       = ()
    override def isAlive(): Boolean                    = true

  private val dummyProcess = AgentProcess(
    process = DummyProcess,
    stdinWriter = new BufferedWriter(new StringWriter()),
    stdout = ZStream.empty,
    stderr = ZStream.empty,
    release = ZIO.unit,
  )

  private def makeManager(initialEvents: List[WorkspaceRunEvent]) =
    for
      eventsRef   <- Ref.make(initialEvents.groupBy(_.runId))
      processRef  <- Ref.make(Option(dummyProcess))
      pausedRef   <- Ref.make(0)
      resumedRef  <- Ref.make(0)
      sentRef     <- Ref.make(List.empty[String])
      activityRef <- Ref.make(List.empty[ActivityEvent])
      messagesRef <- Ref.make(List.empty[ConversationEntry])
      repo         = InMemoryWorkspaceRepo(eventsRef)
      runner       = StubInteractiveRunner(processRef, pausedRef, resumedRef, sentRef)
      manager      = RunSessionManagerLive(repo, runner, InMemoryChatRepo(messagesRef), evt => activityRef.update(_ :+ evt))
    yield (manager, repo, pausedRef, resumedRef, sentRef, activityRef, processRef, messagesRef)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("RunSessionManagerSpec")(
    test("attach transitions run to Interactive and assigns controller") {
      for
        (manager, repo, _, _, _, _, _, _) <- makeManager(baseRunEvents("run-1"))
        _                                 <- manager.attach("run-1", "alice")
        run                               <- repo.getRun("run-1")
                                               .mapError(e => RuntimeException(e.toString))
                                               .orDie
                                               .flatMap {
                                                 case Some(value) => ZIO.succeed(value)
                                                 case None        => ZIO.fail(RuntimeException("run-1 not found"))
                                               }
      yield assertTrue(
        run.status == RunStatus.Running(RunSessionMode.Interactive),
        run.attachedUsers == Set("alice"),
        run.controllerUserId.contains("alice"),
      )
    },
    test("detach removes user and falls back to Autonomous when last user leaves") {
      val initial = baseRunEvents("run-2") ++ List(
        WorkspaceRunEvent.UserAttached("run-2", "alice", baseAssignedAt.plusSeconds(3))
      )
      for
        (manager, repo, _, _, _, _, _, _) <- makeManager(initial)
        _                                 <- manager.detach("run-2", "alice")
        run                               <- repo.getRun("run-2")
                                               .mapError(e => RuntimeException(e.toString))
                                               .orDie
                                               .flatMap {
                                                 case Some(value) => ZIO.succeed(value)
                                                 case None        => ZIO.fail(RuntimeException("run-2 not found"))
                                               }
      yield assertTrue(
        run.status == RunStatus.Running(RunSessionMode.Autonomous),
        run.attachedUsers.isEmpty,
        run.controllerUserId.isEmpty,
      )
    },
    test("interrupt rejects non-controller user") {
      val initial = baseRunEvents("run-3") ++ List(
        WorkspaceRunEvent.UserAttached("run-3", "alice", baseAssignedAt.plusSeconds(3))
      )
      for
        (manager, _, _, _, _, _, _, _) <- makeManager(initial)
        result                         <- manager.interrupt("run-3", "bob").either
      yield assertTrue(result match
        case Left(WorkspaceError.ControllerConflict("run-3", "alice", "bob")) => true
        case _                                                                => false)
    },
    test("interrupt pauses process and resume sends input") {
      val initial = baseRunEvents("run-4") ++ List(
        WorkspaceRunEvent.UserAttached("run-4", "alice", baseAssignedAt.plusSeconds(3))
      )
      for
        (manager, repo, pausedRef, resumedRef, sentRef, _, _, _) <- makeManager(initial)
        _                                                        <- manager.interrupt("run-4", "alice")
        paused                                                   <- pausedRef.get
        run1                                                     <- repo.getRun("run-4")
                                                                      .mapError(e => RuntimeException(e.toString))
                                                                      .orDie
                                                                      .flatMap {
                                                                        case Some(value) => ZIO.succeed(value)
                                                                        case None        => ZIO.fail(RuntimeException("run-4 not found after interrupt"))
                                                                      }
        _                                                        <- manager.resume("run-4", "alice", "continue with tests")
        resumed                                                  <- resumedRef.get
        sent                                                     <- sentRef.get
        run2                                                     <- repo.getRun("run-4")
                                                                      .mapError(e => RuntimeException(e.toString))
                                                                      .orDie
                                                                      .flatMap {
                                                                        case Some(value) => ZIO.succeed(value)
                                                                        case None        => ZIO.fail(RuntimeException("run-4 not found after resume"))
                                                                      }
      yield assertTrue(
        paused == 1,
        resumed == 1,
        sent.lastOption.contains("continue with tests"),
        run1.status == RunStatus.Running(RunSessionMode.Paused),
        run2.status == RunStatus.Running(RunSessionMode.Interactive),
      )
    },
    test("interrupt fails when interactive process is unavailable") {
      val initial = baseRunEvents("run-5") ++ List(
        WorkspaceRunEvent.UserAttached("run-5", "alice", baseAssignedAt.plusSeconds(3))
      )
      for
        (manager, _, _, _, _, _, processRef, _) <- makeManager(initial)
        _                                       <- processRef.set(None)
        result                                  <- manager.interrupt("run-5", "alice").either
      yield assertTrue(result == Left(WorkspaceError.InteractiveProcessUnavailable("run-5")))
    },
    test("sendMessage accepts input in interactive mode and persists user message") {
      val initial = baseRunEvents("run-6") ++ List(
        WorkspaceRunEvent.UserAttached("run-6", "alice", baseAssignedAt.plusSeconds(3))
      )
      for
        (manager, _, _, _, sentRef, _, _, messagesRef) <- makeManager(initial)
        result                                         <- manager.sendMessage("run-6", "alice", "check edge cases")
        sent                                           <- sentRef.get
        messages                                       <- messagesRef.get
      yield assertTrue(
        result.isRight,
        sent.contains("check edge cases"),
        messages.exists(_.content == "check edge cases"),
      )
    },
    test("sendMessage rejects in autonomous mode and queues message for continuation") {
      for
        (manager, _, _, _, sentRef, _, _, messagesRef) <- makeManager(baseRunEvents("run-7"))
        result                                         <- manager.sendMessage("run-7", "alice", "please continue")
        sent                                           <- sentRef.get
        messages                                       <- messagesRef.get
      yield assertTrue(
        result.isLeft,
        sent.isEmpty,
        messages.exists(_.content == "please continue"),
      )
    },
  )
