# Level 2: Per-Domain Typed Roots for NativeLocal Stores

## Context

With the Level 1 migration (zio-eclipsestore 2.2.2 NativeLocal + JsonCodec-aware `DataStoreService`), all stores now use JSON serialization via zio-json codecs. However, the storage model is still a generic `KVRoot(entries: Map[String, String])` — a key-value bag where domain structure is encoded in string key prefixes (e.g., `"events:conversation:conv-123:1"`, `"snapshot:conversation:conv-123"`).

Level 2 replaces this with **per-domain typed root aggregates** — each domain gets its own NativeLocal snapshot with a strongly-typed root, eliminating string key conventions and making the store a proper aggregate.

## Goals

- **Eliminate string key prefixes**: No more `s"events:${domain}:${id}:${seq}"` conventions
- **Type-safe at the storage layer**: The root aggregate's shape reflects the domain model
- **Domain isolation**: Each domain owns its own snapshot file — no cross-domain key collisions
- **Leverage ZIO Schema**: Root types use `DeriveSchema.gen` for automatic codec derivation
- **Simplify repositories**: `*EventStoreES` and `*RepositoryES` operate on typed maps, not key-string gymnastics

## Design

### Per-Domain Root Aggregates

Each domain defines a root case class describing its persisted state:

```scala
// modules/conversation-domain/src/main/scala/conversation/entity/ConversationRoot.scala
final case class ConversationRoot(
  events: Map[String, List[ConversationEvent]] = Map.empty,    // ConversationId.value -> events
  snapshots: Map[String, Conversation] = Map.empty,            // ConversationId.value -> latest snapshot
) derives Schema

object ConversationRoot:
  val descriptor: RootDescriptor[ConversationRoot] =
    RootDescriptor.fromSchema(id = "conversation", initializer = () => ConversationRoot())
```

```scala
// modules/governance-domain/src/main/scala/governance/entity/GovernanceRoot.scala
final case class GovernanceRoot(
  policyEvents: Map[String, List[GovernancePolicyEvent]] = Map.empty,
  policies: Map[String, GovernancePolicy] = Map.empty,
) derives Schema
```

### Domain Store Service Trait

Each domain gets a typed store service that replaces the generic `DataStoreService`:

```scala
// modules/conversation-domain/src/main/scala/conversation/entity/ConversationStore.scala
trait ConversationStore:
  def appendEvent(id: ConversationId, event: ConversationEvent): IO[PersistenceError, Unit]
  def events(id: ConversationId): IO[PersistenceError, List[ConversationEvent]]
  def storeSnapshot(id: ConversationId, conversation: Conversation): IO[PersistenceError, Unit]
  def getSnapshot(id: ConversationId): IO[PersistenceError, Option[Conversation]]
  def listSnapshots: IO[PersistenceError, List[Conversation]]
```

Implementation backed by `ObjectStore[ConversationRoot]`:

```scala
final class ConversationStoreLive(store: ObjectStore[ConversationRoot]) extends ConversationStore:
  override def appendEvent(id: ConversationId, event: ConversationEvent): IO[PersistenceError, Unit] =
    store.modify { root =>
      val existing = root.events.getOrElse(id.value, Nil)
      ZIO.succeed(((), root.copy(events = root.events.updated(id.value, existing :+ event))))
    }.unit.mapError(toPersistenceError)

  override def events(id: ConversationId): IO[PersistenceError, List[ConversationEvent]] =
    store.load.map(_.events.getOrElse(id.value, Nil)).mapError(toPersistenceError)
```

### Store Module Per Domain

Each domain module provides a `ZLayer` that creates its NativeLocal store:

```scala
object ConversationStoreModule:
  val live: ZLayer[StoreConfig, EclipseStoreError, ConversationStore] =
    ZLayer.scoped {
      for
        cfg         <- ZIO.service[StoreConfig]
        snapshotPath = Paths.get(cfg.dataStorePath).resolve("conversation.snapshot.json")
        env         <- NativeLocal.live[ConversationRoot](snapshotPath, ConversationRoot.descriptor).build
        store        = env.get[ObjectStore[ConversationRoot]]
        ops          = env.get[StorageOps[ConversationRoot]]
        _           <- ops.scheduleCheckpoints(Schedule.fixed(5.seconds))
        _           <- ZIO.addFinalizer(store.checkpoint.ignoreLogged)
      yield ConversationStoreLive(store)
    }
```

### Repository Simplification

`*EventStoreES` and `*RepositoryES` become thin wrappers or are eliminated entirely, since the store service already provides domain-typed operations:

```scala
// Before (Level 1):
final case class ConversationRepositoryES(
  eventStore: EventStore[ConversationId, ConversationEvent],
  dataStore: DataStoreService,
) extends ConversationRepository:
  override def append(event: ConversationEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.conversationId, event)
      _ <- rebuildSnapshot(event.conversationId)  // fetch events, replay, store snapshot
    yield ()

// After (Level 2):
final case class ConversationRepositoryLive(
  store: ConversationStore,
) extends ConversationRepository:
  override def append(event: ConversationEvent): IO[PersistenceError, Unit] =
    for
      _ <- store.appendEvent(event.conversationId, event)
      _ <- rebuildSnapshot(event.conversationId)  // events + snapshot in same root
    yield ()
```

## Migration Strategy

### Phase A: Foundation (shared-store-core)

1. Keep `DataStoreService` + `NativeLocalKVStore` as fallback for non-migrated domains
2. Add `DomainStore[Root]` base trait in `shared-store-core` with common `modify`/`load`/`checkpoint` operations
3. Add helper to create `NativeLocal` layer from `StoreConfig` + domain name

### Phase B: Domain-by-Domain Migration (one domain per PR)

For each of the 12 ADE domains + cross-cutting stores:

1. Define `*Root` case class in the domain's entity package
2. Define `*Store` trait in the domain's entity package
3. Implement `*StoreLive` backed by `ObjectStore[*Root]`
4. Create `*StoreModule` with `ZLayer`
5. Update `*EventStoreES` → use `*Store` directly (or remove if redundant)
6. Update `*RepositoryES` → depend on `*Store` instead of `DataStoreService`
7. Update DI wiring in `ApplicationDI`
8. Update tests

**Suggested order** (simplest first, most dependencies last):
1. `governance` — small, self-contained, 2 maps
2. `decision` — small, self-contained
3. `knowledge` — small (decision logs)
4. `specification` — small
5. `plan` — small
6. `evolution` — small
7. `project` — small
8. `analysis` — small
9. `agent` — medium, shared across modules
10. `issues` — medium, used by board
11. `taskrun` — medium, cross-cutting
12. `conversation` — large, many consumers
13. `config` + `activity` — cross-cutting, migrate last

### Phase C: Cleanup

1. Remove `DataStoreService` trait (all consumers migrated)
2. Remove `NativeLocalKVStore`, `KVRoot`
3. Remove `EventStore[Id, Event]` generic trait (each domain has its own typed store)
4. Remove string key prefix conventions from CLAUDE.md

## Trade-offs

**Advantages:**
- Full type safety at every layer — no `asInstanceOf`, no string key parsing
- Each domain snapshot is independent — no cross-domain interference
- Smaller snapshot files — only the domain's data, not everything in one map
- Domain-specific operations (e.g., `listByWorkspace`) can be index methods on the root
- Easier testing — mock a focused trait, not a generic KV store

**Costs:**
- ~13 new root types, ~13 store traits, ~13 store implementations
- Each domain gets its own NativeLocal instance (separate snapshot file, separate checkpoint schedule)
- DI graph grows — more layers to wire in `ApplicationDI`
- Migration is incremental but touches every domain module

**Risks:**
- Snapshot file proliferation (13+ files vs current 2) — manageable with naming convention
- ObjectStore concurrency — each domain store is independent, so no contention between domains
- ZIO Schema derivation for complex ADTs (enums, sealed traits) — already working in Level 1

## File Count Estimate

Per domain: ~4 new/modified files (Root, Store trait, StoreLive, StoreModule)
Total: ~52 files across 13 domains + cleanup of ~30 existing EventStoreES/RepositoryES files

## Success Criteria

- All 1100+ tests pass after each domain migration
- No `asInstanceOf` or `.toString` in store implementations
- No string key prefix conventions in migrated domains
- Each domain's snapshot file is independently loadable/inspectable as JSON
