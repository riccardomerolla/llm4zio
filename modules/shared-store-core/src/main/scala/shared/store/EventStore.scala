package shared.store

import zio.IO

import shared.errors.PersistenceError

trait EventStore[Id, E]:
  def append(id: Id, event: E): IO[PersistenceError, Unit]
  def events(id: Id): IO[PersistenceError, List[E]]
  def eventsSince(id: Id, sequence: Long): IO[PersistenceError, List[E]]
