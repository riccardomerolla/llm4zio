package llm4zio.runner

import java.nio.file.Path

/** The parsed form of a flow script's CLI args. A small, closed set owned by [[flow]]: a prompt source — positional
  * text, or a file via `--prompt-file <path>` / `--prompt-file=<path>` / `@<path>` sugar. Anything beyond this closed
  * set, a script reads from raw `args` itself.
  *
  * Precedence between sources is resolved later (when the file is actually read): an explicit `--prompt-file` wins over
  * positional text, which wins over the script default. Parsing only captures what was given.
  */
final case class FlowArgs(
  promptText: Option[String] = None,
  promptFile: Option[Path] = None,
  repo: Option[Path] = None,
)

object FlowArgs:

  private val usage =
    """usage: scala-cli run <script>.sc -- ["<prompt>" | --prompt-file <path> | @<path>] [--repo <path>]"""

  /** Parse a flow script's args into [[FlowArgs]], or a usage error string. The first non-blank positional is the
    * prompt text; later positionals are ignored. `@<path>` is sugar for `--prompt-file <path>`.
    */
  def parse(args: List[String]): Either[String, FlowArgs] =
    def loop(rem: List[String], acc: FlowArgs): Either[String, FlowArgs] =
      rem match
        case Nil                                                  => Right(acc)
        case "--prompt-file" :: value :: rest                     =>
          loop(rest, acc.copy(promptFile = Some(Path.of(value))))
        case "--prompt-file" :: Nil                               =>
          Left(s"--prompt-file requires a path\n$usage")
        case flag :: rest if flag.startsWith("--prompt-file=")    =>
          loop(rest, acc.copy(promptFile = Some(Path.of(flag.stripPrefix("--prompt-file=")))))
        case ("--repo" | "-C") :: value :: rest                   =>
          loop(rest, acc.copy(repo = Some(Path.of(value))))
        case ("--repo" | "-C") :: Nil                             =>
          Left(s"--repo requires a path\n$usage")
        case flag :: rest if flag.startsWith("--repo=")           =>
          loop(rest, acc.copy(repo = Some(Path.of(flag.stripPrefix("--repo=")))))
        case flag :: _ if flag.startsWith("--")                   =>
          Left(s"unknown flag: $flag\n$usage")
        case pos :: rest if pos.startsWith("@") && pos.length > 1 =>
          // @file sugar — never overrides an explicit --prompt-file.
          loop(rest, acc.copy(promptFile = acc.promptFile.orElse(Some(Path.of(pos.drop(1))))))
        case pos :: rest                                          =>
          val trimmed = pos.trim
          val next    = if trimmed.nonEmpty && acc.promptText.isEmpty then acc.copy(promptText = Some(trimmed)) else acc
          loop(rest, next)
    loop(args, FlowArgs())
