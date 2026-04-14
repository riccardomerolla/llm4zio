package conversation.boundary

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream

import conversation.entity.DialogueEvent
import orchestration.control.WorkReportEventBus
import shared.ids.Ids.ConversationId

object DialogueSseController:

  def routes(eventBus: WorkReportEventBus): Routes[Any, Nothing] =
    Routes(
      Method.GET / "api" / "dialogues" / string("conversationId") / "sse" -> handler {
        (conversationId: String, _: Request) =>
          val convId = ConversationId(conversationId)
          ZIO.scoped {
            eventBus.subscribeDialogue.map { dequeue =>
              val stream: ZStream[Any, Nothing, String] =
                ZStream.fromQueue(dequeue)
                  .filter(_.conversationId == convId)
                  .map { event =>
                    val eventType = event match
                      case _: DialogueEvent.DialogueStarted   => "dialogue-started"
                      case _: DialogueEvent.MessagePosted     => "message-posted"
                      case _: DialogueEvent.TurnChanged       => "turn-changed"
                      case _: DialogueEvent.HumanIntervened   => "human-intervened"
                      case _: DialogueEvent.DialogueConcluded => "dialogue-concluded"
                    s"event: $eventType\ndata: ${event.toJson}\n\n"
                  }
              Response(
                status = Status.Ok,
                headers = Headers(
                  Header.ContentType(MediaType.text.`event-stream`),
                  Header.CacheControl.NoCache,
                  Header.Custom("Connection", "keep-alive"),
                ),
                body = Body.fromCharSequenceStreamChunked(stream),
              )
            }
          }
      }
    )
