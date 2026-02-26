package workspace.control
import java.nio.file.Paths

import zio.*

import workspace.entity.RunMode

object CliAgentRunner:

  /** Map agent name → argv list for host execution. `worktreePath` is passed as the working directory for agents that
    * use cwd, or as an explicit argument for agents that require it.
    */
  private def buildArgvForHost(agentName: String, prompt: String, worktreePath: String): List[String] =
    agentName match
      case "gemini-cli" => List("gemini", "-p", prompt, worktreePath)
      case "opencode"   => List("opencode", "run", "--prompt", prompt, worktreePath)
      case "claude"     => List("claude", "--print", prompt)
      case "codex"      => List("codex", prompt)
      case "copilot"    => List("gh", "copilot", "suggest", "-t", "shell", prompt)
      case other        => List(other, prompt)

  /** Build the full argv list, wrapping in `docker run` when `runMode` is `RunMode.Docker`. */
  def buildArgv(
    agentName: String,
    prompt: String,
    worktreePath: String,
    runMode: RunMode = RunMode.Host,
  ): List[String] =
    runMode match
      case RunMode.Host =>
        buildArgvForHost(agentName, prompt, worktreePath)
      case RunMode.Docker(image, extraArgs, mountWorktree, network) =>
        val containerPath = if mountWorktree then "/workspace" else worktreePath
        val innerArgv     = buildArgvForHost(agentName, prompt, containerPath)
        val mountFlags    = if mountWorktree then List("-v", s"$worktreePath:/workspace", "--workdir", "/workspace")
                            else List.empty
        val networkFlags  = network.map(n => List("--network", n)).getOrElse(List.empty)
        List("docker", "run", "--rm") ++ mountFlags ++ networkFlags ++ extraArgs ++ List(image) ++ innerArgv

  /** Run argv as a subprocess with `cwd` as working directory. Returns (stdout+stderr lines, exit code). Merges stderr
    * into stdout via redirectErrorStream. Runs blocking IO on ZIO's blocking thread pool.
    */
  def runProcess(argv: List[String], cwd: String): Task[(List[String], Int)] =
    ZIO.attemptBlockingIO {
      val pb       = new ProcessBuilder(argv*)
      pb.directory(Paths.get(cwd).toFile)
      pb.redirectErrorStream(true)
      val process  = pb.start()
      val lines    = scala.io.Source.fromInputStream(process.getInputStream).getLines().toList
      val exitCode = process.waitFor()
      (lines, exitCode)
    }
