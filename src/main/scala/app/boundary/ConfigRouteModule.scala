package app.boundary

import zio.*
import zio.http.*

import _root_.config.boundary.{
  ConfigController as ConfigBoundaryController,
  SettingsController as SettingsBoundaryController,
  WorkflowsController as ConfigWorkflowsController,
}
import daemon.boundary.DaemonsController
import governance.boundary.GovernanceController
import governance.entity.GovernancePolicyRepository

trait ConfigRouteModule:
  def routes: Routes[Any, Response]

object ConfigRouteModule:
  val live
    : ZLayer[
      SettingsBoundaryController & ConfigBoundaryController & ConfigWorkflowsController &
        DaemonsController & GovernancePolicyRepository,
      Nothing,
      ConfigRouteModule,
    ] =
    ZLayer {
      for
        settings             <- ZIO.service[SettingsBoundaryController]
        config               <- ZIO.service[ConfigBoundaryController]
        workflows            <- ZIO.service[ConfigWorkflowsController]
        daemons              <- ZIO.service[DaemonsController]
        governancePolicyRepo <- ZIO.service[GovernancePolicyRepository]
      yield new ConfigRouteModule:
        override val routes: Routes[Any, Response] =
          settings.routes ++
            config.routes ++
            workflows.routes ++
            daemons.routes ++
            GovernanceController.routes(governancePolicyRepo)
    }
