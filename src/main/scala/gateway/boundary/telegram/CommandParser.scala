package gateway.boundary.telegram

object CommandParser:
  def parse(input: String): Either[CommandParseError, BotCommand] =
    val text = input.trim
    if text.isEmpty then Left(CommandParseError.EmptyInput)
    else if !text.startsWith("/") then Left(CommandParseError.NotACommand(text))
    else
      val tokens       = text.split("\\s+").toList.filter(_.nonEmpty)
      val rawCommand   = tokens.headOption.getOrElse("")
      val commandToken = normalizeCommand(rawCommand)
      val args         = tokens.drop(1)

      commandToken match
        case "start"          => Right(BotCommand.Start)
        case "help"           => Right(BotCommand.Help)
        case "tasks" | "list" => Right(BotCommand.ListTasks)
        case "status"         => parseTaskIdArg("status", args).map(BotCommand.Status.apply)
        case "logs"           => parseTaskIdArg("logs", args).map(BotCommand.Logs.apply)
        case "cancel"         => parseTaskIdArg("cancel", args).map(BotCommand.Cancel.apply)
        case other            => Left(CommandParseError.UnknownCommand(other))

  def parseToWorkflowOperation(input: String): Either[CommandParseError, BotWorkflowOperation] =
    parse(input).map(_.toWorkflowOperation)

  private def normalizeCommand(token: String): String =
    token.stripPrefix("/").split("@").headOption.getOrElse("").trim.toLowerCase

  private def parseTaskIdArg(command: String, args: List[String]): Either[CommandParseError, Long] =
    args.headOption match
      case None        => Left(CommandParseError.MissingParameter(command, "id"))
      case Some(value) =>
        value.toLongOption match
          case Some(id) if id > 0L => Right(id)
          case _                   => Left(CommandParseError.InvalidTaskId(command, value))
