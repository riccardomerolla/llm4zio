package app.boundary

import zio.*
import zio.http.*

import _root_.config.boundary.{
  ConfigController as ConfigBoundaryController,
  SettingsController as SettingsBoundaryController,
  WorkflowsController as ConfigWorkflowsController,
}

trait ConfigRouteModule:
  def routes: Routes[Any, Response]

object ConfigRouteModule:
  val live
    : ZLayer[
      SettingsBoundaryController & ConfigBoundaryController & ConfigWorkflowsController,
      Nothing,
      ConfigRouteModule,
    ] =
    ZLayer {
      for
        settings  <- ZIO.service[SettingsBoundaryController]
        config    <- ZIO.service[ConfigBoundaryController]
        workflows <- ZIO.service[ConfigWorkflowsController]
      yield new ConfigRouteModule:
        override val routes: Routes[Any, Response] =
          settings.routes ++ config.routes ++ workflows.routes
    }
