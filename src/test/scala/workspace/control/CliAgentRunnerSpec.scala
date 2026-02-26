package workspace.control

import zio.*
import zio.test.*

import workspace.entity.RunMode

object CliAgentRunnerSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("CliAgentRunnerSpec")(
    // RunMode.Host — same as before
    test("buildArgv for echo agent returns [echo, prompt]") {
      val argv = CliAgentRunner.buildArgv("echo", "hello world", "/tmp/wt")
      assertTrue(argv == List("echo", "hello world"))
    },
    test("buildArgv for gemini-cli returns correct args") {
      val argv = CliAgentRunner.buildArgv("gemini-cli", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("gemini", "-p", "fix the bug", "/tmp/wt"))
    },
    test("buildArgv for opencode returns correct args") {
      val argv = CliAgentRunner.buildArgv("opencode", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("opencode", "run", "--prompt", "fix the bug", "/tmp/wt"))
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
    test("buildArgv for unknown agent passes prompt as single arg") {
      val argv = CliAgentRunner.buildArgv("my-agent", "do something", "/tmp/wt")
      assertTrue(argv == List("my-agent", "do something"))
    },
    test("buildArgv with RunMode.Host is identical to default") {
      val argvDefault  = CliAgentRunner.buildArgv("gemini-cli", "fix it", "/tmp/wt")
      val argvExplicit = CliAgentRunner.buildArgv("gemini-cli", "fix it", "/tmp/wt", RunMode.Host)
      assertTrue(argvDefault == argvExplicit)
    },
    test("buildArgv with RunMode.Docker wraps in docker run with mount and workdir") {
      val argv = CliAgentRunner.buildArgv(
        "gemini-cli",
        "fix it",
        "/tmp/wt",
        RunMode.Docker("gemini:latest", Nil, mountWorktree = true, None),
      )
      assertTrue(
        argv == List("docker", "run", "--rm", "-v", "/tmp/wt:/workspace", "--workdir", "/workspace", "gemini:latest",
          "gemini", "-p", "fix it", "/workspace")
      )
    },
    test("buildArgv with RunMode.Docker and network includes --network flag") {
      val argv = CliAgentRunner.buildArgv(
        "gemini-cli",
        "fix it",
        "/tmp/wt",
        RunMode.Docker("gemini:latest", Nil, mountWorktree = true, network = Some("none")),
      )
      assertTrue(argv.containsSlice(List("--network", "none")))
    },
    test("buildArgv with RunMode.Docker and mountWorktree=false omits -v and --workdir") {
      val argv = CliAgentRunner.buildArgv(
        "gemini-cli",
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
  )
