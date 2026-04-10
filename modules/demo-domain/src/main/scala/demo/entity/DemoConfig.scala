package demo.entity

import zio.json.*

case class DemoConfig(
  enabled: Boolean = false,
  issueCount: Int = 25,
  agentDelaySeconds: Int = 5,
  repoBaseDir: String = "/tmp",
) derives JsonCodec

object DemoConfig:

  val default: DemoConfig = DemoConfig()

  def fromSettings(settings: Map[String, String]): DemoConfig =
    DemoConfig(
      enabled = settings.get("demo.enabled").exists(_ == "true"),
      issueCount = settings.get("demo.issueCount").flatMap(_.toIntOption).getOrElse(25),
      agentDelaySeconds = settings.get("demo.agentDelaySeconds").flatMap(_.toIntOption).getOrElse(5),
      repoBaseDir = settings.get("demo.repoBaseDir").filter(_.trim.nonEmpty).getOrElse("/tmp"),
    )
