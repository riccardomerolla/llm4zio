package llm4zio.runner

import zio.{ UIO, ZIO }

/** Terminal styling. When `enabled` is false (NO_COLOR or not a TTY), every method returns plain text with the glyph
  * but no ANSI escapes, so output stays readable in logs/pipes and is trivially testable.
  */
final case class Palette(enabled: Boolean):
  private def paint(attrs: fansi.Attrs, s: String): String =
    if enabled then attrs(s).render else s

  def stageStart(s: String): String                = paint(fansi.Color.Magenta ++ fansi.Bold.On, "▶ ") + s
  def stageDone(s: String): String                 = paint(fansi.Color.Green, "✔ ") + s
  def fail(s: String): String                      = paint(fansi.Color.Red, "✖ ") + s
  def info(s: String): String                      = paint(fansi.Color.DarkGray, "· " + s)
  def assistant(s: String): String                 = paint(fansi.Color.Magenta ++ fansi.Bold.On, "● ") + s
  def toolCall(name: String, args: String): String =
    val head = paint(fansi.Color.Yellow ++ fansi.Bold.On, "● " + name)
    if args.isEmpty then head else head + " " + paint(fansi.Color.DarkGray, args)
  def raw(s: String): String                       = paint(fansi.Color.DarkGray, s)

object Palette:
  /** Decide color from environment: disabled if `NO_COLOR` is set or stdout is not a TTY. */
  val auto: UIO[Palette] =
    ZIO.succeed {
      val noColor = sys.env.contains("NO_COLOR")
      val notTty  = Option(java.lang.System.console()).isEmpty
      Palette(enabled = !noColor && !notTty)
    }
