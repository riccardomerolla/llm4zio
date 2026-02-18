package gateway.telegram

import zio.*

import agents.AgentRegistry

enum WorkflowNotifierError:
  case Telegram(error: TelegramClientError)

trait WorkflowNotifier:
  def notifyCommand(
    chatId: Long,
    replyToMessageId: Option[Long],
    command: BotCommand,
  ): IO[WorkflowNotifierError, Unit]

  def notifyParseError(
    chatId: Long,
    replyToMessageId: Option[Long],
    error: CommandParseError,
  ): IO[WorkflowNotifierError, Unit]

object WorkflowNotifier:
  def notifyCommand(
    chatId: Long,
    replyToMessageId: Option[Long],
    command: BotCommand,
  ): ZIO[WorkflowNotifier, WorkflowNotifierError, Unit] =
    ZIO.serviceWithZIO[WorkflowNotifier](_.notifyCommand(chatId, replyToMessageId, command))

  def notifyParseError(
    chatId: Long,
    replyToMessageId: Option[Long],
    error: CommandParseError,
  ): ZIO[WorkflowNotifier, WorkflowNotifierError, Unit] =
    ZIO.serviceWithZIO[WorkflowNotifier](_.notifyParseError(chatId, replyToMessageId, error))

  val noop: WorkflowNotifier =
    new WorkflowNotifier:
      override def notifyCommand(
        chatId: Long,
        replyToMessageId: Option[Long],
        command: BotCommand,
      ): IO[WorkflowNotifierError, Unit] =
        ZIO.unit

      override def notifyParseError(
        chatId: Long,
        replyToMessageId: Option[Long],
        error: CommandParseError,
      ): IO[WorkflowNotifierError, Unit] =
        ZIO.unit

  val live: ZLayer[TelegramClient & AgentRegistry, Nothing, WorkflowNotifier] =
    ZLayer.fromZIO {
      for
        client        <- ZIO.service[TelegramClient]
        agentRegistry <- ZIO.service[AgentRegistry]
      yield WorkflowNotifierLive(client, agentRegistry)
    }

final case class WorkflowNotifierLive(
  client: TelegramClient,
  agentRegistry: AgentRegistry,
) extends WorkflowNotifier:

  override def notifyCommand(
    chatId: Long,
    replyToMessageId: Option[Long],
    command: BotCommand,
  ): IO[WorkflowNotifierError, Unit] =
    for
      text <- commandMessage(command)
      _    <- sendText(chatId, text, replyToMessageId)
    yield ()

  override def notifyParseError(
    chatId: Long,
    replyToMessageId: Option[Long],
    error: CommandParseError,
  ): IO[WorkflowNotifierError, Unit] =
    sendText(chatId, parseErrorMessage(error), replyToMessageId)

  private def sendText(
    chatId: Long,
    text: String,
    replyToMessageId: Option[Long],
  ): IO[WorkflowNotifierError, Unit] =
    client
      .sendMessage(
        TelegramSendMessage(
          chat_id = chatId,
          text = text,
          reply_to_message_id = replyToMessageId,
        )
      )
      .mapError(WorkflowNotifierError.Telegram.apply)
      .unit

  private def commandMessage(command: BotCommand): UIO[String] =
    command match
      case BotCommand.Start      => ZIO.succeed("Gateway bot is online.")
      case BotCommand.Help       =>
        agentRegistry.getAllAgents.map { agents =>
          val names = agents.map(_.name).sorted
          if names.isEmpty then "No agents are currently available."
          else s"Available agents:\n${names.map(name => s"- $name").mkString("\n")}"
        }
      case BotCommand.ListRuns   => ZIO.succeed("Run listing via bot is disabled in gateway mode.")
      case BotCommand.Status(id) => ZIO.succeed(s"Run status via bot is disabled (run=$id).")
      case BotCommand.Logs(id)   => ZIO.succeed(s"Run logs via bot is disabled (run=$id).")
      case BotCommand.Cancel(id) => ZIO.succeed(s"Run cancel via bot is disabled (run=$id).")

  private def parseErrorMessage(error: CommandParseError): String =
    error match
      case CommandParseError.EmptyInput                       =>
        "Command is empty. Use /help."
      case CommandParseError.NotACommand(_)                   =>
        "Unsupported message. Use /help."
      case CommandParseError.UnknownCommand(command)          =>
        s"Unknown command '/$command'. Use /help."
      case CommandParseError.MissingParameter(command, param) =>
        s"Command '/$command' requires parameter '$param'."
      case CommandParseError.InvalidRunId(command, raw)       =>
        s"Invalid run id '$raw' for '/$command'."
