package board.control

import java.nio.file.{ Files as JFiles, Path }

import zio.*
import zio.json.*
import zio.stream.Stream
import zio.test.*

import app.control.FileService
import board.entity.*
import llm4zio.core.{ LlmChunk, LlmError, LlmService, ToolCallResponse }
import llm4zio.tools.{ AnyTool, JsonSchema }
import shared.ids.Ids.BoardIssueId

object IssueCreationWizardSpec extends ZIOSpecDefault:

  final private case class RecordingBoardRepo(createdRef: Ref[List[BoardIssue]]) extends BoardRepository:
    override def initBoard(workspacePath: String): IO[BoardError, Unit] = ZIO.unit

    override def readBoard(workspacePath: String): IO[BoardError, Board] =
      createdRef.get.map { issues =>
        Board(
          workspacePath = workspacePath,
          columns = BoardColumn.values.map(column => column -> issues.filter(_.column == column)).toMap,
        )
      }

    override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue] =
      createdRef.get.flatMap(issues =>
        ZIO
          .fromOption(issues.find(_.frontmatter.id == issueId))
          .orElseFail(BoardError.IssueNotFound(issueId.value))
      )

    override def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue)
      : IO[BoardError, BoardIssue] =
      val created = issue.copy(column = column)
      createdRef.update(_ :+ created).as(created)

    override def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn)
      : IO[BoardError, BoardIssue] =
      ZIO.fail(BoardError.ParseError("moveIssue unused in IssueCreationWizardSpec"))

    override def updateIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      update: IssueFrontmatter => IssueFrontmatter,
    ): IO[BoardError, BoardIssue] =
      ZIO.fail(BoardError.ParseError("updateIssue unused in IssueCreationWizardSpec"))

    override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
      ZIO.unit

    override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
      createdRef.get.map(_.filter(_.column == column))

  final private case class JsonLlmStub(
    responsesRef: Ref[List[String]],
    promptsRef: Ref[List[String]],
  ) extends LlmService:
    override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
      zio.stream.ZStream.empty

    override def executeStreamWithHistory(messages: List[llm4zio.core.Message]): Stream[LlmError, LlmChunk] =
      zio.stream.ZStream.empty

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.ProviderError("executeWithTools unused"))

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      for
        _          <- promptsRef.update(_ :+ prompt)
        response   <- responsesRef.modify {
                        case head :: tail => (head, tail)
                        case Nil          => ("", Nil)
                      }
        structured <-
          if response.trim.isEmpty then ZIO.fail(LlmError.ProviderError("No structured response queued"))
          else
            ZIO
              .fromEither(response.fromJson[A])
              .mapError(err => LlmError.ParseError(err, response))
      yield structured

    override def isAvailable: UIO[Boolean] = ZIO.succeed(true)

  private def deleteRecursively(path: Path): Task[Unit] =
    ZIO.attemptBlocking {
      val stream = JFiles.walk(path)
      try
        stream.sorted(java.util.Comparator.reverseOrder()).forEach(p =>
          JFiles.deleteIfExists(p)
          ()
        )
      finally stream.close()
    }.unit

  private def withTempDir[E, A](name: String)(effect: Path => ZIO[Any, E, A]): ZIO[Any, E, A] =
    for
      dir    <- ZIO.attempt(JFiles.createTempDirectory(name)).orDie
      result <- effect(dir).ensuring(deleteRecursively(dir).orDie)
    yield result

  private def makeWizard(
    responses: List[String]
  ): ZIO[FileService, Nothing, (IssueCreationWizardLive, Ref[List[String]], Ref[List[BoardIssue]], FileService)] =
    for
      responsesRef <- Ref.make(responses)
      promptsRef   <- Ref.make(List.empty[String])
      createdRef   <- Ref.make(List.empty[BoardIssue])
      llm           = JsonLlmStub(responsesRef, promptsRef)
      boardRepo     = RecordingBoardRepo(createdRef)
      parser        = IssueMarkdownParserLive()
      fileService  <- ZIO.service[FileService]
      wizard        = IssueCreationWizardLive(
                        llmService = llm,
                        boardRepository = boardRepo,
                        issueMarkdownParser = parser,
                        fileService = fileService,
                        stateRef = zio.Unsafe.unsafe(implicit u => Ref.Synchronized.unsafe.make(Map.empty)),
                      )
    yield (wizard, promptsRef, createdRef, fileService)

  private val naturalBatchJson: String =
    """
      |{
      |  "summary": "Generated two issues from the request.",
      |  "issues": [
      |    {
      |      "id": "fix-auth-timeout",
      |      "title": "Fix auth timeout",
      |      "priority": "high",
      |      "assignedAgent": "agent-a",
      |      "requiredCapabilities": ["scala", "zio"],
      |      "blockedBy": [],
      |      "tags": ["auth", "backend"],
      |      "acceptanceCriteria": ["Timeout is configurable", "Regression tests pass"],
      |      "estimate": "M",
      |      "proofOfWork": ["unit-tests"],
      |      "body": "# Fix auth timeout\n\nRefactor timeout handling."
      |    },
      |    {
      |      "id": "refactor-session-service",
      |      "title": "Refactor session service",
      |      "priority": "medium",
      |      "assignedAgent": null,
      |      "requiredCapabilities": ["scala"],
      |      "blockedBy": ["fix-auth-timeout"],
      |      "tags": ["refactor"],
      |      "acceptanceCriteria": ["No behavior regressions"],
      |      "estimate": "L",
      |      "proofOfWork": ["diff-stat"],
      |      "body": "# Refactor session service\n\nSplit large methods."
      |    }
      |  ]
      |}
      |""".stripMargin

  private val analysisBatchJson: String =
    """
      |{
      |  "summary": "Suggested one code-analysis issue.",
      |  "issues": [
      |    {
      |      "id": "add-auth-tests",
      |      "title": "Add auth module tests",
      |      "priority": "critical",
      |      "assignedAgent": null,
      |      "requiredCapabilities": ["scala", "testing"],
      |      "blockedBy": [],
      |      "tags": ["testing", "auth"],
      |      "acceptanceCriteria": ["Edge cases covered", "Coverage above 80%"],
      |      "estimate": "S",
      |      "proofOfWork": ["coverage-report"],
      |      "body": "# Add auth tests\n\nFocus on boundary conditions."
      |    }
      |  ]
      |}
      |""".stripMargin

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("IssueCreationWizardSpec")(
      test("natural language mode supports batch preview/edit/confirm") {
        withTempDir("issue-wizard-natural") { workspace =>
          for
            (wizard, _, createdRef, _) <- makeWizard(List(naturalBatchJson)).provideLayer(FileService.live)
            session                    <- wizard.startNaturalLanguage(workspace.toString, "Fix timeout and refactor sessions")
            _                          <- wizard.updatePreview(
                                            session.sessionId,
                                            session.preview.copy(
                                              drafts = session.preview.drafts.zipWithIndex.map {
                                                case (draft, idx) =>
                                                  if idx == 1 then draft.copy(included = false) else draft
                                              }
                                            ),
                                          )
            result                     <- wizard.confirm(session.sessionId)
            created                    <- createdRef.get
          yield assertTrue(
            session.preview.drafts.size == 2,
            result.issueIds.map(_.value) == List("fix-auth-timeout"),
            created.size == 1,
            created.head.column == BoardColumn.Backlog,
            created.head.frontmatter.priority == IssuePriority.High,
          )
        }
      },
      test("code analysis mode reads scoped files and generates issue suggestions") {
        withTempDir("issue-wizard-analysis") { workspace =>
          val scope = workspace.resolve("src/main/scala/auth")
          for
            _                          <- ZIO.attemptBlocking(JFiles.createDirectories(scope)).unit
            _                          <- ZIO.attemptBlocking(
                                            JFiles.writeString(scope.resolve("AuthService.scala"), "object AuthService { def x = 1 }")
                                          ).unit
            (wizard, promptsRef, _, _) <- makeWizard(List(analysisBatchJson)).provideLayer(FileService.live)
            session                    <- wizard.startCodeAnalysis(workspace.toString, scope.toString)
            _                          <- wizard.confirm(session.sessionId)
            prompts                    <- promptsRef.get
          yield assertTrue(
            session.preview.drafts.nonEmpty,
            prompts.exists(_.contains("Scope:")),
            prompts.exists(_.contains("AuthService.scala")),
          )
        }
      },
      test("template mode lists templates, interpolates placeholders, and errors on missing placeholders") {
        withTempDir("issue-wizard-template") { workspace =>
          val templatesDir = workspace.resolve(".board/templates")
          val templateFile = templatesDir.resolve("tests.md")
          val templateBody =
            """---
              |id: {{id}}
              |title: Add tests for {{module}}
              |priority: medium
              |assignedAgent:
              |requiredCapabilities: [scala, zio, testing]
              |blockedBy: []
              |tags: [testing, {{module}}]
              |acceptanceCriteria: [Test coverage above 80%]
              |estimate: S
              |proofOfWork: [coverage]
              |transientState: none
              |branchName:
              |failureReason:
              |completedAt:
              |createdAt: 2026-03-20T10:00:00Z
              |---
              |
              |# Add tests for {{module}}
              |
              |Implement tests for module {{module}}.
              |""".stripMargin

          for
            _                          <- ZIO.attemptBlocking(JFiles.createDirectories(templatesDir)).unit
            _                          <- ZIO.attemptBlocking(JFiles.writeString(templateFile, templateBody)).unit
            (wizard, _, createdRef, _) <- makeWizard(Nil).provideLayer(FileService.live)
            templates                  <- wizard.listTemplates(workspace.toString)
            session                    <- wizard.startTemplate(
                                            workspace.toString,
                                            "tests.md",
                                            Map("id" -> "add-auth-tests-template", "module" -> "auth"),
                                          )
            _                          <- wizard.confirm(session.sessionId)
            created                    <- createdRef.get
            missing                    <- wizard
                                            .startTemplate(workspace.toString, "tests.md", Map("module" -> "auth"))
                                            .exit
          yield assertTrue(
            templates == List("tests.md"),
            session.preview.drafts.head.frontmatter.id.value == "add-auth-tests-template",
            session.preview.drafts.head.frontmatter.tags.contains("auth"),
            created.map(_.frontmatter.id.value) == List("add-auth-tests-template"),
            missing.isFailure,
          )
        }
      },
    )
