package shared.store

import zio.schema.{ DeriveSchema, Schema }

import io.github.riccardomerolla.zio.eclipsestore.domain.RootDescriptor

/** Root aggregate for NativeLocal JSON-backed key-value stores. Each store module (Data, Config) uses its own snapshot
  * file containing a single KVRoot. All values are stored as JSON strings to avoid Eclipse Serializer's binary
  * serialization issues with Scala 3 enums and case objects.
  */
final case class KVRoot(entries: Map[String, String] = Map.empty)

object KVRoot:
  given Schema[KVRoot] = DeriveSchema.gen[KVRoot]

  val dataDescriptor: RootDescriptor[KVRoot] = RootDescriptor.fromSchema(
    id = "data-store",
    initializer = () => KVRoot(),
  )

  val configDescriptor: RootDescriptor[KVRoot] = RootDescriptor.fromSchema(
    id = "config-store",
    initializer = () => KVRoot(),
  )
