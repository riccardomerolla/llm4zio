package gateway.boundary.telegram

import zio.test.*

object CommandParserSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("CommandParserSpec")(
    test("parses basic commands") {
      val start = CommandParser.parse("/start")
      val help  = CommandParser.parse("/help")
      val list  = CommandParser.parse("/list")
      val tasks = CommandParser.parse("/tasks")
      assertTrue(
        start == Right(BotCommand.Start),
        help == Right(BotCommand.Help),
        list == Right(BotCommand.ListTasks),
        tasks == Right(BotCommand.ListTasks),
      )
    },
    test("parses run-id commands with parameters") {
      val status = CommandParser.parse("/status 123")
      val logs   = CommandParser.parse("/logs 77")
      val cancel = CommandParser.parse("/cancel 9")
      assertTrue(
        status == Right(BotCommand.Status(123L)),
        logs == Right(BotCommand.Logs(77L)),
        cancel == Right(BotCommand.Cancel(9L)),
      )
    },
    test("supports telegram command mention syntax") {
      val result = CommandParser.parse("/status@my_bot 42")
      assertTrue(result == Right(BotCommand.Status(42L)))
    },
    test("maps commands to workflow operations") {
      val op1 = CommandParser.parseToWorkflowOperation("/start")
      val op2 = CommandParser.parseToWorkflowOperation("/cancel 3")
      assertTrue(
        op1 == Right(BotWorkflowOperation.ShowWelcome),
        op2 == Right(BotWorkflowOperation.CancelTask(3L)),
      )
    },
    test("returns errors for invalid commands") {
      val empty   = CommandParser.parse("  ")
      val unknown = CommandParser.parse("/unknown")
      val missing = CommandParser.parse("/status")
      val badId   = CommandParser.parse("/cancel abc")
      val plain   = CommandParser.parse("hello")
      assertTrue(
        empty == Left(CommandParseError.EmptyInput),
        unknown == Left(CommandParseError.UnknownCommand("unknown")),
        missing == Left(CommandParseError.MissingParameter("status", "id")),
        badId == Left(CommandParseError.InvalidTaskId("cancel", "abc")),
        plain == Left(CommandParseError.NotACommand("hello")),
      )
    },
  )
