package bankmod.app

import zio.*

object BankmodMain extends ZIOAppDefault:
  def run: ZIO[Any, Nothing, Unit] =
    Console.printLine("bankmod v0.0.1 scaffold").orDie
