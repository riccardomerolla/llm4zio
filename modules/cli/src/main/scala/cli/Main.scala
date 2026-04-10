package cli

import zio.*

object Main extends ZIOAppDefault:
  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    ZIO.logInfo("llm4zio-cli placeholder")
