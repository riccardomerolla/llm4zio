package demo.control

import java.nio.file.{ Files, Paths }

import zio.*

import demo.entity.DemoConfig

/** Mock CLI agent runner for demo mode.
  *
  * Replaces `CliAgentRunner.runProcessStreaming` when `demo.enabled=true`. Instead of invoking a real CLI agent, it:
  *   1. Logs a starting message via `onLine`
  *   2. Waits `demo.agentDelaySeconds` seconds
  *   3. Writes a markdown summary file to the worktree
  *   4. Commits the file via `git add && git commit`
  *   5. Logs a completion message and returns exit code 0
  *
  * Exit code 0 triggers the standard completion lifecycle: `RunCompleted` → `completeIssue(success=true)` → issue moves
  * to Review.
  */
object MockAgentRunner:

  /** Build a `runCliAgent`-compatible function using the given `DemoConfig`. */
  def runner(config: DemoConfig): (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
    (_, cwd, onLine, _) => run(cwd, onLine, config.agentDelaySeconds)

  /** Execute the mock agent in `worktreePath`. */
  private def run(
    worktreePath: String,
    onLine: String => Task[Unit],
    delaySeconds: Int,
  ): Task[Int] =
    for
      _ <- onLine("[mock] Starting mock agent execution...")
      _ <- ZIO.sleep(delaySeconds.seconds)
      _ <- writeMockImplementation(worktreePath)
      _ <- gitCommit(worktreePath)
      _ <- onLine("[mock] Mock implementation complete.")
    yield 0

  private def writeMockImplementation(worktreePath: String): Task[Unit] =
    for
      now <- Clock.instant
      _   <- ZIO.attemptBlocking {
               val docsDir  = Paths.get(worktreePath).resolve("docs").resolve("mock-implementation")
               Files.createDirectories(docsDir)
               val filename = s"implementation-${now.toEpochMilli}.md"
               val content  =
                 s"""# Mock Implementation
                    |
                    |**Generated at:** $now
                    |
                    |This file was written by the demo-mode mock agent runner. In a real run this
                    |directory would contain the agent's work product: code, tests, and documentation.
                    |
                    |## Summary
                    |
                    |The mock agent completed its task successfully. All acceptance criteria are
                    |considered met for demo purposes.
                    |
                    |## Notes
                    |
                    |- No real AI calls were made
                    |- Delay was configured for demo pacing
                    |""".stripMargin
               Files.writeString(docsDir.resolve(filename), content)
               ()
             }
    yield ()

  private def gitCommit(worktreePath: String): Task[Unit] =
    ZIO.attemptBlockingIO {
      def run(cmd: String*): Unit =
        val pb   = new ProcessBuilder(cmd*)
        pb.directory(Paths.get(worktreePath).toFile)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.getInputStream.transferTo(java.io.OutputStream.nullOutputStream())
        proc.waitFor()
        ()
      run("git", "add", ".")
      run("git", "commit", "--allow-empty", "-m", "[mock] Add implementation summary")
    }
