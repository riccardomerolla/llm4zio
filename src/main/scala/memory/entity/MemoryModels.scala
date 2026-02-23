package memory.entity

import java.time.Instant

import zio.json.*

opaque type MemoryId = String
object MemoryId:
  def apply(value: String): MemoryId = value
  def make: MemoryId                 = java.util.UUID.randomUUID().toString

  extension (id: MemoryId)
    def value: String = id

  given JsonCodec[MemoryId] = JsonCodec.string.transform(MemoryId.apply, _.value)

opaque type UserId = String
object UserId:
  def apply(value: String): UserId = value

  extension (id: UserId)
    def value: String = id

  given JsonCodec[UserId] = JsonCodec.string.transform(UserId.apply, _.value)

opaque type SessionId = String
object SessionId:
  def apply(value: String): SessionId = value

  extension (id: SessionId)
    def value: String = id

  given JsonCodec[SessionId] = JsonCodec.string.transform(SessionId.apply, _.value)

opaque type MemoryKind = String
object MemoryKind:
  val Preference: MemoryKind = "Preference"
  val Fact: MemoryKind       = "Fact"
  val Context: MemoryKind    = "Context"
  val Summary: MemoryKind    = "Summary"

  def apply(value: String): MemoryKind = value

  extension (kind: MemoryKind)
    def value: String = kind

  given JsonCodec[MemoryKind] = JsonCodec.string.transform(MemoryKind.apply, _.value)

final case class MemoryEntry(
  id: MemoryId,
  userId: UserId,
  sessionId: SessionId,
  text: String,
  embedding: Vector[Float],
  tags: List[String],
  kind: MemoryKind,
  createdAt: Instant,
  lastAccessedAt: Instant,
) derives JsonCodec

final case class ScoredMemory(
  entry: MemoryEntry,
  score: Float,
)

final case class MemoryFilter(
  userId: Option[UserId] = None,
  sessionId: Option[SessionId] = None,
  tags: List[String] = Nil,
  kind: Option[MemoryKind] = None,
)
