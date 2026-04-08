package workspace.entity

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

enum ChangeStatus derives JsonCodec, Schema:
  case Added, Modified, Deleted, Renamed, Copied

final case class FileChange(path: String, status: ChangeStatus) derives JsonCodec, Schema

final case class GitStatus(
  branch: String,
  staged: List[FileChange],
  unstaged: List[FileChange],
  untracked: List[String],
) derives JsonCodec,
    Schema

final case class DiffFile(path: String, additions: Int, deletions: Int, content: String) derives JsonCodec, Schema

final case class GitDiff(files: List[DiffFile]) derives JsonCodec, Schema

final case class DiffFileStat(path: String, additions: Int, deletions: Int) derives JsonCodec, Schema

final case class GitDiffStat(files: List[DiffFileStat]) derives JsonCodec, Schema

final case class GitChangeSummary(filesChanged: Int, insertions: Int, deletions: Int) derives JsonCodec, Schema

final case class GitLogEntry(
  hash: String,
  shortHash: String,
  author: String,
  message: String,
  date: Instant,
) derives JsonCodec,
    Schema

final case class GitBranchInfo(current: String, all: List[String], isDetached: Boolean) derives JsonCodec, Schema

final case class AheadBehind(ahead: Int, behind: Int) derives JsonCodec, Schema

enum GitError derives JsonCodec, Schema:
  case NotAGitRepository(repoPath: String)
  case InvalidReference(ref: String)
  case CommandFailed(command: String, details: String)
  case ParseFailure(command: String, details: String)
