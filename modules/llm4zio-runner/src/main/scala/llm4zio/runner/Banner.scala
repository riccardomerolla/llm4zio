package llm4zio.runner

import java.nio.file.Path

/** The header line: `llm4zio <version>, logs: <path>`. Version comes from the jar manifest (`Implementation-Version`,
  * populated by sbt-dynver in published artifacts); falls back to `dev`.
  */
object Banner:
  val version: String =
    Option(getClass.getPackage.getImplementationVersion).filter(_.nonEmpty).getOrElse("dev")

  def line(version: String, logPath: Path): String =
    s"llm4zio $version, logs: $logPath"
