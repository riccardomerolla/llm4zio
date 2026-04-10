package integration

import java.nio.file.{ Files as JFiles, Path }
import java.time.Instant

import zio.*
import zio.json.*
import zio.process.Command
import zio.stream.ZStream

import board.control.{ BoardRepositoryFS, IssueMarkdownParserLive }
import governance.control.{ GovernanceEvaluationContext, GovernanceTransitionDecision }
import governance.control.GovernancePolicyService
import governance.entity.GovernancePolicy
import llm4zio.core.{ LlmChunk, LlmError, LlmService, Message, ToolCallResponse }
import llm4zio.tools.{ AnyTool, JsonSchema }
import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId
import workspace.control.GitServiceLive
import workspace.entity.*

/** Shared helpers for integration tests that exercise the board, orchestrator and analysis pipeline against a real
  * temporary git repository.
  */
object IntegrationFixtures:

  // ── Git helpers ─────────────────────────────────────────────────────────────

  def gitRun(cwd: Path, args: String*): Task[Unit] =
    Command(args.head, args.drop(1)*).workingDirectory(cwd.toFile).string.unit

  def gitOutput(cwd: Path, args: String*): Task[String] =
    Command(args.head, args.drop(1)*).workingDirectory(cwd.toFile).string

  def deleteRecursively(path: Path): Task[Unit] =
    ZIO.attemptBlocking {
      val stream = JFiles.walk(path)
      try
        stream.sorted(java.util.Comparator.reverseOrder()).forEach { p =>
          val _ = JFiles.deleteIfExists(p)
        }
      finally stream.close()
    }.unit

  // ── Shared git repo fixture ──────────────────────────────────────────────────
  // acquireRelease: creates a temp dir, git-inits with main branch,
  // writes src/HelloWorld.scala, initial commit. Deletes on scope exit.

  val initGitRepo: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      for
        dir <- ZIO.attempt(JFiles.createTempDirectory("integration-spec-"))
        _   <- gitRun(dir, "git", "init", "--initial-branch=main")
                 .orElse(gitRun(dir, "git", "init") *> gitRun(dir, "git", "checkout", "-b", "main"))
        _   <- gitRun(dir, "git", "config", "user.name", "spec-user")
        _   <- gitRun(dir, "git", "config", "user.email", "spec@example.com")
        src  = dir.resolve("src")
        _   <- ZIO.attemptBlocking {
                 JFiles.createDirectories(src)
                 JFiles.writeString(
                   src.resolve("HelloWorld.scala"),
                   s"""|object HelloWorld:
                       |  def main(args: Array[String]): Unit =
                       |    println("Hello, World!")
                       |""".stripMargin,
                 )
               }
        _   <- gitRun(dir, "git", "add", ".")
        _   <- gitRun(dir, "git", "commit", "-m", "initial: Hello World")
      yield dir
    )(dir => deleteRecursively(dir).orDie)

  // ── Board repo builder ───────────────────────────────────────────────────────

  def boardRepoFor(git: GitServiceLive, parser: IssueMarkdownParserLive): UIO[BoardRepositoryFS] =
    Ref.make(Map.empty[String, Semaphore]).map(ref => BoardRepositoryFS(parser, git, ref))

  // ── LLM stub ────────────────────────────────────────────────────────────────
  // Ref-backed queue: executeStructured pops and decodes pre-canned JSON strings.

  def stubLlm(jsonResponses: List[String]): UIO[LlmService] =
    Ref.make(jsonResponses).map { ref =>
      new LlmService:
        override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
          ZStream.empty

        override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.fail(LlmError.ProviderError("executeWithTools not used in this test"))

        override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          for
            raw    <- ref.modify { case h :: t => (h, t); case Nil => ("", Nil) }
            result <- ZIO
                        .fromEither(raw.fromJson[A])
                        .mapError(msg => LlmError.ParseError(msg, raw))
          yield result

        override def isAvailable: UIO[Boolean] = ZIO.succeed(true)
    }

  // ── Workspace builder ────────────────────────────────────────────────────────

  def minimalWorkspace(id: String, path: Path): Workspace =
    Workspace(
      id = id,
      projectId = ProjectId("test-project"),
      name = id,
      localPath = path.toString,
      defaultAgent = Some("codex"),
      description = None,
      enabled = true,
      runMode = RunMode.Host,
      cliTool = "codex",
      createdAt = Instant.now(),
      updatedAt = Instant.now(),
    )

  // ── NoOpGovernancePolicyService ───────────────────────────────────────────
  // Always allows all transitions (no governance rules enforced).

  object NoOpGovernancePolicyService extends GovernancePolicyService:
    override def resolvePolicyForWorkspace(workspaceId: String): IO[PersistenceError, GovernancePolicy] =
      ZIO.succeed(GovernancePolicy.noOp)
    override def evaluateForWorkspace(
      workspaceId: String,
      context: GovernanceEvaluationContext,
    ): IO[PersistenceError, GovernanceTransitionDecision] =
      ZIO.succeed(
        GovernanceTransitionDecision(
          allowed = true,
          requiredGates = Set.empty,
          missingGates = Set.empty,
          humanApprovalRequired = false,
          daemonTriggers = Nil,
          escalationRules = Nil,
          completionCriteria = None,
          reason = None,
        )
      )
