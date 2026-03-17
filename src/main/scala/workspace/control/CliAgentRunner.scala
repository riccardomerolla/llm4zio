package workspace.control

import java.nio.file.Paths

import zio.*

import workspace.entity.RunMode

object CliAgentRunner:

  enum InteractionSupport:
    case InteractiveStdin, ContinuationOnly

  /** Normalise a prompt for Windows `cmd.exe` execution.
    *
    * When a process is launched via `cmd /c <tool> -p <prompt>`, `cmd.exe` interprets literal newline characters as
    * command separators, truncating the argument list at the first newline. Collapsing all newline sequences to a
    * single space avoids this while keeping the prompt intelligible to the LLM.
    */
  private[workspace] def normalizePromptForCmd(prompt: String): String =
    prompt.replaceAll("""\r\n|\n|\r""", " ")

  def interactionSupport(cliTool: String): InteractionSupport =
    cliTool.toLowerCase match
      // Supports stdin conversation when not using --print.
      case "claude"                        => InteractionSupport.InteractiveStdin
      // Gemini CLI supports interactive sessions; we still pass include-directories.
      case "gemini"                        => InteractionSupport.InteractiveStdin
      // Conservative defaults for tools where stdin conversation is not guaranteed.
      case "opencode" | "copilot"          => InteractionSupport.ContinuationOnly
      case "codex" | "echo" | "sh" | "cmd" => InteractionSupport.InteractiveStdin
      case "powershell" | "pwsh"           => InteractionSupport.InteractiveStdin
      case _                               => InteractionSupport.ContinuationOnly

  /** Map CLI tool name → argv list for host execution. `cliTool` is the binary to invoke (e.g. "claude", "gemini",
    * "opencode"). All agents use the process `cwd` (set to `worktreePath`) for directory context.
    *
    * For `gemini`, the original repository path is passed via `--include-directories` so the sandbox allows access to
    * both the worktree checkout and the source repository root.
    */
  private[workspace] def buildArgvForHost(
    cliTool: String,
    prompt: String,
    repoPath: String,
    isWindowsHost: Boolean,
  ): List[String] =
    cliTool match
      case "gemini" if isWindowsHost     =>
        List(
          "cmd",
          "/c",
          "gemini",
          "--yolo",
          "--include-directories",
          repoPath,
          "-p",
          normalizePromptForCmd(prompt),
        )
      case "gemini"                      => List("gemini", "--yolo", "--include-directories", repoPath, "-p", prompt)
      case "opencode" if isWindowsHost   =>
        List("cmd", "/c", "opencode", "run", "--prompt", normalizePromptForCmd(prompt))
      case "opencode"                    => List("opencode", "run", "--prompt", prompt)
      case "claude" if isWindowsHost     => List("cmd", "/c", "claude", "--print", normalizePromptForCmd(prompt))
      case "claude"                      => List("claude", "--print", prompt)
      case "codex" if isWindowsHost      => List("cmd", "/c", "codex", normalizePromptForCmd(prompt))
      case "codex"                       => List("codex", prompt)
      case "copilot" if isWindowsHost    =>
        List("cmd", "/c", "gh", "copilot", "suggest", "-t", "shell", normalizePromptForCmd(prompt))
      case "copilot"                     => List("gh", "copilot", "suggest", "-t", "shell", prompt)
      case "echo" if isWindowsHost       => List("cmd", "/c", "echo", prompt)
      case "sh" if isWindowsHost         => List("cmd", "/c", prompt)
      case "cmd" if !isWindowsHost       => List("sh", "-lc", prompt)
      case "powershell" if isWindowsHost => List("powershell", "-Command", prompt)
      case "pwsh" if isWindowsHost       => List("pwsh", "-Command", prompt)
      case other                         => List(other, prompt)

  /** Host argv for interactive sessions.
    *
    * For tools that do not support stdin conversations, callers should degrade to continuation runs.
    */
  private[workspace] def buildInteractiveArgvForHost(
    cliTool: String,
    repoPath: String,
    isWindowsHost: Boolean,
  ): List[String] =
    cliTool match
      case "gemini" if isWindowsHost     =>
        List("cmd", "/c", "gemini", "--yolo", "--include-directories", repoPath)
      case "gemini"                      => List("gemini", "--yolo", "--include-directories", repoPath)
      case "claude" if isWindowsHost     => List("cmd", "/c", "claude")
      case "claude"                      => List("claude")
      case "codex" if isWindowsHost      => List("cmd", "/c", "codex")
      case "codex"                       => List("codex")
      case "opencode" if isWindowsHost   => List("cmd", "/c", "opencode", "run")
      case "opencode"                    => List("opencode", "run")
      case "sh" if isWindowsHost         => List("cmd")
      case "powershell" if isWindowsHost => List("powershell")
      case "pwsh" if isWindowsHost       => List("pwsh")
      case other                         => List(other)

  /** Build the full argv list, wrapping in `docker run` when `runMode` is `RunMode.Docker`. `cliTool` is the CLI binary
    * to invoke (from the workspace's `cliTool` setting). `repoPath` is the original workspace `localPath`; it is added
    * as an `--include-directories` argument for tools (like `gemini`) that sandbox filesystem access to the CWD.
    */
  def buildArgv(
    cliTool: String,
    prompt: String,
    worktreePath: String,
    runMode: RunMode = RunMode.Host,
    repoPath: String = "",
    envVars: Map[String, String] = Map.empty,
    dockerMemoryLimit: Option[String] = None,
    dockerCpuLimit: Option[String] = None,
  ): List[String] =
    val effectiveIncludePath =
      if cliTool == "gemini" then worktreePath
      else if repoPath.nonEmpty then repoPath
      else worktreePath
    val isWindowsHost        = HostPlatform.isWindows()
    runMode match
      case RunMode.Host                                             =>
        buildArgvForHost(cliTool, prompt, effectiveIncludePath, isWindowsHost)
      case RunMode.Docker(image, extraArgs, mountWorktree, network) =>
        // Docker containers always run a Linux image; never apply Windows cmd-wrapping for the inner argv.
        val innerArgv     = buildArgvForHost(cliTool, prompt, effectiveIncludePath, isWindowsHost = false)
        val mountFlags    = if mountWorktree then List("-v", s"$worktreePath:/workspace", "--workdir", "/workspace")
        else List.empty
        val networkFlags  = network.map(n => List("--network", n)).getOrElse(List.empty)
        val envFlags      = envVars.toList.sortBy(_._1).flatMap((k, v) => List("-e", s"$k=$v"))
        val resourceFlags =
          dockerMemoryLimit.map(v => List("--memory", v)).getOrElse(Nil) ++
            dockerCpuLimit.map(v => List("--cpus", v)).getOrElse(Nil)
        List(
          "docker",
          "run",
          "--rm",
          "-i",
        ) ++ mountFlags ++ networkFlags ++ resourceFlags ++ envFlags ++ extraArgs ++ List(
          image
        ) ++ innerArgv

  /** Build argv for long-lived interactive sessions.
    *
    * Callers should check [[interactionSupport]] and degrade to continuation-only if needed.
    */
  def buildInteractiveArgv(
    cliTool: String,
    worktreePath: String,
    runMode: RunMode = RunMode.Host,
    repoPath: String = "",
  ): List[String] =
    val effectiveIncludePath =
      if cliTool == "gemini" then worktreePath
      else if repoPath.nonEmpty then repoPath
      else worktreePath
    val isWindowsHost        = HostPlatform.isWindows()
    runMode match
      case RunMode.Host                                             =>
        buildInteractiveArgvForHost(cliTool, effectiveIncludePath, isWindowsHost)
      case RunMode.Docker(image, extraArgs, mountWorktree, network) =>
        // Docker containers always run a Linux image; never apply Windows cmd-wrapping for the inner argv.
        val innerArgv    = buildInteractiveArgvForHost(cliTool, effectiveIncludePath, isWindowsHost = false)
        val mountFlags   = if mountWorktree then List("-v", s"$worktreePath:/workspace", "--workdir", "/workspace")
        else List.empty
        val networkFlags = network.map(n => List("--network", n)).getOrElse(List.empty)
        List("docker", "run", "--rm", "-i") ++ mountFlags ++ networkFlags ++ extraArgs ++ List(image) ++ innerArgv

  /** Run argv as a subprocess with `cwd` as working directory. Returns (stdout+stderr lines, exit code). Merges stderr
    * into stdout via redirectErrorStream. Runs blocking IO on ZIO's blocking thread pool.
    */
  def runProcess(argv: List[String], cwd: String, envVars: Map[String, String] = Map.empty): Task[(List[String], Int)] =
    ZIO.attemptBlockingIO {
      val pb       = new ProcessBuilder(argv*)
      pb.directory(Paths.get(cwd).toFile)
      envVars.foreach { case (k, v) => pb.environment().put(k, v) }
      pb.redirectErrorStream(true)
      val process  = pb.start()
      val lines    = scala.io.Source.fromInputStream(process.getInputStream).getLines().toList
      val exitCode = process.waitFor()
      (lines, exitCode)
    }

  /** Run argv as a subprocess, calling `onLine` for each line of output as it is produced. stderr is merged into stdout
    * via redirectErrorStream. Returns the exit code. Runs on ZIO's blocking thread pool. The process is forcibly
    * destroyed if the effect is interrupted or an error occurs.
    *
    * Uses `ZIO.attemptBlockingCancelable` for `readLine` so that fiber interruption immediately kills the process (via
    * `destroyForcibly`) which unblocks the blocking read instead of waiting for the process to finish naturally.
    */
  def runProcessStreaming(
    argv: List[String],
    cwd: String,
    onLine: String => Task[Unit],
    envVars: Map[String, String] = Map.empty,
  ): Task[Int] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlockingIO {
        val pb = new ProcessBuilder(argv*)
        pb.directory(Paths.get(cwd).toFile)
        envVars.foreach { case (k, v) => pb.environment().put(k, v) }
        pb.redirectErrorStream(true)
        pb.start()
      }
    )(process => ZIO.succeedBlocking(process.destroyForcibly()).ignore) { process =>
      val reader           = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream))
      def loop: Task[Unit] =
        ZIO
          .attemptBlockingCancelable(Option(reader.readLine()))(ZIO.succeedBlocking(process.destroyForcibly()).ignore)
          .flatMap {
            case None       => ZIO.unit
            case Some(line) => onLine(line) *> loop
          }
      loop *> ZIO.attemptBlockingIO(process.waitFor())
    }
