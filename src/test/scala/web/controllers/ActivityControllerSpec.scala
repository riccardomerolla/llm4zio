package web.controllers

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import activity.boundary.ActivityController
import activity.entity.{ ActivityEvent, ActivityEventType, ActivityRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.EventId

object ActivityControllerSpec extends ZIOSpecDefault:

  private def controllerLayer(seenSince: Ref[Option[Instant]]): ULayer[ActivityController] =
    val repository = new ActivityRepository:
      override def createEvent(event: ActivityEvent): IO[PersistenceError, EventId] =
        ZIO.succeed(event.id)

      override def listEvents(
        eventType: Option[ActivityEventType],
        since: Option[Instant],
        limit: Int,
      ): IO[PersistenceError, List[ActivityEvent]] =
        seenSince.set(since).as(Nil)

    ZLayer.succeed(repository) >>> ActivityController.live

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ActivityControllerSpec")(
    test("GET /api/activity/events ignores invalid since values") {
      for
        seenSince  <- Ref.make(Option.empty[Instant])
        controller <- ZIO.service[ActivityController].provideLayer(controllerLayer(seenSince))
        response   <-
          controller.routes.runZIO(Request.get(URL.decode("/api/activity/events?since=not-a-date").toOption.get))
        parsed     <- seenSince.get
      yield assertTrue(response.status == Status.Ok, parsed.isEmpty)
    },
    test("GET /api/activity/events forwards valid since values") {
      val since = Instant.parse("2026-03-31T10:15:30Z")
      for
        seenSince  <- Ref.make(Option.empty[Instant])
        controller <- ZIO.service[ActivityController].provideLayer(controllerLayer(seenSince))
        response   <- controller.routes.runZIO(
                        Request.get(URL.decode(s"/api/activity/events?since=${since.toString}").toOption.get)
                      )
        parsed     <- seenSince.get
      yield assertTrue(response.status == Status.Ok, parsed.contains(since))
    },
  )
