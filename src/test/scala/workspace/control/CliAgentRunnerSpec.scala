package workspace.control

import zio.*
import zio.test.*

import workspace.entity.RunMode

object CliAgentRunnerSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("CliAgentRunnerSpec")(
    // RunMode.Host
    test("buildArgv for unknown cli tool passes prompt as single arg") {
      val argv = CliAgentRunner.buildArgv("echo", "hello world", "/tmp/wt")
      assertTrue(argv == List("echo", "hello world"))
    },
    test("buildArgv for gemini returns correct args (includes --include-directories)") {
      val argv = CliAgentRunner.buildArgv("gemini", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("gemini", "--include-directories", "/tmp/wt", "-p", "fix the bug"))
    },
    test("buildArgv for gemini passes explicit repoPath to --include-directories") {
      val argv = CliAgentRunner.buildArgv("gemini", "fix the bug", "/tmp/wt", RunMode.Host, "/repo/src")
      assertTrue(argv == List("gemini", "--include-directories", "/repo/src", "-p", "fix the bug"))
    },
    test("buildArgv for opencode returns correct args") {
      val argv = CliAgentRunner.buildArgv("opencode", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("opencode", "run", "--prompt", "fix the bug"))
    },
    test("buildArgv for claude returns correct args") {
      val argv = CliAgentRunner.buildArgv("claude", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("claude", "--print", "fix the bug"))
    },
    test("buildArgv for codex returns correct args") {
      val argv = CliAgentRunner.buildArgv("codex", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("codex", "fix the bug"))
    },
    test("buildArgv for copilot returns correct args") {
      val argv = CliAgentRunner.buildArgv("copilot", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("gh", "copilot", "suggest", "-t", "shell", "fix the bug"))
    },
    test("buildArgv for custom tool falls through to [tool, prompt]") {
      val argv = CliAgentRunner.buildArgv("my-tool", "do something", "/tmp/wt")
      assertTrue(argv == List("my-tool", "do something"))
    },
    test("interactionSupport marks unsupported CLIs as continuation-only") {
      assertTrue(
        CliAgentRunner.interactionSupport("opencode") == CliAgentRunner.InteractionSupport.ContinuationOnly,
        CliAgentRunner.interactionSupport("copilot") == CliAgentRunner.InteractionSupport.ContinuationOnly,
      )
    },
    test("buildInteractiveArgv for claude uses non --print mode") {
      val argv = CliAgentRunner.buildInteractiveArgv("claude", "/tmp/wt")
      assertTrue(argv == List("claude"))
    },
    test("buildArgv with RunMode.Host is identical to default") {
      val argvDefault  = CliAgentRunner.buildArgv("gemini", "fix it", "/tmp/wt")
      val argvExplicit = CliAgentRunner.buildArgv("gemini", "fix it", "/tmp/wt", RunMode.Host)
      assertTrue(argvDefault == argvExplicit)
    },
    test("buildArgv with RunMode.Docker wraps in docker run with mount and workdir") {
      val argv = CliAgentRunner.buildArgv(
        "gemini",
        "fix it",
        "/tmp/wt",
        RunMode.Docker("gemini:latest", Nil, mountWorktree = true, None),
      )
      assertTrue(
        argv == List(
          "docker",
          "run",
          "--rm",
          "-i",
          "-v",
          "/tmp/wt:/workspace",
          "--workdir",
          "/workspace",
          "gemini:latest",
          "gemini",
          "--include-directories",
          "/tmp/wt",
          "-p",
          "fix it",
        )
      )
    },
    test("buildArgv with RunMode.Docker and network includes --network flag") {
      val argv = CliAgentRunner.buildArgv(
        "gemini",
        "fix it",
        "/tmp/wt",
        RunMode.Docker("gemini:latest", Nil, mountWorktree = true, network = Some("none")),
      )
      assertTrue(argv.containsSlice(List("--network", "none")) && argv.contains("-i"))
    },
    test("buildArgv with RunMode.Docker and mountWorktree=false omits -v and --workdir") {
      val argv = CliAgentRunner.buildArgv(
        "gemini",
        "fix it",
        "/tmp/wt",
        RunMode.Docker("gemini:latest", Nil, mountWorktree = false, None),
      )
      assertTrue(!argv.contains("-v") && !argv.contains("--workdir"))
    },
    test("runProcess with echo collects output line and returns exit 0") {
      for
        result           <- CliAgentRunner.runProcess(List("echo", "line one"), cwd = "/tmp")
        (lines, exitCode) = result
      yield assertTrue(exitCode == 0 && lines.exists(_.contains("line one")))
    },
    test("runProcess with false command returns non-zero exit code") {
      for
        result       <- CliAgentRunner.runProcess(List("sh", "-c", "exit 1"), cwd = "/tmp")
        (_, exitCode) = result
      yield assertTrue(exitCode != 0)
    },
    test("runProcessStreaming invokes callback per line and returns exit 0") {
      for
        received <- zio.Ref.make(List.empty[String])
        exitCode <- CliAgentRunner.runProcessStreaming(
                      List("sh", "-c", "echo first; echo second"),
                      cwd = "/tmp",
                      line => received.update(_ :+ line),
                    )
        lines    <- received.get
      yield assertTrue(exitCode == 0 && lines.contains("first") && lines.contains("second"))
    },
    test("runProcessStreaming returns non-zero exit code on failure") {
      for
        exitCode <- CliAgentRunner.runProcessStreaming(
                      List("sh", "-c", "exit 2"),
                      cwd = "/tmp",
                      _ => ZIO.unit,
                    )
      yield assertTrue(exitCode == 2)
    },
  )
