package bankmod.graph.model

/** Opaque typed identifiers for all first-class domain entities in the graph model.
  *
  * All smart constructors return `Either[String, T]` — never raw wrapping. Callers must go through `from`.
  */

opaque type ServiceId = String

object ServiceId:
  private val pattern = "^[a-z][a-z0-9-]{1,40}$".r

  def from(s: String): Either[String, ServiceId] =
    if pattern.matches(s) then Right(s)
    else Left(s"Invalid ServiceId '$s': must match ^[a-z][a-z0-9-]{1,40}$$")

  extension (id: ServiceId) def value: String = id

opaque type PortName = String

object PortName:
  def from(s: String): Either[String, PortName] =
    if s.nonEmpty then Right(s)
    else Left("PortName must not be empty")

  extension (p: PortName) def value: String = p

opaque type TopicName = String

object TopicName:
  def from(s: String): Either[String, TopicName] =
    if s.nonEmpty then Right(s)
    else Left("TopicName must not be empty")

  extension (t: TopicName) def value: String = t

opaque type TableName = String

object TableName:
  def from(s: String): Either[String, TableName] =
    if s.nonEmpty then Right(s)
    else Left("TableName must not be empty")

  extension (t: TableName) def value: String = t

opaque type SchemaHash = String

object SchemaHash:
  def from(s: String): Either[String, SchemaHash] =
    if s.nonEmpty then Right(s)
    else Left("SchemaHash must not be empty")

  extension (h: SchemaHash) def value: String = h
