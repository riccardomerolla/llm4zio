package llm4zio.flow

import zio.Scope
import zio.test.*

/** Pure git argv + non-interactive-env helpers (the parts of [[GitTool]] that don't spawn a process). */
object GitArgsSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("GitTool args & env")(
    test("nonInteractiveEnv disables git and ssh interactive prompts") {
      val env = GitTool.nonInteractiveEnv
      assertTrue(
        env.get("GIT_TERMINAL_PROMPT").contains("0"),
        env.getOrElse("GIT_SSH_COMMAND", "").contains("-o BatchMode=yes"),
      )
    },
    test("remoteHost parses scp-like and url forms; None for local paths") {
      assertTrue(
        GitTool.remoteHost("git@github.com:me/repo.git").contains("github.com"),
        GitTool.remoteHost("https://github.com/me/repo.git").contains("github.com"),
        GitTool.remoteHost("ssh://git@github.com/me/repo.git").contains("github.com"),
        GitTool.remoteHost("/local/path/repo.git").isEmpty,
      )
    },
    test("isGithubRemote detects github across ssh and https forms") {
      assertTrue(
        GitTool.isGithubRemote("git@github.com:me/repo.git"),
        GitTool.isGithubRemote("https://github.com/me/repo.git"),
        GitTool.isGithubRemote("ssh://git@github.com/me/repo.git"),
        !GitTool.isGithubRemote("git@gitlab.com:me/repo.git"),
        !GitTool.isGithubRemote("https://github.example.com/me/repo.git"),
        !GitTool.isGithubRemote("/local/path/repo.git"),
      )
    },
    test("pushArgs adds no credential helper for a non-github or unknown remote") {
      assertTrue(
        GitTool.pushArgs("origin", "feat", Some("git@gitlab.com:me/repo.git"), Some("tok")) ==
          List("push", "-u", "origin", "feat"),
        GitTool.pushArgs("origin", "feat", None, Some("tok")) ==
          List("push", "-u", "origin", "feat"),
      )
    },
    test("pushArgs appends a github-scoped gh-fallback helper when no token is in the env") {
      val args = GitTool.pushArgs("origin", "main", Some("https://github.com/me/repo.git"), None)
      assertTrue(
        args.take(2) == List("-c", "credential.https://github.com.helper=!gh auth git-credential"),
        args.endsWith(List("push", "-u", "origin", "main")),
      )
    },
    test("pushArgs uses an env-token-direct helper that never inlines the token value") {
      val args   = GitTool.pushArgs("origin", "main", Some("https://github.com/me/repo.git"), Some("secret-tok"))
      val helper = args(1)
      assertTrue(
        args.head == "-c",
        helper.startsWith("credential.https://github.com.helper=!"),
        helper.contains("x-access-token"),
        helper.contains("GH_TOKEN"),
        !helper.contains("secret-tok"), // token is read from the env at helper runtime, never placed in argv/logs
        args.endsWith(List("push", "-u", "origin", "main")),
      )
    },
  )
