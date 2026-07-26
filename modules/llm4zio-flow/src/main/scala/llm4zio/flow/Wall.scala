package llm4zio.flow

import java.nio.file.Path

import zio.IO

/** The enforced clean-room wall: target-phase flows (implement, verify, review) refuse to run when the target workspace
  * contains anything matching the pack's legacy `sources:` regex, so the coder provably never has legacy source in
  * reach. The breach is a recoverable, typed outcome — the flow decides to abort; only I/O failure fails the effect.
  */
object Wall:

  enum Result:
    case Clean
    case Breached(paths: List[String])

  /** Scan the working tree under `root` for files matching the pack's legacy `sourcesRegex` (relative-path regex).
    * `.git` is exempt — the wall is about the working tree, not repository internals.
    */
  def check(root: Path, sourcesRegex: String): IO[FlowError, Result] =
    SpecChecks.matchingFiles(root, sourcesRegex).map { matches =>
      matches.filterNot(_.startsWith(".git/")) match
        case Nil   => Result.Clean
        case paths => Result.Breached(paths)
    }
