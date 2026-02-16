package gateway.telegram

enum BotCommand:
  case Start
  case Help
  case ListRuns
  case Status(runId: Long)
  case Logs(runId: Long)
  case Cancel(runId: Long)

  def toWorkflowOperation: BotWorkflowOperation =
    this match
      case BotCommand.Start         => BotWorkflowOperation.ShowWelcome
      case BotCommand.Help          => BotWorkflowOperation.ShowHelp
      case BotCommand.ListRuns      => BotWorkflowOperation.ListRuns
      case BotCommand.Status(runId) => BotWorkflowOperation.ShowRunStatus(runId)
      case BotCommand.Logs(runId)   => BotWorkflowOperation.ShowRunLogs(runId)
      case BotCommand.Cancel(runId) => BotWorkflowOperation.CancelRun(runId)

enum BotWorkflowOperation:
  case ShowWelcome
  case ShowHelp
  case ListRuns
  case ShowRunStatus(runId: Long)
  case ShowRunLogs(runId: Long)
  case CancelRun(runId: Long)

enum CommandParseError:
  case EmptyInput
  case NotACommand(input: String)
  case UnknownCommand(command: String)
  case MissingParameter(command: String, parameter: String)
  case InvalidRunId(command: String, rawValue: String)
