package llm4zio.agents

import zio.*
import zio.json.*
import llm4zio.core.Message
import java.time.Instant

enum MemoryError derives JsonCodec:
  case NotFound(threadId: String)
  case PersistenceFailed(message: String)
  case InvalidInput(message: String)

object MemoryError:
  extension (error: MemoryError)
    def message: String = error match
      case MemoryError.NotFound(threadId)       => s"Thread not found: $threadId"
      case MemoryError.PersistenceFailed(msg)   => msg
      case MemoryError.InvalidInput(msg)        => msg

case class MemoryEntry(
  threadId: String,
  message: Message,
  recordedAt: Instant,
) derives JsonCodec

case class ConversationThread(
  threadId: String,
  parentThreadId: Option[String] = None,
  history: Vector[Message] = Vector.empty,
  metadata: Map[String, String] = Map.empty,
  updatedAt: Instant = Instant.EPOCH,
) derives JsonCodec

trait Memory:
  def append(threadId: String, message: Message): IO[MemoryError, Unit]
  def appendAll(threadId: String, messages: Iterable[Message]): IO[MemoryError, Unit]
  def read(threadId: String): IO[MemoryError, Vector[Message]]
  def upsert(thread: ConversationThread): IO[MemoryError, Unit]
  def fork(fromThreadId: String, newThreadId: String): IO[MemoryError, ConversationThread]
  def search(query: String, limit: Int = 20): IO[MemoryError, List[MemoryEntry]]

object Memory:
  def append(threadId: String, message: Message): ZIO[Memory, MemoryError, Unit] =
    ZIO.serviceWithZIO[Memory](_.append(threadId, message))

  def appendAll(threadId: String, messages: Iterable[Message]): ZIO[Memory, MemoryError, Unit] =
    ZIO.serviceWithZIO[Memory](_.appendAll(threadId, messages))

  def read(threadId: String): ZIO[Memory, MemoryError, Vector[Message]] =
    ZIO.serviceWithZIO[Memory](_.read(threadId))

  def upsert(thread: ConversationThread): ZIO[Memory, MemoryError, Unit] =
    ZIO.serviceWithZIO[Memory](_.upsert(thread))

  def fork(fromThreadId: String, newThreadId: String): ZIO[Memory, MemoryError, ConversationThread] =
    ZIO.serviceWithZIO[Memory](_.fork(fromThreadId, newThreadId))

  def search(query: String, limit: Int = 20): ZIO[Memory, MemoryError, List[MemoryEntry]] =
    ZIO.serviceWithZIO[Memory](_.search(query, limit))

  def inMemory: UIO[Memory] =
    for
      ref <- Ref.make(Map.empty[String, ConversationThread])
    yield InMemoryMemory(ref)

  val inMemoryLayer: ULayer[Memory] = ZLayer.fromZIO(inMemory)

final case class InMemoryMemory(
  state: Ref[Map[String, ConversationThread]]
) extends Memory:

  override def append(threadId: String, message: Message): IO[MemoryError, Unit] =
    appendAll(threadId, List(message))

  override def appendAll(threadId: String, messages: Iterable[Message]): IO[MemoryError, Unit] =
    for
      now <- Clock.instant
      _ <- state.update { current =>
             val existing = current.getOrElse(threadId, ConversationThread(threadId = threadId))
             current.updated(
               threadId,
               existing.copy(
                 history = existing.history ++ messages,
                 updatedAt = now,
               ),
             )
           }
    yield ()

  override def read(threadId: String): IO[MemoryError, Vector[Message]] =
    state.get.flatMap { current =>
      ZIO.fromOption(current.get(threadId).map(_.history)).orElseFail(MemoryError.NotFound(threadId))
    }

  override def upsert(thread: ConversationThread): IO[MemoryError, Unit] =
    state.update(_.updated(thread.threadId, thread)).unit

  override def fork(fromThreadId: String, newThreadId: String): IO[MemoryError, ConversationThread] =
    for
      source <- state.get.flatMap { current =>
                  ZIO.fromOption(current.get(fromThreadId)).orElseFail(MemoryError.NotFound(fromThreadId))
                }
      now <- Clock.instant
      forked = source.copy(
                 threadId = newThreadId,
                 parentThreadId = Some(fromThreadId),
                 updatedAt = now,
               )
      _ <- upsert(forked)
    yield forked

  override def search(query: String, limit: Int = 20): IO[MemoryError, List[MemoryEntry]] =
    if query.trim.isEmpty then ZIO.fail(MemoryError.InvalidInput("Search query must be non-empty"))
    else
      for
        threads <- state.get
        normalized = query.toLowerCase
        entries = threads.values.toList.flatMap { thread =>
                    thread.history
                      .filter(message => message.content.toLowerCase.contains(normalized))
                      .map(message => MemoryEntry(thread.threadId, message, thread.updatedAt))
                  }
      yield entries.sortBy(_.recordedAt).reverse.take(limit)

trait PersistentMemoryStore:
  def upsertThread(thread: ConversationThread): IO[MemoryError, Unit]
  def loadThread(threadId: String): IO[MemoryError, Option[ConversationThread]]
  def appendEntry(entry: MemoryEntry): IO[MemoryError, Unit]
  def searchEntries(query: String, limit: Int): IO[MemoryError, List[MemoryEntry]]

final case class PersistentMemory(
  inMemory: Memory,
  store: PersistentMemoryStore,
) extends Memory:
  override def append(threadId: String, message: Message): IO[MemoryError, Unit] =
    appendAll(threadId, List(message))

  override def appendAll(threadId: String, messages: Iterable[Message]): IO[MemoryError, Unit] =
    for
      _ <- inMemory.appendAll(threadId, messages)
      now <- Clock.instant
      _ <- ZIO.foreachDiscard(messages) { message =>
             store.appendEntry(MemoryEntry(threadId, message, now))
           }
      thread <- loadFromFastMemoryOrStore(threadId)
      _ <- store.upsertThread(thread)
    yield ()

  override def read(threadId: String): IO[MemoryError, Vector[Message]] =
    inMemory.read(threadId).catchAll {
      case MemoryError.NotFound(_) =>
        store.loadThread(threadId).flatMap {
          case Some(thread) => inMemory.upsert(thread) *> ZIO.succeed(thread.history)
          case None         => ZIO.fail(MemoryError.NotFound(threadId))
        }
      case other                   => ZIO.fail(other)
    }

  override def upsert(thread: ConversationThread): IO[MemoryError, Unit] =
    inMemory.upsert(thread) *> store.upsertThread(thread)

  override def fork(fromThreadId: String, newThreadId: String): IO[MemoryError, ConversationThread] =
    for
      forked <- inMemory.fork(fromThreadId, newThreadId)
      _ <- store.upsertThread(forked)
    yield forked

  override def search(query: String, limit: Int = 20): IO[MemoryError, List[MemoryEntry]] =
    inMemory.search(query, limit).catchAll {
      case MemoryError.NotFound(_) => store.searchEntries(query, limit)
      case MemoryError.InvalidInput(msg) => ZIO.fail(MemoryError.InvalidInput(msg))
      case _                             => store.searchEntries(query, limit)
    }

  private def loadFromFastMemoryOrStore(threadId: String): IO[MemoryError, ConversationThread] =
    inMemory.read(threadId)
      .map(history => ConversationThread(threadId = threadId, history = history))
      .catchAll {
        case MemoryError.NotFound(_) =>
          store.loadThread(threadId).flatMap {
            case Some(thread) => ZIO.succeed(thread)
            case None         => ZIO.succeed(ConversationThread(threadId = threadId))
          }
        case other                   => ZIO.fail(other)
      }

trait SemanticMemory:
  def upsert(id: String, text: String, metadata: Map[String, String] = Map.empty): IO[MemoryError, Unit]
  def query(text: String, limit: Int = 10): IO[MemoryError, List[(String, Double)]]

object SemanticMemory:
  val disabled: SemanticMemory =
    new SemanticMemory:
      override def upsert(id: String, text: String, metadata: Map[String, String]): IO[MemoryError, Unit] =
        ZIO.unit

      override def query(text: String, limit: Int): IO[MemoryError, List[(String, Double)]] =
        ZIO.succeed(List.empty)
