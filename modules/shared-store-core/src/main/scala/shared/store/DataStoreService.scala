package shared.store

import zio.*
import zio.json.*
import zio.stream.ZStream

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError

/** Schema-validated key-value store service. Backed by NativeLocal JSON snapshots. Domain modules depend on this trait
  * for CRUD and key-prefix scanning. Values are serialized/deserialized via zio-json codecs — all domain types must
  * derive JsonCodec.
  */
trait DataStoreService:
  def store[K, V](key: K, value: V)(using JsonEncoder[V]): IO[EclipseStoreError, Unit]
  def fetch[K, V](key: K)(using JsonDecoder[V]): IO[EclipseStoreError, Option[V]]
  def remove[K](key: K): IO[EclipseStoreError, Unit]
  def fetchAll[V](using JsonDecoder[V]): IO[EclipseStoreError, List[V]]
  def streamAll[V](using JsonDecoder[V]): ZStream[Any, EclipseStoreError, V]
  def streamKeys[K]: ZStream[Any, EclipseStoreError, K]
  def checkpoint: IO[EclipseStoreError, Unit]

  /** Fetch the raw JSON string for a key, without attempting to decode it. Useful for debug/inspection endpoints. */
  def fetchRawJson(key: String): IO[EclipseStoreError, Option[String]]
