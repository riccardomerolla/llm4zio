package workspace.control

import zio.*
import zio.json.*
import zio.stream.ZStream

import workspace.entity.{ GitError, GitLogEntry, GitStatus }

sealed trait GitWatcherEvent derives JsonCodec
object GitWatcherEvent:
  final case class StatusUpdated(status: GitStatus) extends GitWatcherEvent
  final case class NewCommit(commit: GitLogEntry)   extends GitWatcherEvent

trait GitWatcher:
  def registerRun(runId: String, worktreePath: String): UIO[Unit]
  def unregisterRun(runId: String): UIO[Unit]
  def subscribe(runId: String): ZIO[Scope, String, Dequeue[GitWatcherEvent]]

object GitWatcher:
  val live: ZLayer[GitService, Nothing, GitWatcher] =
    ZLayer.fromZIO {
      for
        gitService <- ZIO.service[GitService]
        watcher    <- GitWatcherLive.make(gitService)
      yield watcher
    }

  val noop: GitWatcher =
    new GitWatcher:
      override def registerRun(runId: String, worktreePath: String): UIO[Unit]            = ZIO.unit
      override def unregisterRun(runId: String): UIO[Unit]                                = ZIO.unit
      override def subscribe(runId: String): ZIO[Scope, String, Dequeue[GitWatcherEvent]] =
        ZIO.fail("git watcher disabled")

final case class GitWatcherLive private (
  gitService: GitService,
  pollInterval: Duration,
  watchers: Ref[Map[String, GitWatcherLive.RunWatch]],
) extends GitWatcher:

  override def registerRun(runId: String, worktreePath: String): UIO[Unit] =
    watchers.get.flatMap { current =>
      if current.contains(runId) then ZIO.unit
      else
        (for
          subscribers <- Ref.make(0)
          lastStatus  <- Ref.make(Option.empty[String])
          lastCommit  <- Ref.make(Option.empty[String])
          hub         <- Hub.unbounded[GitWatcherEvent]
          fiber       <- runWatchLoop(worktreePath, subscribers, lastStatus, lastCommit, hub)
                           .forkDaemon
          watch        = GitWatcherLive.RunWatch(
                           worktreePath = worktreePath,
                           subscribers = subscribers,
                           lastStatus = lastStatus,
                           lastCommit = lastCommit,
                           hub = hub,
                           fiber = fiber,
                         )
          _           <- watchers.update(_ + (runId -> watch))
        yield ()).ignoreLogged
    }

  override def unregisterRun(runId: String): UIO[Unit] =
    watchers
      .modify(current => (current.get(runId), current - runId))
      .flatMap {
        case Some(watch) => watch.fiber.interrupt.ignore *> watch.hub.shutdown.ignore
        case None        => ZIO.unit
      }
      .ignoreLogged

  override def subscribe(runId: String): ZIO[Scope, String, Dequeue[GitWatcherEvent]] =
    watchers.get.flatMap { current =>
      current.get(runId) match
        case None        => ZIO.fail(s"run_not_watched:$runId")
        case Some(watch) =>
          ZIO.acquireRelease(watch.subscribers.update(_ + 1) *> watch.hub.subscribe)(_ =>
            watch.subscribers.update(count => if count > 0 then count - 1 else 0)
          )
    }

  private def runWatchLoop(
    worktreePath: String,
    subscribers: Ref[Int],
    lastStatus: Ref[Option[String]],
    lastCommit: Ref[Option[String]],
    hub: Hub[GitWatcherEvent],
  ): UIO[Unit] =
    val pollOnce =
      for
        activeSubscribers <- subscribers.get
        _                 <-
          if activeSubscribers <= 0 then ZIO.unit
          else
            publishStatusIfChanged(worktreePath, lastStatus, hub) *>
              publishCommitIfChanged(worktreePath, lastCommit, hub)
      yield ()

    ZStream
      .repeatZIOWithSchedule(pollOnce, Schedule.spaced(pollInterval))
      .runDrain

  private def publishStatusIfChanged(
    worktreePath: String,
    lastStatus: Ref[Option[String]],
    hub: Hub[GitWatcherEvent],
  ): UIO[Unit] =
    gitService.status(worktreePath).either.flatMap {
      case Right(status)                       =>
        val snapshot = status.toJson
        lastStatus.modify { previous =>
          val changed = previous.forall(_ != snapshot)
          (changed, Some(snapshot))
        }.flatMap { changed =>
          if changed then hub.publish(GitWatcherEvent.StatusUpdated(status)).unit else ZIO.unit
        }
      case Left(_: GitError.NotAGitRepository) => ZIO.unit
      case Left(_)                             => ZIO.unit
    }

  private def publishCommitIfChanged(
    worktreePath: String,
    lastCommit: Ref[Option[String]],
    hub: Hub[GitWatcherEvent],
  ): UIO[Unit] =
    gitService.log(worktreePath, 1).either.flatMap {
      case Right(head :: _) =>
        lastCommit.modify { previous =>
          val changed = previous.forall(_ != head.hash)
          (changed, Some(head.hash))
        }.flatMap { changed =>
          if changed then hub.publish(GitWatcherEvent.NewCommit(head)).unit else ZIO.unit
        }
      case Right(Nil)       => ZIO.unit
      case Left(_)          => ZIO.unit
    }

object GitWatcherLive:
  final case class RunWatch(
    worktreePath: String,
    subscribers: Ref[Int],
    lastStatus: Ref[Option[String]],
    lastCommit: Ref[Option[String]],
    hub: Hub[GitWatcherEvent],
    fiber: Fiber.Runtime[Nothing, Unit],
  )

  def make(gitService: GitService, pollInterval: Duration = 5.seconds): UIO[GitWatcherLive] =
    Ref.make(Map.empty[String, RunWatch]).map(state => GitWatcherLive(gitService, pollInterval, state))
