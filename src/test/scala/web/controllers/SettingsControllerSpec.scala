package web.controllers

import java.nio.file.{ Files, Paths }

import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*

import _root_.config.boundary.SettingsController
import _root_.config.control.ModelService
import _root_.config.entity.*
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType, ActivityRepository }
import conversation.entity.ConversationRow
import io.github.riccardomerolla.zio.eclipsestore.service.LifecycleCommand
import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema, ToolRegistry }
import shared.errors.PersistenceError
import shared.store.{ ConfigStoreModule, DataStoreModule, DataStoreService, MemoryStoreModule, StoreConfig }

object SettingsControllerSpec extends ZIOSpecDefault:

  private val stubLlmService: LlmService = new LlmService:
    def execute(prompt: String): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse("ok"))

    override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
      ZStream.empty

    def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse("ok"))

    override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
      ZStream.empty

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.succeed(ToolCallResponse(None, Nil, "stop"))

    override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.ConfigError("not implemented in test"))

    override def isAvailable: UIO[Boolean] =
      ZIO.succeed(true)

  private val stubModelService: ModelService = new ModelService:
    override def listAvailableModels: UIO[ModelRegistryResponse]                              = ZIO.succeed(ModelRegistryResponse(Nil))
    override def probeProviders: UIO[List[ProviderProbeStatus]]                               = ZIO.succeed(Nil)
    override def resolveFallbackChain(primary: ProviderConfig): UIO[List[ProviderConfig]] =
      ZIO.succeed(List(primary))

  final private case class InMemoryConfigRepository(ref: Ref[Map[String, SettingRow]]) extends ConfigRepository:
    override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
      ref.get.map(_.values.toList.sortBy(_.key))

    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
      ref.get.map(_.get(key))

    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
      Clock.instant.flatMap(now => ref.update(_.updated(key, SettingRow(key, value, now))))

    override def deleteSetting(key: String): IO[PersistenceError, Unit] =
      ref.update(_ - key).unit

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

  private def mkLayer
    : UIO[
      ZLayer[Any, Nothing, SettingsController & ConfigRepository & StoreConfig & DataStoreService]
    ] =
    for
      tempDir   <- ZIO.attemptBlocking(Files.createTempDirectory("settings-controller-spec")).orDie
      storeCfg   = StoreConfig(
                     configStorePath = tempDir.resolve("config-store").toString,
                     dataStorePath = tempDir.resolve("data-store").toString,
                   )
      configRef <- Ref.make(Map.empty[String, SettingRow])
      configRepo = InMemoryConfigRepository(configRef)
      hub        = new ActivityHub:
                     override def publish(event: ActivityEvent): UIO[Unit] = ZIO.unit
                     override def subscribe: UIO[Dequeue[ActivityEvent]]   = Queue.unbounded[ActivityEvent].map(identity)
    yield ZLayer.make[SettingsController & ConfigRepository & StoreConfig & DataStoreService](
      ZLayer.succeed(storeCfg),
      ConfigStoreModule.live.mapError(err => new RuntimeException(err.toString)).orDie,
      DataStoreModule.live.mapError(err => new RuntimeException(err.toString)).orDie,
      MemoryStoreModule.live.mapError(err => new RuntimeException(err.toString)).orDie,
      ZLayer.succeed(configRepo),
      ZLayer.succeed(hub),
      ZLayer.fromZIO(Ref.make(MigrationConfig())),
      ZLayer.succeed(stubLlmService),
      ZLayer.succeed(stubModelService),
      ToolRegistry.layer,
      SettingsController.live,
    )

  private def mkLayerWithPersistentActivity
    : UIO[
      ZLayer[
        Any,
        Nothing,
        SettingsController & ConfigRepository & StoreConfig & ActivityRepository & ConfigStoreModule.ConfigStoreService,
      ]
    ] =
    for
      tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("settings-controller-activity-spec")).orDie
      storeCfg = StoreConfig(
                   configStorePath = tempDir.resolve("config-store").toString,
                   dataStorePath = tempDir.resolve("data-store").toString,
                 )
    yield ZLayer.make[
      SettingsController & ConfigRepository & StoreConfig & ActivityRepository & ConfigStoreModule.ConfigStoreService
    ](
      ZLayer.succeed(storeCfg),
      ConfigStoreModule.live.mapError(err => new RuntimeException(err.toString)).orDie,
      DataStoreModule.live.mapError(err => new RuntimeException(err.toString)).orDie,
      MemoryStoreModule.live.mapError(err => new RuntimeException(err.toString)).orDie,
      ConfigRepositoryES.live,
      ActivityRepository.live,
      ActivityHub.live,
      ZLayer.fromZIO(Ref.make(MigrationConfig())),
      ZLayer.succeed(stubLlmService),
      ZLayer.succeed(stubModelService),
      ToolRegistry.layer,
      SettingsController.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SettingsControllerSpec")(
      test("POST /api/store/reset-data clears operational data and keeps config settings") {
        for
          layer  <- mkLayer
          result <- ZIO.scoped {
                      for
                        env         <- layer.build
                        controller  <- ZIO.service[SettingsController].provideEnvironment(env)
                        configRepo  <- ZIO.service[ConfigRepository].provideEnvironment(env)
                        storeConfig <- ZIO.service[StoreConfig].provideEnvironment(env)
                        _           <- configRepo.upsertSetting("spec.keep", "yes")
                        response    <- controller.routes.runZIO(Request.post("/api/store/reset-data", Body.empty))
                        body        <- response.body.asString
                        setting     <- configRepo.getSetting("spec.keep")
                        dataDirOk   <- ZIO.attemptBlocking(Files.exists(Paths.get(storeConfig.dataStorePath))).orDie
                      yield assertTrue(
                        response.status == Status.Ok,
                        body.contains("reset completed"),
                        setting.exists(_.value == "yes"),
                        dataDirOk,
                      )
                    }
        yield result
      },
      test("POST /settings persists ConfigChanged activity event end-to-end") {
        for
          layer  <- mkLayerWithPersistentActivity
          result <- ZIO.scoped {
                      for
                        env         <- layer.build
                        controller  <- ZIO.service[SettingsController].provideEnvironment(env)
                        activity    <- ZIO.service[ActivityRepository].provideEnvironment(env)
                        configStore <- ZIO.service[ConfigStoreModule.ConfigStoreService].provideEnvironment(env)
                        _           <- configStore.store("setting:warmup", "1")
                        response    <- controller.routes.runZIO(Request.post(
                                         "/settings",
                                         Body.fromString("gateway.name=SpecGateway"),
                                       ))
                        events      <- activity.listEvents(eventType = Some(ActivityEventType.ConfigChanged), limit = 20)
                      yield assertTrue(
                        response.status == Status.Ok,
                        events.exists(event =>
                          event.source == "settings" && event.summary == "Application settings updated"
                        ),
                      )
                    }
        yield result
      },
      test("GET /api/store/debug-data returns raw entries and store size") {
        for
          layer  <- mkLayer
          result <- ZIO.scoped {
                      for
                        env        <- layer.build
                        controller <- ZIO.service[SettingsController].provideEnvironment(env)
                        dataStore  <- ZIO.service[DataStoreService].provideEnvironment(env)
                        _          <- dataStore.store(
                                        "conv:9001",
                                        ConversationRow(
                                          id = "9001",
                                          title = "debug-conversation",
                                          description = Some("persist-check"),
                                          channelName = Some("web"),
                                          status = "active",
                                          createdAt = java.time.Instant.parse("2026-02-21T12:00:00Z"),
                                          updatedAt = java.time.Instant.parse("2026-02-21T12:00:00Z"),
                                          runId = Some("run-debug"),
                                          createdBy = Some("spec"),
                                        ),
                                      )
                        _          <- dataStore.rawStore.maintenance(LifecycleCommand.Checkpoint)
                        response   <- controller.routes.runZIO(Request.get("/api/store/debug-data"))
                        body       <- response.body.asString
                      yield assertTrue(
                        response.status == Status.Ok,
                        body.contains("\"dataStorePath\":"),
                        body.contains("\"dataStoreInstanceId\":"),
                        body.contains("\"dataStoreBytes\":"),
                        body.contains("\"dataStorePrefixCounts\":"),
                        body.contains("\"dataEntries\":"),
                        body.contains("\"key\":\"conv:9001\""),
                        body.contains("\"prefix\":\"conv\""),
                        body.contains("\"rawValue\":"),
                        !body.contains("\"configStorePath\":"),
                        !body.contains("\"configStoreInstanceId\":"),
                        !body.contains("\"configEntries\":"),
                      )
                    }
        yield result
      },
      test("GET /api/store/debug-data includes config store only when requested") {
        for
          layer  <- mkLayer
          result <- ZIO.scoped {
                      for
                        env        <- layer.build
                        controller <- ZIO.service[SettingsController].provideEnvironment(env)
                        response   <- controller.routes.runZIO(
                                        Request.get(URL.decode("/api/store/debug-data?includeConfig=true").toOption.get)
                                      )
                        body       <- response.body.asString
                      yield assertTrue(
                        response.status == Status.Ok,
                        body.contains("\"configStorePath\":"),
                        body.contains("\"configStoreInstanceId\":"),
                        body.contains("\"configStoreBytes\":"),
                        body.contains("\"configStorePrefixCounts\":"),
                        body.contains("\"configEntries\":"),
                      )
                    }
        yield result
      },
    )
