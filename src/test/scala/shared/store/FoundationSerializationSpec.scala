package shared.store

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import analysis.entity.*
import conversation.entity.*
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import issues.entity.*
import shared.ids.Ids
import taskrun.entity.*

object FoundationSerializationSpec extends ZIOSpecDefault:

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("foundation-serialization-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files
            .walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach { path =>
              val _ = Files.deleteIfExists(path)
            }
      }.ignore
    )(use)

  private def layerFor(
    dataDir: Path
  ): ZLayer[Any, EclipseStoreError | GigaMapError, DataStoreModule.DataStoreService] =
    ZLayer.succeed(
      StoreConfig(
        configStorePath = dataDir.resolve("config-store").toString,
        dataStorePath = dataDir.resolve("data-store").toString,
      )
    ) >>> DataStoreModule.live

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("FoundationSerializationSpec")(
      test("task run ADTs and events round-trip") {
        withTempDir { dir =>
          val createdAt           = Instant.parse("2026-02-23T10:00:00Z")
          val report              = TaskReport(
            id = Ids.ReportId("rep-1"),
            stepName = "analysis",
            reportType = "summary",
            content = "ok",
            createdAt = createdAt,
          )
          val artifact            = TaskArtifact(
            id = Ids.ArtifactId("art-1"),
            stepName = "analysis",
            key = "output",
            value = "target/out",
            createdAt = createdAt,
          )
          val run                 = TaskRun(
            id = Ids.TaskRunId("run-1"),
            workflowId = Ids.WorkflowId("wf-1"),
            state = TaskRunState.Running(startedAt = createdAt, currentPhase = "analysis"),
            agentName = "planner",
            source = "workspace/repo",
            reports = List(report),
            artifacts = List(artifact),
          )
          val event: TaskRunEvent = TaskRunEvent.ReportAdded(
            runId = run.id,
            report = report,
            occurredAt = createdAt,
          )

          (for
            data        <- ZIO.service[DataStoreModule.DataStoreService]
            _           <- data.store("foundation:taskrun", run)
            loadedRun   <- data.fetch[String, TaskRun]("foundation:taskrun")
            _           <- data.store("foundation:taskrun:event", event)
            loadedEvent <- data.fetch[String, TaskRunEvent]("foundation:taskrun:event")
          yield assertTrue(loadedRun.contains(run), loadedEvent.contains(event))).provideLayer(layerFor(dir))
        }
      },
      test("issue ADTs and events round-trip") {
        withTempDir { dir =>
          val createdAt         = Instant.parse("2026-02-23T10:05:00Z")
          val issue             = AgentIssue(
            id = Ids.IssueId("iss-1"),
            runId = Some(Ids.TaskRunId("run-1")),
            conversationId = Some(Ids.ConversationId("conv-1")),
            title = "Fix mapping",
            description = "replace row mapping with ADT",
            issueType = "refactor",
            priority = "high",
            requiredCapabilities = List("scala", "refactoring"),
            state = IssueState.Assigned(Ids.AgentId("agent-1"), createdAt),
            tags = List("event-sourcing", "phase1"),
            blockedBy = List(Ids.IssueId("iss-0")),
            blocking = List(Ids.IssueId("iss-2")),
            contextPath = "src/main/scala/db",
            sourceFolder = "src/main/scala",
            promptTemplate = Some("Fix ${title} with exhaustive tests."),
            acceptanceCriteria = Some("Round-trip serialization remains stable."),
            kaizenSkill = Some("scala-zio-refactor"),
            milestoneRef = Some("m9-foundation"),
            analysisDocIds = List(Ids.AnalysisDocId("analysis-1"), Ids.AnalysisDocId("analysis-2")),
          )
          val event: IssueEvent = IssueEvent.AnalysisAttached(
            issueId = issue.id,
            analysisDocIds = issue.analysisDocIds,
            attachedAt = createdAt,
            occurredAt = createdAt,
          )

          (for
            data        <- ZIO.service[DataStoreModule.DataStoreService]
            _           <- data.store("foundation:issue", issue)
            loadedIssue <- data.fetch[String, AgentIssue]("foundation:issue")
            _           <- data.store("foundation:issue:event", event)
            loadedEvent <- data.fetch[String, IssueEvent]("foundation:issue:event")
          yield assertTrue(loadedIssue.contains(issue), loadedEvent.contains(event))).provideLayer(layerFor(dir))
        }
      },
      test("conversation ADTs and events round-trip") {
        withTempDir { dir =>
          val createdAt                = Instant.parse("2026-02-23T10:10:00Z")
          val message                  = Message(
            id = Ids.MessageId("msg-1"),
            sender = "user",
            senderType = SenderType.User(),
            content = "hello",
            messageType = MessageType.Text(),
            createdAt = createdAt,
            metadata = Map("channel" -> "web"),
          )
          val conversation             = Conversation(
            id = Ids.ConversationId("conv-1"),
            channel = ChannelInfo.Web("session-1"),
            state = ConversationState.Active(createdAt),
            title = "Session",
            description = "chat with agent",
            messages = List(message),
            runId = Some(Ids.TaskRunId("run-1")),
            createdBy = Some("system"),
          )
          val event: ConversationEvent = ConversationEvent.MessageSent(
            conversationId = conversation.id,
            message = message,
            occurredAt = createdAt,
          )

          (for
            data        <- ZIO.service[DataStoreModule.DataStoreService]
            _           <- data.store("foundation:conversation", conversation)
            loaded      <- data.fetch[String, Conversation]("foundation:conversation")
            _           <- data.store("foundation:conversation:event", event)
            loadedEvent <- data.fetch[String, ConversationEvent]("foundation:conversation:event")
          yield assertTrue(loaded.contains(conversation), loadedEvent.contains(event))).provideLayer(layerFor(dir))
        }
      },
      test("analysis ADTs and events round-trip") {
        withTempDir { dir =>
          val createdAt            = Instant.parse("2026-02-23T10:15:00Z")
          val doc                  = AnalysisDoc(
            id = Ids.AnalysisDocId("analysis-1"),
            workspaceId = "ws-1",
            analysisType = AnalysisType.Custom("release-readiness"),
            content = "Ready for release pending smoke tests.",
            filePath = ".llm4zio/analysis/release.md",
            generatedBy = Ids.AgentId("reviewer"),
            createdAt = createdAt,
            updatedAt = createdAt,
          )
          val event: AnalysisEvent = AnalysisEvent.AnalysisCreated(
            docId = doc.id,
            workspaceId = doc.workspaceId,
            analysisType = doc.analysisType,
            content = doc.content,
            filePath = doc.filePath,
            generatedBy = doc.generatedBy,
            occurredAt = createdAt,
          )

          (for
            data        <- ZIO.service[DataStoreModule.DataStoreService]
            _           <- data.store("foundation:analysis", doc)
            loadedDoc   <- data.fetch[String, AnalysisDoc]("foundation:analysis")
            _           <- data.store("foundation:analysis:event", event)
            loadedEvent <- data.fetch[String, AnalysisEvent]("foundation:analysis:event")
          yield assertTrue(loadedDoc.contains(doc), loadedEvent.contains(event))).provideLayer(layerFor(dir))
        }
      },
    ) @@ TestAspect.sequential
