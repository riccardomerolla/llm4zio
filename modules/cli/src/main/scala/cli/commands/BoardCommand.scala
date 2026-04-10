package cli.commands

import zio.*

import board.entity.*
import shared.ids.Ids.BoardIssueId

object BoardCommand:

  def listBoard(workspacePath: String): ZIO[BoardRepository, BoardError, String] =
    BoardRepository.readBoard(workspacePath).map { board =>
      val columnOrder = List(
        BoardColumn.Backlog,
        BoardColumn.Todo,
        BoardColumn.InProgress,
        BoardColumn.Review,
        BoardColumn.Done,
        BoardColumn.Archive,
      )
      val lines = columnOrder.flatMap { col =>
        board.columns.get(col) match
          case None | Some(Nil) => Nil
          case Some(issues)     =>
            val header = s"── ${col.toString.toUpperCase} ──"
            val rows   = issues.map { issue =>
              val fm = issue.frontmatter
              s"  [${fm.id.value}] ${fm.title}"
            }
            header :: rows
      }
      if lines.isEmpty then s"Board at $workspacePath has no issues."
      else lines.mkString("\n")
    }

  def showIssue(workspacePath: String, issueId: String): ZIO[BoardRepository, BoardError, String] =
    BoardRepository.readIssue(workspacePath, BoardIssueId(issueId)).map { issue =>
      val fm    = issue.frontmatter
      val lines = List(
        s"ID:       ${fm.id.value}",
        s"Title:    ${fm.title}",
        s"Column:   ${issue.column}",
        s"Priority: ${fm.priority}",
        fm.assignedAgent.fold("")(a => s"Agent:    $a"),
        "",
        issue.body,
      ).filter(_.nonEmpty)
      lines.mkString("\n")
    }
