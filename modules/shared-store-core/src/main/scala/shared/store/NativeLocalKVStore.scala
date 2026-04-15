package shared.store

import zio.*
import zio.json.*
import zio.stream.ZStream

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.service.ObjectStore

/** DataStoreService implementation backed by NativeLocal JSON snapshots. Values are serialized to JSON via zio-json
  * codecs, eliminating unsafe .toString / .asInstanceOf casts.
  */
final class NativeLocalKVStore(objectStore: ObjectStore[KVRoot]) extends DataStoreService:

  override def store[K, V](key: K, value: V)(using encoder: JsonEncoder[V]): IO[EclipseStoreError, Unit] =
    val keyStr   = key.toString
    val valueStr = encoder.encodeJson(value, None).toString
    objectStore
      .modify { root =>
        ZIO.succeed(((), root.copy(entries = root.entries.updated(keyStr, valueStr))))
      }
      .unit

  override def fetch[K, V](key: K)(using decoder: JsonDecoder[V]): IO[EclipseStoreError, Option[V]] =
    objectStore.load.flatMap { root =>
      root.entries.get(key.toString) match
        case None       => ZIO.none
        case Some(json) =>
          ZIO
            .fromEither(decoder.decodeJson(json))
            .mapBoth(
              err => EclipseStoreError.StorageError(s"JSON decode failed for key '${key.toString}': $err", None),
              Some(_),
            )
    }

  override def remove[K](key: K): IO[EclipseStoreError, Unit] =
    objectStore
      .modify { root =>
        ZIO.succeed(((), root.copy(entries = root.entries.removed(key.toString))))
      }
      .unit

  override def fetchAll[V](using decoder: JsonDecoder[V]): IO[EclipseStoreError, List[V]] =
    objectStore.load.flatMap { root =>
      ZIO.foreach(root.entries.values.toList) { json =>
        ZIO
          .fromEither(decoder.decodeJson(json))
          .mapError(err => EclipseStoreError.StorageError(s"JSON decode failed: $err", None))
      }
    }

  override def streamAll[V](using decoder: JsonDecoder[V]): ZStream[Any, EclipseStoreError, V] =
    ZStream.fromIterableZIO(objectStore.load.map(_.entries.values)).mapZIO { json =>
      ZIO
        .fromEither(decoder.decodeJson(json))
        .mapError(err => EclipseStoreError.StorageError(s"JSON decode failed: $err", None))
    }

  override def streamKeys[K]: ZStream[Any, EclipseStoreError, K] =
    ZStream.fromIterableZIO(objectStore.load.map(_.entries.keys.map(_.asInstanceOf[K])))

  override def checkpoint: IO[EclipseStoreError, Unit] =
    objectStore.checkpoint

  override def fetchRawJson(key: String): IO[EclipseStoreError, Option[String]] =
    objectStore.load.map(_.entries.get(key))
