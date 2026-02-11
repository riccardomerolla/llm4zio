package web.controllers

import zio.*
import zio.http.*

import agents.AgentRegistry
import web.views.HtmlViews

trait AgentsController:
  def routes: Routes[Any, Response]

object AgentsController:

  def routes: ZIO[AgentsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[AgentsController](_.routes)

  val live: ZLayer[Any, Nothing, AgentsController] =
    ZLayer.succeed(AgentsControllerLive())

final case class AgentsControllerLive() extends AgentsController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "agents" -> handler {
      html(HtmlViews.agentsPage(AgentRegistry.builtInAgents))
    }
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
