package workspace.control

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.process.Command

import workspace.entity.*

trait GitService:
  def status(repoPath: String): IO[GitError, GitStatus]
  def diff(repoPath: String, staged: Boolean = false): IO[GitError, GitDiff]
  def diffStat(repoPath: String, staged: Boolean = false): IO[GitError, GitDiffStat]
  def diffFile(repoPath: String, filePath: String, staged: Boolean = false): IO[GitError, String]
  def log(repoPath: String, limit: Int = 20): IO[GitError, List[GitLogEntry]]
  def branchInfo(repoPath: String): IO[GitError, GitBranchInfo]
  def showFile(repoPath: String, filePath: String, ref: String = "HEAD"): IO[GitError, String]
  def aheadBehind(repoPath: String, baseBranch: String): IO[GitError, AheadBehind]
  def checkout(repoPath: String, branch: String): IO[GitError, Unit]
  def add(repoPath: String, paths: List[String]): IO[GitError, Unit]
  def mv(repoPath: String, from: String, to: String): IO[GitError, Unit]
  def commit(repoPath: String, message: String): IO[GitError, String]
  def rm(repoPath: String, path: String, recursive: Boolean = false): IO[GitError, Unit]
  def mergeNoFastForward(repoPath: String, branch: String, message: String): IO[GitError, Unit]
  def mergeAbort(repoPath: String): IO[GitError, Unit]
  def conflictedFiles(repoPath: String): IO[GitError, List[String]]
  def headSha(repoPath: String): IO[GitError, String]
  def showDiffStat(repoPath: String, ref: String = "HEAD"): IO[GitError, GitDiffStat]

object GitService:
  val live: ULayer[GitService] = ZLayer.succeed(GitServiceLive())

  def status(repoPath: String): ZIO[GitService, GitError, GitStatus] =
    ZIO.serviceWithZIO[GitService](_.status(repoPath))

  def diff(repoPath: String, staged: Boolean = false): ZIO[GitService, GitError, GitDiff] =
    ZIO.serviceWithZIO[GitService](_.diff(repoPath, staged))

  def diffStat(repoPath: String, staged: Boolean = false): ZIO[GitService, GitError, GitDiffStat] =
    ZIO.serviceWithZIO[GitService](_.diffStat(repoPath, staged))

  def diffFile(repoPath: String, filePath: String, staged: Boolean = false): ZIO[GitService, GitError, String] =
    ZIO.serviceWithZIO[GitService](_.diffFile(repoPath, filePath, staged))

  def log(repoPath: String, limit: Int = 20): ZIO[GitService, GitError, List[GitLogEntry]] =
    ZIO.serviceWithZIO[GitService](_.log(repoPath, limit))

  def branchInfo(repoPath: String): ZIO[GitService, GitError, GitBranchInfo] =
    ZIO.serviceWithZIO[GitService](_.branchInfo(repoPath))

  def showFile(repoPath: String, filePath: String, ref: String = "HEAD"): ZIO[GitService, GitError, String] =
    ZIO.serviceWithZIO[GitService](_.showFile(repoPath, filePath, ref))

  def aheadBehind(repoPath: String, baseBranch: String): ZIO[GitService, GitError, AheadBehind] =
    ZIO.serviceWithZIO[GitService](_.aheadBehind(repoPath, baseBranch))

  def checkout(repoPath: String, branch: String): ZIO[GitService, GitError, Unit] =
    ZIO.serviceWithZIO[GitService](_.checkout(repoPath, branch))

  def add(repoPath: String, paths: List[String]): ZIO[GitService, GitError, Unit] =
    ZIO.serviceWithZIO[GitService](_.add(repoPath, paths))

  def mv(repoPath: String, from: String, to: String): ZIO[GitService, GitError, Unit] =
    ZIO.serviceWithZIO[GitService](_.mv(repoPath, from, to))

  def commit(repoPath: String, message: String): ZIO[GitService, GitError, String] =
    ZIO.serviceWithZIO[GitService](_.commit(repoPath, message))

  def rm(repoPath: String, path: String, recursive: Boolean = false): ZIO[GitService, GitError, Unit] =
    ZIO.serviceWithZIO[GitService](_.rm(repoPath, path, recursive))

  def mergeNoFastForward(repoPath: String, branch: String, message: String): ZIO[GitService, GitError, Unit] =
    ZIO.serviceWithZIO[GitService](_.mergeNoFastForward(repoPath, branch, message))

  def mergeAbort(repoPath: String): ZIO[GitService, GitError, Unit] =
    ZIO.serviceWithZIO[GitService](_.mergeAbort(repoPath))

  def conflictedFiles(repoPath: String): ZIO[GitService, GitError, List[String]] =
    ZIO.serviceWithZIO[GitService](_.conflictedFiles(repoPath))

  def headSha(repoPath: String): ZIO[GitService, GitError, String] =
    ZIO.serviceWithZIO[GitService](_.headSha(repoPath))

  def showDiffStat(repoPath: String, ref: String = "HEAD"): ZIO[GitService, GitError, GitDiffStat] =
    ZIO.serviceWithZIO[GitService](_.showDiffStat(repoPath, ref))

object GitParsers:
  private val LogSeparator = "\\u001f"

  private def parseChangeStatus(code: Char): Option[ChangeStatus] =
    code match
      case 'A' => Some(ChangeStatus.Added)
      case 'M' => Some(ChangeStatus.Modified)
      case 'D' => Some(ChangeStatus.Deleted)
      case 'R' => Some(ChangeStatus.Renamed)
      case 'C' => Some(ChangeStatus.Copied)
      case 'T' => Some(ChangeStatus.Modified)
      case 'U' => Some(ChangeStatus.Modified)
      case _   => None

  private def parseTrackedLine(line: String): Option[(String, Char, Char)] =
    if line.startsWith("1 ") then
      line.split("\\s+", 9).toList match
        case _ :: xy :: _ :: _ :: _ :: _ :: _ :: _ :: path :: Nil if xy.length >= 2 =>
          Some((path, xy.charAt(0), xy.charAt(1)))
        case _                                                                      => None
    else if line.startsWith("2 ") then
      line.split("\\s+", 10).toList match
        case _ :: xy :: _ :: _ :: _ :: _ :: _ :: _ :: _ :: pathWithOld :: Nil if xy.length >= 2 =>
          val path = pathWithOld.split("\\t", 2).headOption.getOrElse(pathWithOld)
          Some((path, xy.charAt(0), xy.charAt(1)))
        case _                                                                                  => None
    else None

  def parseStatusPorcelain(raw: String): Either[GitError, GitStatus] =
    val lines     = raw.linesIterator.toList
    val branch    = lines.collectFirst {
      case line if line.startsWith("# branch.head ") =>
        line.stripPrefix("# branch.head ").trim
    }.filterNot(_ == "(detached)").getOrElse("HEAD")
    val staged    = scala.collection.mutable.ListBuffer.empty[FileChange]
    val unstaged  = scala.collection.mutable.ListBuffer.empty[FileChange]
    val untracked = scala.collection.mutable.ListBuffer.empty[String]

    lines.foreach { line =>
      if line.startsWith("1 ") || line.startsWith("2 ") then
        parseTrackedLine(line).foreach {
          case (path, idxStatus, wtStatus) =>
            parseChangeStatus(idxStatus).foreach(status => staged += FileChange(path, status))
            parseChangeStatus(wtStatus).foreach(status => unstaged += FileChange(path, status))
        }
      else if line.startsWith("? ") then
        untracked += line.drop(2).trim
    }

    Right(
      GitStatus(
        branch = branch,
        staged = staged.toList,
        unstaged = unstaged.toList,
        untracked = untracked.toList,
      )
    )

  def parseDiffNumStat(raw: String): Either[GitError, GitDiffStat] =
    val entries = raw.linesIterator.toList.filter(_.trim.nonEmpty).flatMap { line =>
      line.split("\\t", 3).toList match
        case adds :: dels :: path :: Nil =>
          val additions = if adds == "-" then 0 else adds.toIntOption.getOrElse(0)
          val deletions = if dels == "-" then 0 else dels.toIntOption.getOrElse(0)
          Some(DiffFileStat(path, additions, deletions))
        case _                           => None
    }
    Right(GitDiffStat(entries))

  def parseLog(raw: String): Either[GitError, List[GitLogEntry]] =
    raw.linesIterator.toList.filter(_.trim.nonEmpty).foldRight[Either[GitError, List[GitLogEntry]]](Right(Nil)) {
      (line, acc) =>
        for
          tail <- acc
          head <- line.split(LogSeparator, -1).toList match
                    case hash :: shortHash :: author :: message :: date :: Nil =>
                      try Right(GitLogEntry(hash, shortHash, author, message, Instant.parse(date)))
                      catch
                        case err: Throwable =>
                          Left(GitError.ParseFailure("git log", s"Invalid timestamp '$date': ${err.getMessage}"))
                    case _                                                     =>
                      Left(GitError.ParseFailure("git log", s"Invalid git log format: $line"))
        yield head :: tail
    }

  def parseAheadBehind(raw: String): Either[GitError, AheadBehind] =
    raw.trim.split("\\s+").toList match
      case behind :: ahead :: Nil =>
        (behind.toIntOption, ahead.toIntOption) match
          case (Some(behindCount), Some(aheadCount)) => Right(AheadBehind(ahead = aheadCount, behind = behindCount))
          case _                                     => Left(GitError.ParseFailure("git rev-list", s"Invalid counts: $raw"))
      case _                      =>
        Left(GitError.ParseFailure("git rev-list", s"Invalid ahead/behind output: $raw"))

final case class GitServiceLive() extends GitService:
  private def asRepoPath(repoPath: String): IO[GitError, java.io.File] =
    val trimmed = repoPath.trim
    if trimmed.isEmpty then ZIO.fail(GitError.NotAGitRepository(repoPath))
    else ZIO.succeed(Paths.get(trimmed).toFile)

  private def runGit(repoPath: String, args: String*): IO[GitError, String] =
    for
      path    <- asRepoPath(repoPath)
      safeArgs = args.filter(_.nonEmpty)
      out     <- Command("git", safeArgs*).workingDirectory(path).string.mapError(err =>
                   GitError.CommandFailed(s"git ${safeArgs.mkString(" ")}", err.getMessage)
                 )
    yield out

  private def ensureRepo(repoPath: String): IO[GitError, Unit] =
    runGit(repoPath, "rev-parse", "--is-inside-work-tree")
      .flatMap { out =>
        if out.trim == "true" then ZIO.unit
        else ZIO.fail(GitError.NotAGitRepository(repoPath))
      }
      .catchAll {
        case _: GitError.CommandFailed => ZIO.fail(GitError.NotAGitRepository(repoPath))
        case other                     => ZIO.fail(other)
      }

  override def status(repoPath: String): IO[GitError, GitStatus] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "status", "--porcelain=v2", "--branch", "--untracked-files=all")
        .flatMap(raw => ZIO.fromEither(GitParsers.parseStatusPorcelain(raw)))

  override def diff(repoPath: String, staged: Boolean = false): IO[GitError, GitDiff] =
    for
      _     <- ensureRepo(repoPath)
      stats <- diffStat(repoPath, staged)
      files <- ZIO.foreach(stats.files) { stat =>
                 diffFile(repoPath, stat.path, staged).map(content =>
                   DiffFile(path = stat.path, additions = stat.additions, deletions = stat.deletions, content = content)
                 )
               }
    yield GitDiff(files)

  override def diffStat(repoPath: String, staged: Boolean = false): IO[GitError, GitDiffStat] =
    for
      _            <- ensureRepo(repoPath)
      tracked      <- runGit(repoPath, "diff", if staged then "--cached" else "", "--numstat")
                        .flatMap(raw => ZIO.fromEither(GitParsers.parseDiffNumStat(raw)))
      untrackedRaw <- if staged then ZIO.succeed("")
                      else
                        runGit(repoPath, "ls-files", "--others", "--exclude-standard")
                          .catchAll(_ => ZIO.succeed(""))
      trackedPaths  = tracked.files.map(_.path).toSet
      untracked     = untrackedRaw.linesIterator.map(_.trim)
                        .filter(p => p.nonEmpty && !trackedPaths.contains(p))
                        .map(p => DiffFileStat(p, 0, 0)).toList
    yield GitDiffStat(tracked.files ++ untracked)

  override def diffFile(repoPath: String, filePath: String, staged: Boolean = false): IO[GitError, String] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "diff", if staged then "--cached" else "", "--no-color", "--", filePath)
        .flatMap { out =>
          if out.nonEmpty then ZIO.succeed(out)
          else
            // File may be untracked — produce a new-file diff via --no-index
            runGit(repoPath, "diff", "--no-index", "--no-color", "/dev/null", filePath)
              .catchAll(_ => ZIO.succeed(out)) // --no-index exits non-zero for new files; ignore error
        }

  override def log(repoPath: String, limit: Int = 20): IO[GitError, List[GitLogEntry]] =
    ensureRepo(repoPath) *>
      runGit(
        repoPath,
        "log",
        s"-n${Math.max(limit, 1)}",
        "--date=iso-strict",
        "--format=%H%x1f%h%x1f%an%x1f%s%x1f%cI",
      ).flatMap(raw => ZIO.fromEither(GitParsers.parseLog(raw)))

  override def branchInfo(repoPath: String): IO[GitError, GitBranchInfo] =
    for
      _       <- ensureRepo(repoPath)
      current <- runGit(repoPath, "rev-parse", "--abbrev-ref", "HEAD").map(_.trim)
      all     <- runGit(
                   repoPath,
                   "branch",
                   "--format=%(refname:short)",
                 ).map(_.linesIterator.map(_.trim).filter(_.nonEmpty).toList)
    yield GitBranchInfo(current = current, all = all, isDetached = current == "HEAD")

  override def showFile(repoPath: String, filePath: String, ref: String = "HEAD"): IO[GitError, String] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "show", s"$ref:$filePath").catchAll {
        case GitError.CommandFailed(command, details)
             if details.toLowerCase.contains("invalid") || details.toLowerCase
               .contains("unknown revision") =>
          ZIO.fail(GitError.InvalidReference(ref))
        case other => ZIO.fail(other)
      }

  override def aheadBehind(repoPath: String, baseBranch: String): IO[GitError, AheadBehind] =
    ensureRepo(repoPath) *>
      resolveRef(repoPath, baseBranch).flatMap {
        case None      => ZIO.succeed(AheadBehind(ahead = 0, behind = 0))
        case Some(ref) =>
          runGit(repoPath, "rev-list", "--left-right", "--count", s"$ref...HEAD")
            .flatMap(raw => ZIO.fromEither(GitParsers.parseAheadBehind(raw)))
      }

  override def checkout(repoPath: String, branch: String): IO[GitError, Unit] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "checkout", branch.trim).unit

  override def add(repoPath: String, paths: List[String]): IO[GitError, Unit] =
    val cleanPaths = paths.map(_.trim).filter(_.nonEmpty).distinct
    ensureRepo(repoPath) *>
      (if cleanPaths.isEmpty then ZIO.fail(GitError.CommandFailed("git add", "No paths provided"))
       else runGit(repoPath, ("add" :: cleanPaths)*).unit)

  override def mv(repoPath: String, from: String, to: String): IO[GitError, Unit] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "mv", from.trim, to.trim).unit

  override def commit(repoPath: String, message: String): IO[GitError, String] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "commit", "-m", message.trim) *>
      headSha(repoPath)

  override def rm(repoPath: String, path: String, recursive: Boolean = false): IO[GitError, Unit] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "rm", if recursive then "-r" else "", path.trim).unit

  override def mergeNoFastForward(repoPath: String, branch: String, message: String): IO[GitError, Unit] =
    ensureRepo(repoPath) *>
      runGit(
        repoPath,
        "merge",
        "--no-ff",
        "-m",
        message.trim,
        branch.trim,
      ).unit

  override def mergeAbort(repoPath: String): IO[GitError, Unit] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "merge", "--abort").unit

  override def conflictedFiles(repoPath: String): IO[GitError, List[String]] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "diff", "--name-only", "--diff-filter=U")
        .map(_.linesIterator.map(_.trim).filter(_.nonEmpty).toList.distinct)

  override def headSha(repoPath: String): IO[GitError, String] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "rev-parse", "HEAD").map(_.trim)

  override def showDiffStat(repoPath: String, ref: String = "HEAD"): IO[GitError, GitDiffStat] =
    ensureRepo(repoPath) *>
      runGit(repoPath, "show", "--numstat", "--format=", ref)
        .flatMap(raw => ZIO.fromEither(GitParsers.parseDiffNumStat(raw)))

  /** Returns the first resolvable ref from the candidates, or None if none exist in this repo/worktree. */
  private def resolveRef(repoPath: String, branch: String): IO[GitError, Option[String]] =
    val candidates = List(branch, s"origin/$branch")
    ZIO.foldLeft(candidates)(Option.empty[String]) { (found, ref) =>
      if found.isDefined then ZIO.succeed(found)
      else
        runGit(repoPath, "rev-parse", "--verify", ref)
          .as(Some(ref))
          .catchAll(_ => ZIO.succeed(None))
    }
