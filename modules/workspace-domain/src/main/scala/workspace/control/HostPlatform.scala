package workspace.control

import java.nio.file.{ Path, Paths }

object HostPlatform:

  def isWindows(osName: String = System.getProperty("os.name", "")): Boolean =
    osName.toLowerCase.contains("win")

  def defaultWorktreeRoot(
    userHome: String = sys.props.getOrElse("user.home", "."),
    localAppData: Option[String] = sys.env.get("LOCALAPPDATA"),
    osName: String = System.getProperty("os.name", ""),
  ): Path =
    if isWindows(osName) then
      localAppData
        .map(Paths.get(_, "llm4zio", "agent-worktrees"))
        .getOrElse(Paths.get(userHome, "AppData", "Local", "llm4zio", "agent-worktrees"))
    else Paths.get(userHome, ".cache", "agent-worktrees")
