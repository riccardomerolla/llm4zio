package gateway.boundary.telegram

enum BotCommand:
  case Start
  case Help
  case ListTasks
  case Status(taskId: Long)
  case Logs(taskId: Long)
  case Cancel(taskId: Long)

  def toWorkflowOperation: BotWorkflowOperation =
    this match
      case BotCommand.Start          => BotWorkflowOperation.ShowWelcome
      case BotCommand.Help           => BotWorkflowOperation.ShowHelp
      case BotCommand.ListTasks      => BotWorkflowOperation.ListTasks
      case BotCommand.Status(taskId) => BotWorkflowOperation.ShowTaskStatus(taskId)
      case BotCommand.Logs(taskId)   => BotWorkflowOperation.ShowTaskLogs(taskId)
      case BotCommand.Cancel(taskId) => BotWorkflowOperation.CancelTask(taskId)

enum BotWorkflowOperation:
  case ShowWelcome
  case ShowHelp
  case ListTasks
  case ShowTaskStatus(taskId: Long)
  case ShowTaskLogs(taskId: Long)
  case CancelTask(taskId: Long)

enum CommandParseError:
  case EmptyInput
  case NotACommand(input: String)
  case UnknownCommand(command: String)
  case MissingParameter(command: String, parameter: String)
  case InvalidTaskId(command: String, rawValue: String)
