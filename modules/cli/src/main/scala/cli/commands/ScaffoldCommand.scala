package cli.commands

import java.nio.file.{ Path, Paths }
import java.time.Duration

import zio.*
import zio.json.*

import agent.entity.{ Agent, AgentEvent, AgentRepository }
import board.entity.BoardRepository
import shared.ids.Ids.{ AgentId, ProjectId }
import shared.services.FileService
import workspace.entity.{ RunMode, WorkspaceEvent, WorkspaceRepository }

/** Scaffolding handlers for `workspace init` and `agent init`.
  *
  * Both commands produce a filesystem layout *and* register the new entity in the local event store so the gateway can
  * pick it up without further import.
  */
object ScaffoldCommand:

  private val defaultProjectId: ProjectId = ProjectId("default")
  private val defaultCliTool: String      = "llm4zio"

  private val governancePolicyStub: String =
    """|{
       |  "id": "governance-default",
       |  "projectId": "global-default",
       |  "name": "Default Governance Policy",
       |  "version": 0,
       |  "transitionRules": [],
       |  "daemonTriggers": [],
       |  "escalationRules": [],
       |  "completionCriteria": [],
       |  "isDefault": true,
       |  "createdAt": "1970-01-01T00:00:00Z",
       |  "updatedAt": "1970-01-01T00:00:00Z"
       |}
       |""".stripMargin

  private val evalExampleStub: String =
    """|{"prompt":"Sum 2 + 2","expected":"4","metadata":{"tag":"example"}}
       |""".stripMargin

  private def readmeStub(name: String, description: Option[String]): String =
    s"""|# $name
        |
        |${description.getOrElse("llm4zio workspace.")}
        |
        |## Layout
        |
        |- `.board/`       — kanban board (git-backed)
        |- `.llm4zio/`     — workspace metadata (governance policy, state)
        |- `agents/`       — agent definitions (`<name>.agent.json`)
        |- `evals/`        — eval datasets (JSONL)
        |
        |## Commands
        |
        |```
        |llm4zio-cli agent init <agent-name> --workspace .
        |llm4zio-cli board list .
        |```
        |""".stripMargin

  def slug(s: String): String =
    s.trim.toLowerCase
      .replaceAll("[^a-z0-9-]+", "-")
      .replaceAll("^-+|-+$", "")
      .replaceAll("-{2,}", "-")

  def initWorkspace(
    rawPath: String,
    name: Option[String],
    description: Option[String],
  ): ZIO[FileService & BoardRepository & WorkspaceRepository, String, String] =
    val root            = Paths.get(rawPath).toAbsolutePath.normalize
    val effectiveName   = name.getOrElse(root.getFileName.toString)
    val workspaceId     = slug(effectiveName)
    val llm4zioDir      = root.resolve(".llm4zio")
    val agentsDir       = root.resolve("agents")
    val evalsDir        = root.resolve("evals")
    val readmeFile      = root.resolve("README.md")
    val policyFile      = llm4zioDir.resolve("governance-policy.json")
    val agentsKeep      = agentsDir.resolve(".gitkeep")
    val evalExampleFile = evalsDir.resolve("example.jsonl")

    def ensure(p: Path): ZIO[FileService, String, Unit] =
      FileService.ensureDirectory(p).mapError(e => s"ensureDirectory($p) failed: $e")

    def write(p: Path, content: String): ZIO[FileService, String, Unit] =
      FileService.writeFileAtomic(p, content).mapError(e => s"write($p) failed: $e")

    for
      _   <- ensure(root)
      _   <- BoardRepository.initBoard(root.toString).mapError(e => s"initBoard failed: $e")
      _   <- ensure(llm4zioDir)
      _   <- ensure(agentsDir)
      _   <- ensure(evalsDir)
      _   <- write(policyFile, governancePolicyStub)
      _   <- write(agentsKeep, "")
      _   <- write(evalExampleFile, evalExampleStub)
      _   <- write(readmeFile, readmeStub(effectiveName, description))
      now <- Clock.instant
      _   <- ZIO
               .serviceWithZIO[WorkspaceRepository](_.append(
                 WorkspaceEvent.Created(
                   workspaceId = workspaceId,
                   projectId = defaultProjectId,
                   name = effectiveName,
                   localPath = root.toString,
                   defaultAgent = None,
                   description = description,
                   cliTool = defaultCliTool,
                   runMode = RunMode.Host,
                   occurredAt = now,
                 )
               ))
               .mapError(e => s"WorkspaceRepository.append failed: $e")
    yield
      s"""|Initialized workspace '$effectiveName' at $root
          |  id:          $workspaceId
          |  project:     ${defaultProjectId.value}
          |  board:       ${root.resolve(".board")}
          |  policy:      $policyFile
          |Next:
          |  llm4zio-cli agent init <name> --workspace $root
          |""".stripMargin

  def initAgent(
    workspaceRaw: String,
    name: String,
    model: Option[String],
    cliTool: String,
    description: Option[String],
  ): ZIO[FileService & AgentRepository, String, String] =
    val workspace       = Paths.get(workspaceRaw).toAbsolutePath.normalize
    val agentsDir       = workspace.resolve("agents")
    val agentName       = slug(name)
    val agentFile: Path = agentsDir.resolve(s"$agentName.agent.json")

    for
      now   <- Clock.instant
      agent  = Agent(
                 id = AgentId.generate,
                 name = agentName,
                 description = description.getOrElse(""),
                 cliTool = cliTool,
                 capabilities = Nil,
                 defaultModel = model,
                 systemPrompt = None,
                 maxConcurrentRuns = 1,
                 envVars = Map.empty,
                 timeout = Duration.ofMinutes(30),
                 enabled = true,
                 createdAt = now,
                 updatedAt = now,
               )
      _     <- FileService
                 .ensureDirectory(agentsDir)
                 .mapError(e => s"ensureDirectory($agentsDir) failed: $e")
      _     <- FileService
                 .writeFileAtomic(agentFile, agent.toJsonPretty + "\n")
                 .mapError(e => s"write($agentFile) failed: $e")
      _     <- AgentRepository
                 .append(AgentEvent.Created(agent, now))
                 .mapError(e => s"AgentRepository.append failed: $e")
    yield
      s"""|Created agent '$agentName'
          |  id:           ${agent.id.value}
          |  cliTool:      ${agent.cliTool}
          |  defaultModel: ${agent.defaultModel.getOrElse("<unset>")}
          |  file:         $agentFile
          |""".stripMargin
