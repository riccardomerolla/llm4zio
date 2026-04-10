package shared.store

import io.github.riccardomerolla.zio.eclipsestore.schema.TypedStore
import io.github.riccardomerolla.zio.eclipsestore.service.EclipseStoreService

/** DataStoreService IS-A TypedStore for schema-validated CRUD and additionally exposes the raw EclipseStoreService for
  * key-prefix scanning (streamKeys). Trait extracted to shared-store-core so domain modules can depend on it without
  * pulling in the full DataStoreModule implementation.
  */
trait DataStoreService extends TypedStore:
  def rawStore: EclipseStoreService
