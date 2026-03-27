package gateway.boundary

import zio.*
import zio.http.*
import zio.test.*

import _root_.config.entity.GatewayConfig
import db.*
import shared.errors.PersistenceError
import gateway.control.*

object ChannelControllerSpec extends ZIOSpecDefault:

  final private case class InMemoryConfigRepo(ref: Ref[Map[String, SettingRow]]) extends ConfigRepository:
    override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
      ref.get.map(_.values.toList)

    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
      ref.get.map(_.get(key))

    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
      Clock.instant.flatMap(now => ref.update(_.updated(key, SettingRow(key, value, now)))).unit

    override def deleteSetting(key: String): IO[PersistenceError, Unit] = ref.update(_ - key).unit

    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
      ref.update(_.filterNot(_._1.startsWith(prefix))).unit

    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]          =
      ZIO.fail(PersistenceError.QueryFailed("createWorkflow", "unused"))
    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]           =
      ZIO.fail(PersistenceError.QueryFailed("getWorkflow", "unused"))
    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getWorkflowByName", "unused"))
    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                     =
      ZIO.fail(PersistenceError.QueryFailed("listWorkflows", "unused"))
    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]          =
      ZIO.fail(PersistenceError.QueryFailed("updateWorkflow", "unused"))
    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                       =
      ZIO.fail(PersistenceError.QueryFailed("deleteWorkflow", "unused"))

    override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             =
      ZIO.fail(PersistenceError.QueryFailed("createCustomAgent", "unused"))
    override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           =
      ZIO.fail(PersistenceError.QueryFailed("getCustomAgent", "unused"))
    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getCustomAgentByName", "unused"))
    override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     =
      ZIO.fail(PersistenceError.QueryFailed("listCustomAgents", "unused"))
    override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             =
      ZIO.fail(PersistenceError.QueryFailed("updateCustomAgent", "unused"))
    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          =
      ZIO.fail(PersistenceError.QueryFailed("deleteCustomAgent", "unused"))

  private val gatewayService: GatewayService = new GatewayService:
    override def enqueueInbound(message: gateway.entity.NormalizedMessage): UIO[Unit]                     = ZIO.unit
    override def enqueueOutbound(message: gateway.entity.NormalizedMessage): UIO[Unit]                    = ZIO.unit
    override def processInbound(message: gateway.entity.NormalizedMessage): IO[GatewayServiceError, Unit] = ZIO.unit
    override def processOutbound(
      message: gateway.entity.NormalizedMessage
    ): IO[GatewayServiceError, List[gateway.entity.NormalizedMessage]] = ZIO.succeed(Nil)
    override def metrics: UIO[GatewayMetricsSnapshot]                                                     = ZIO.succeed(GatewayMetricsSnapshot())

  private def mkController: UIO[(ChannelControllerLive, ChannelRegistry)] =
    for
      channels <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
      runtime  <- Ref.Synchronized.make(Map.empty[String, ChannelRuntime])
      registry  = ChannelRegistryLive(channels, runtime)
      settings <- Ref.make(Map.empty[String, SettingRow])
      repo      = InMemoryConfigRepo(settings)
      cfgRef   <- Ref.make(GatewayConfig())
    yield (ChannelControllerLive(registry, gatewayService, cfgRef, repo), registry)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ChannelControllerSpec")(
    test("enabling discord settings re-registers channel and clears NotConfigured status") {
      for
        tuple                 <- mkController
        (controller, registry) = tuple
        _                     <- registry.markNotConfigured("discord")
        request                = Request.post(
                                   URL.decode("/settings/channels/discord").toOption.get,
                                   Body.fromString("enabled=true&botToken=test-token&sessionScopeStrategy=PerConversation"),
                                 )
        response              <- controller.routes.runZIO(request)
        runtime               <- registry.getRuntime("discord")
      yield assertTrue(
        response.status == Status.Ok,
        runtime.status == ChannelStatus.Disconnected,
      )
    },
    test("disabling discord settings marks channel as NotConfigured") {
      for
        tuple                 <- mkController
        (controller, registry) = tuple
        existing              <-
          DiscordChannel.make(config = DiscordConfig(botToken = "token", guildId = None, defaultChannelId = None))
        _                     <- registry.register(existing)
        request                = Request.post(
                                   URL.decode("/settings/channels/discord").toOption.get,
                                   Body.fromString("botToken=test-token&sessionScopeStrategy=PerConversation"),
                                 )
        response              <- controller.routes.runZIO(request)
        runtime               <- registry.getRuntime("discord")
      yield assertTrue(
        response.status == Status.Ok,
        runtime.status == ChannelStatus.NotConfigured,
      )
    },
  )
