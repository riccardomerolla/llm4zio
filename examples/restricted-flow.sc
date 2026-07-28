//> using dep "io.github.riccardomerolla::llm4zio-runner:4.2.0"
//> using scala "3.8.3"
//> using jvm 21

/** Tracked capabilities (issue #716): a read-only survey flow that provably cannot commit, push, or open a PR.
  *
  * `flow.restricted[C]` declares the flow's powers once, as a type. The declaration does two things at the same time:
  * it puts exactly those capability witnesses in scope — so `git.commitAll`, `git.push`, or `gh.createPr` below would
  * be a **compile error**, not a runtime surprise — and it derives the runtime grants the whole flow executes under,
  * so even code that smuggles a witness (or a model choosing tools) is stopped by the ambient gate and audited in the
  * trace.
  *
  * Run it and diff away: the strongest guarantee is the line you cannot write.
  *
  * {{{ scala-cli run examples/restricted-flow.sc -- "summarize the recent changes" }}}
  */

import llm4zio.flow.*
import llm4zio.runner.*

flow.restricted[Caps.GitRead & Caps.Reasoning](
  args,
  defaultPrompt = Some("summarize what changed on this branch and flag anything risky"),
):
  for
    base    <- git.defaultBase
    diff    <- git.diffVsBase(base)
    files   <- git.changedFilesVsBase(base)
    _       <- stage("survey")(
                 reasoning
                   .executeStream(
                     s"""You are reviewing a branch (read-only). ${userPrompt}
                        |
                        |Changed files: ${files.mkString(", ")}
                        |
                        |Diff:
                        |$diff
                        |""".stripMargin
                   )
                   .runDrain
                   .mapError(e => FlowError.Llm(e.message, Some(e)))
               )
  yield ()
