package workspace.control

import java.nio.file.Files

import zio.*
import zio.test.*

import workspace.entity.RunMode

object CliAgentRunnerSpec extends ZIOSpecDefault:
  private val isWindowsHost = HostPlatform.isWindows()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("CliAgentRunnerSpec")(
    // RunMode.Host
    test("buildArgv for unknown cli tool passes prompt as single arg") {
      val argv = CliAgentRunner.buildArgv("echo", "hello llm4zio", "/tmp/wt")
      assertTrue(
        if isWindowsHost then argv == List("cmd", "/c", "echo", "hello llm4zio")
        else argv == List("echo", "hello llm4zio")
      )
    },
    test("buildArgvForHost uses cmd.exe for echo on Windows") {
      val argv = CliAgentRunner.buildArgvForHost("echo", "hello", "", isWindowsHost = true)
      assertTrue(argv == List("cmd", "/c", "echo", "hello"))
    },
    test("buildArgvForHost maps sh to cmd on Windows") {
      val argv = CliAgentRunner.buildArgvForHost("sh", "echo hi", "", isWindowsHost = true)
      assertTrue(argv == List("cmd", "/c", "echo hi"))
    },
    test("buildArgvForHost maps cmd to sh on Unix hosts") {
      val argv = CliAgentRunner.buildArgvForHost("cmd", "echo hi", "", isWindowsHost = false)
      assertTrue(argv == List("sh", "-lc", "echo hi"))
    },
    test("buildArgv for gemini returns correct args (includes --yolo and --include-directories)") {
      val argv = CliAgentRunner.buildArgv("gemini", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("gemini", "--yolo", "--include-directories", "/tmp/wt", "-p", "fix the bug"))
    },
    test("buildArgv for gemini ignores explicit repoPath and uses worktree for --include-directories") {
      val argv = CliAgentRunner.buildArgv("gemini", "fix the bug", "/tmp/wt", RunMode.Host, "/repo/src")
      assertTrue(argv == List("gemini", "--yolo", "--include-directories", "/tmp/wt", "-p", "fix the bug"))
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
          "--yolo",
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
    test("buildInteractiveArgv for gemini includes --yolo and --include-directories") {
      val argv = CliAgentRunner.buildInteractiveArgv("gemini", "/tmp/wt")
      assertTrue(argv == List("gemini", "--yolo", "--include-directories", "/tmp/wt"))
    },
    test("buildArgv with RunMode.Docker includes env and resource flags") {
      val argv = CliAgentRunner.buildArgv(
        "gemini",
        "fix it",
        "/tmp/wt",
        RunMode.Docker("gemini:latest", Nil, mountWorktree = true, None),
        envVars = Map("A" -> "1"),
        dockerMemoryLimit = Some("2g"),
        dockerCpuLimit = Some("1.5"),
      )
      assertTrue(
        argv.containsSlice(List("--memory", "2g")),
        argv.containsSlice(List("--cpus", "1.5")),
        argv.containsSlice(List("-e", "A=1")),
      )
    },
    test("runProcess with echo collects output line and returns exit 0") {
      for
        cwd              <- ZIO.attemptBlocking(Files.createTempDirectory("cli-runner").toString).orDie
        command           = CliAgentRunner.buildArgvForHost("echo", "line one", "", isWindowsHost)
        result           <- CliAgentRunner.runProcess(command, cwd = cwd)
        (lines, exitCode) = result
      yield assertTrue(exitCode == 0 && lines.exists(_.contains("line one")))
    },
    test("runProcess with failing command returns non-zero exit code") {
      for
        cwd          <- ZIO.attemptBlocking(Files.createTempDirectory("cli-runner-fail").toString).orDie
        command       = if isWindowsHost then List("cmd", "/c", "exit", "1") else List("sh", "-c", "exit 1")
        result       <- CliAgentRunner.runProcess(command, cwd = cwd)
        (_, exitCode) = result
      yield assertTrue(exitCode != 0)
    },
    test("runProcessStreaming invokes callback per line and returns exit 0") {
      for
        cwd      <- ZIO.attemptBlocking(Files.createTempDirectory("cli-runner-stream").toString).orDie
        command   = if isWindowsHost then List("cmd", "/c", "echo first && echo second")
                    else List("sh", "-c", "echo first; echo second")
        received <- zio.Ref.make(List.empty[String])
        exitCode <- CliAgentRunner.runProcessStreaming(
                      command,
                      cwd = cwd,
                      line => received.update(_ :+ line),
                    )
        lines    <- received.get
      yield assertTrue(exitCode == 0 && lines.contains("first") && lines.contains("second"))
    },
    test("runProcessStreaming returns non-zero exit code on failure") {
      for
        cwd      <- ZIO.attemptBlocking(Files.createTempDirectory("cli-runner-stream-fail").toString).orDie
        command   = if isWindowsHost then List("cmd", "/c", "exit", "2") else List("sh", "-c", "exit 2")
        exitCode <- CliAgentRunner.runProcessStreaming(
                      command,
                      cwd = cwd,
                      _ => ZIO.unit,
                    )
      yield assertTrue(exitCode == 2)
    },
  )
