package workspace.control

import zio.*

import _root_.config.entity.ConfigRepository
import demo.control.MockAgentRunner
import demo.entity.DemoConfig
import workspace.entity.WorkspaceRunService as WorkspaceRunServiceTrait

object WorkspaceRunServiceFactory:

  val live: ZLayer[ConfigRepository & WorkspaceRunService.LiveDeps, Nothing, WorkspaceRunServiceTrait] =
    ZLayer.scoped {
      for
        configRepo                               <- ZIO.service[ConfigRepository]
        rows                                     <- configRepo.getAllSettings.orElseSucceed(Nil)
        demoConfig                                = DemoConfig.fromSettings(rows.map(r => r.key -> r.value).toMap)
        mockFn                                    = MockAgentRunner.runner(demoConfig)
        runner: WorkspaceRunService.RunCliAgentFn =
          (argv, cwd, onLine, envVars) =>
            if argv.headOption.contains("mock") then mockFn(argv, cwd, onLine, envVars)
            else CliAgentRunner.runProcessStreaming(argv, cwd, onLine, envVars)
        wsService                                <- WorkspaceRunService.liveWithAgent(runner).build.map(_.get[WorkspaceRunServiceTrait])
      yield wsService
    }
