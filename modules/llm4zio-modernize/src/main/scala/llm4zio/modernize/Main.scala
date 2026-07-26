package llm4zio.modernize

import java.nio.file.{ Files, Paths }

import scala.jdk.CollectionConverters.*

/** Environment for the modernization flows: real env vars win, `modernize.conf` (loaded into system properties by
  * [[Main]]) fills the gaps — packs stay estate knowledge, the conf is deployment choices (seats, models, endpoints),
  * different owners, different repos.
  */
object Env:
  def get(name: String): Option[String]                    = sys.env.get(name).orElse(sys.props.get(s"llm4zio.$name"))
  def getOrElse(name: String, fallback: => String): String = get(name).getOrElse(fallback)

/** The modernization pipeline as a product: six flows behind one subcommand main, configured entirely by the pack +
  * env/`modernize.conf` — the operator never edits Scala. The `.sc` examples remain the authoring surface; this is the
  * `cs launch` / OCI surface.
  */
object Main:

  private val phases: Map[String, Array[String] => Unit] = Map(
    "survey"    -> SurveyFlow.run,
    "extract"   -> ExtractFlow.run,
    "seed"      -> SeedFlow.run,
    "implement" -> ImplementFlow.run,
    "verify"    -> VerifyFlow.run,
    "review"    -> ReviewFlow.run,
  )

  private val usage =
    """usage: llm4zio-modernize <survey|extract|seed|implement|verify|review> -- --repo <dir> [args]
      |
      |  survey     inventory + dependency graph + triage + human-gated wave plan   (legacy repo)
      |  extract    judged spec pack, per program, resumable                        (legacy repo)
      |  seed       scaffold + specs + plan + provenance manifest (deterministic)   (target repo)
      |  implement  plan execution, RED-first tests, gated until green              (target repo)
      |  verify     equivalence proof: vectors → replay → per-rule report           (target repo)
      |  review     fix specs + plan increment + lessons back into the pack         (target repo)
      |
      |Config: env vars first (LLM4ZIO_PACK, LLM4ZIO_CODER, LLM4ZIO_WAVE, …), then ./modernize.conf
      |(KEY=value lines) for whatever env leaves unset. Packs are estate knowledge; the conf is
      |deployment choices. See docs/pilot-playbook.md.""".stripMargin

  /** `./modernize.conf` (KEY=value, `#` comments) → system properties, so [[Env]] finds them after env. */
  private def loadConf(): Unit =
    val conf = Paths.get("modernize.conf")
    if Files.exists(conf) then
      Files
        .readAllLines(conf)
        .asScala
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("#") && l.contains("="))
        .foreach { line =>
          val (k, v) = line.span(_ != '=')
          sys.props.update(s"llm4zio.${k.trim}", v.drop(1).trim)
        }

  def main(args: Array[String]): Unit =
    args.toList match
      case cmd :: rest if phases.contains(cmd) =>
        loadConf()
        phases(cmd)(rest.toArray)
      case _                                   =>
        System.err.print(usage + "\n")
        sys.exit(2)
