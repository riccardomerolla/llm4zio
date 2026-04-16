package agent.entity

import java.time.Duration

import zio.json.*
import zio.schema.{ Schema, derived }

sealed trait TrustLevel derives Schema

object TrustLevel:
  case object Untrusted extends TrustLevel
  case object Limited   extends TrustLevel
  case object Standard  extends TrustLevel
  case object Elevated  extends TrustLevel

  val values: List[TrustLevel] = List(Untrusted, Limited, Standard, Elevated)

  given JsonCodec[TrustLevel] = JsonCodec[String].transformOrFail(
    {
      case "Untrusted" => Right(Untrusted)
      case "Limited"   => Right(Limited)
      case "Standard"  => Right(Standard)
      case "Elevated"  => Right(Elevated)
      case other       => Left(s"Unknown TrustLevel: $other")
    },
    {
      case Untrusted => "Untrusted"
      case Limited   => "Limited"
      case Standard  => "Standard"
      case Elevated  => "Elevated"
    },
  )

sealed trait AgentPathScope derives JsonCodec, Schema

object AgentPathScope:
  case object Worktree                    extends AgentPathScope
  case object WorkspaceRoot               extends AgentPathScope
  case object WorkspaceConfig             extends AgentPathScope
  case object CrossWorkspace              extends AgentPathScope
  final case class Absolute(path: String) extends AgentPathScope

sealed trait NetworkAccessScope derives Schema

object NetworkAccessScope:
  case object Disabled          extends NetworkAccessScope
  case object WorkspaceServices extends NetworkAccessScope
  case object Unrestricted      extends NetworkAccessScope

  val values: List[NetworkAccessScope] = List(Disabled, WorkspaceServices, Unrestricted)

  given JsonCodec[NetworkAccessScope] = JsonCodec[String].transformOrFail(
    {
      case "Disabled"          => Right(Disabled)
      case "WorkspaceServices" => Right(WorkspaceServices)
      case "Unrestricted"      => Right(Unrestricted)
      case other               => Left(s"Unknown NetworkAccessScope: $other")
    },
    {
      case Disabled          => "Disabled"
      case WorkspaceServices => "WorkspaceServices"
      case Unrestricted      => "Unrestricted"
    },
  )

final case class AgentFileSystemPermissions(
  readScopes: List[AgentPathScope],
  writeScopes: List[AgentPathScope],
) derives JsonCodec,
    Schema

final case class AgentToolPermissions(
  allowedCliTools: List[String]
) derives JsonCodec,
    Schema

final case class AgentGitPermissions(
  createBranch: Boolean,
  commit: Boolean,
  push: Boolean,
  rollback: Boolean,
) derives JsonCodec,
    Schema

final case class AgentIssuePermissions(
  create: Boolean,
  modify: Boolean,
  close: Boolean,
) derives JsonCodec,
    Schema

final case class AgentResourceLimits(
  maxEstimatedTokens: Option[Long] = None,
  maxRuntime: Option[Duration] = None,
  maxEstimatedCostUsd: Option[Double] = None,
) derives JsonCodec,
    Schema

final case class AgentPermissions(
  fileSystem: AgentFileSystemPermissions,
  tools: AgentToolPermissions,
  network: NetworkAccessScope,
  git: AgentGitPermissions,
  issues: AgentIssuePermissions,
  resources: AgentResourceLimits,
) derives JsonCodec,
    Schema

object AgentPermissions:
  given JsonCodec[Duration] = JsonCodec[String].transformOrFail(
    str => scala.util.Try(Duration.parse(str)).toEither.left.map(_.getMessage),
    duration => duration.toString,
  )

  def defaults(
    trustLevel: TrustLevel,
    connectorId: llm4zio.core.ConnectorId,
    timeout: Duration,
    maxEstimatedTokens: Option[Long],
  ): AgentPermissions =
    defaults(trustLevel, connectorId.value, timeout, maxEstimatedTokens)

  def defaults(
    trustLevel: TrustLevel,
    cliTool: String,
    timeout: Duration,
    maxEstimatedTokens: Option[Long],
  ): AgentPermissions =
    val normalizedCliTool = Option(cliTool).map(_.trim).filter(_.nonEmpty).toList
    val resourceLimits    = AgentResourceLimits(
      maxEstimatedTokens = maxEstimatedTokens.filter(_ > 0),
      maxRuntime = Some(timeout),
      maxEstimatedCostUsd = None,
    )
    trustLevel match
      case TrustLevel.Untrusted =>
        AgentPermissions(
          fileSystem = AgentFileSystemPermissions(
            readScopes = List(AgentPathScope.Worktree, AgentPathScope.WorkspaceRoot),
            writeScopes = List(AgentPathScope.Worktree),
          ),
          tools = AgentToolPermissions(normalizedCliTool),
          network = NetworkAccessScope.Disabled,
          git = AgentGitPermissions(createBranch = true, commit = false, push = false, rollback = true),
          issues = AgentIssuePermissions(create = false, modify = false, close = false),
          resources = resourceLimits,
        )
      case TrustLevel.Limited   =>
        AgentPermissions(
          fileSystem = AgentFileSystemPermissions(
            readScopes = List(AgentPathScope.Worktree, AgentPathScope.WorkspaceRoot),
            writeScopes = List(AgentPathScope.Worktree),
          ),
          tools = AgentToolPermissions(normalizedCliTool),
          network = NetworkAccessScope.Disabled,
          git = AgentGitPermissions(createBranch = true, commit = false, push = false, rollback = true),
          issues = AgentIssuePermissions(create = false, modify = true, close = false),
          resources = resourceLimits,
        )
      case TrustLevel.Standard  =>
        AgentPermissions(
          fileSystem = AgentFileSystemPermissions(
            readScopes = List(AgentPathScope.Worktree, AgentPathScope.WorkspaceRoot, AgentPathScope.WorkspaceConfig),
            writeScopes = List(AgentPathScope.Worktree, AgentPathScope.WorkspaceConfig),
          ),
          tools = AgentToolPermissions(normalizedCliTool),
          network = NetworkAccessScope.WorkspaceServices,
          git = AgentGitPermissions(createBranch = true, commit = true, push = false, rollback = true),
          issues = AgentIssuePermissions(create = true, modify = true, close = false),
          resources = resourceLimits,
        )
      case TrustLevel.Elevated  =>
        AgentPermissions(
          fileSystem = AgentFileSystemPermissions(
            readScopes = List(
              AgentPathScope.Worktree,
              AgentPathScope.WorkspaceRoot,
              AgentPathScope.WorkspaceConfig,
              AgentPathScope.CrossWorkspace,
            ),
            writeScopes = List(
              AgentPathScope.Worktree,
              AgentPathScope.WorkspaceConfig,
              AgentPathScope.CrossWorkspace,
            ),
          ),
          tools = AgentToolPermissions(normalizedCliTool),
          network = NetworkAccessScope.Unrestricted,
          git = AgentGitPermissions(createBranch = true, commit = true, push = true, rollback = true),
          issues = AgentIssuePermissions(create = true, modify = true, close = true),
          resources = resourceLimits,
        )
