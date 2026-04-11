# Memory Module: Rename userId to scope

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the misleading `userId` concept from the memory module and replace it with `scope` — a generic partition key populated with workspace IDs, session keys, or logical group names like `"knowledge"`.

**Architecture:** The application has no user dimension. `userId` is currently populated with synthetic values (`"knowledge"`, `"task-run:123"`, `"mcp"`, session-derived strings). We rename `UserId` → `Scope` throughout the memory entity/repository/controller/view layers and update all 6 creation sites to use meaningful scope values. The GigaMap index is updated from `"userId"` to `"scope"`. This is a mechanical rename — no behavioral changes.

**Tech Stack:** Scala 3.5.2, ZIO 2.x, sbt multi-module, EclipseStore/GigaMap, Scalatags, HTMX

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `modules/memory-domain/src/main/scala/memory/entity/MemoryModels.scala` | Rename `UserId` → `Scope`, field `userId` → `scope` in `MemoryEntry` and `MemoryFilter` |
| Modify | `modules/memory-domain/src/main/scala/memory/entity/MemoryRepository.scala` | Rename all `userId: UserId` params → `scope: Scope` |
| Modify | `src/main/scala/memory/control/MemoryRepositoryES.scala` | Update index query from `"userId"` → `"scope"`, update all field references |
| Modify | `src/main/scala/shared/store/MemoryStoreModule.scala` | Rename GigaMap index from `"userId"` → `"scope"` |
| Modify | `src/main/scala/memory/boundary/MemoryController.scala` | Rename query param `userId` → `scope`, update DTO fields |
| Modify | `modules/shared-web/src/main/scala/shared/web/MemoryView.scala` | Rename filter label, param name, card display from userId → scope |
| Modify | `src/main/scala/knowledge/control/KnowledgeExtractionService.scala` | `knowledgeUserId` → `knowledgeScope`, `UserId("knowledge")` → `Scope("knowledge")` |
| Modify | `src/main/scala/knowledge/boundary/KnowledgeController.scala` | Same rename |
| Modify | `modules/knowledge-domain/src/main/scala/knowledge/control/KnowledgeGraphService.scala` | Same rename |
| Modify | `src/main/scala/orchestration/control/Llm4zioAdapters.scala` | Rename `userIdFromSession` → `scopeFromSession`, type aliases |
| Modify | `src/main/scala/orchestration/control/AgentDispatcher.scala` | `UserId(...)` → `Scope(...)` |
| Modify | `src/main/scala/gateway/control/GatewayService.scala` | Update calls to renamed ConversationMemory methods |
| Modify | `src/main/scala/mcp/GatewayMcpTools.scala` | `UserId("mcp")` → `Scope("mcp")` |
| Modify | `src/test/scala/memory/MemoryRepositoryESSpec.scala` | Update test data |
| Modify | `src/test/scala/web/controllers/MemoryControllerSpec.scala` | Update test data and stub signatures |
| Modify | `src/test/scala/knowledge/boundary/KnowledgeControllerSpec.scala` | Update test data and stub signatures |
| Modify | `src/test/scala/knowledge/control/KnowledgeServicesSpec.scala` | Update stub signatures |
| Modify | `src/test/scala/gateway/GatewayServiceSpec.scala` | Update stub signatures |
| Modify | `src/test/scala/mcp/GatewayMcpToolsSpec.scala` | Update stub signatures |
| Modify | `src/test/scala/web/controllers/ChatControllerGatewaySpec.scala` | Update stub signatures |
| Modify | `src/test/scala/shared/web/KnowledgeViewSpec.scala` | Update test data |

---

### Task 1: Rename `UserId` to `Scope` in memory entity models

**Files:**
- Modify: `modules/memory-domain/src/main/scala/memory/entity/MemoryModels.scala`

This is the foundation — every other file depends on these types.

- [ ] **Step 1: Rename the opaque type and all references**

Replace the `UserId` opaque type block (lines 17-24) with `Scope`:

```scala
opaque type Scope = String
object Scope:
  def apply(value: String): Scope = value

  extension (id: Scope)
    def value: String = id

  given JsonCodec[Scope] = JsonCodec.string.transform(Scope.apply, _.value)
```

In `MemoryEntry` (line 68), rename the field:
```scala
// Before
userId: UserId,
// After
scope: Scope,
```

In `MemoryFilter` (line 84), rename the field:
```scala
// Before
userId: Option[UserId] = None,
// After
scope: Option[Scope] = None,
```

- [ ] **Step 2: Verify the module compiles in isolation**

Run: `sbt --client 'memoryDomain / compile'`
Expected: success (this module has no other consumers)

- [ ] **Step 3: Commit**

```bash
git add modules/memory-domain/src/main/scala/memory/entity/MemoryModels.scala
git commit -m "refactor(memory): rename UserId opaque type to Scope in entity models"
```

---

### Task 2: Rename `userId` parameters in `MemoryRepository` trait

**Files:**
- Modify: `modules/memory-domain/src/main/scala/memory/entity/MemoryRepository.scala`

- [ ] **Step 1: Update trait and companion object**

Replace every `userId: UserId` parameter with `scope: Scope` and rename `listForUser` → `listByScope`:

Trait methods (lines 5-12):
```scala
trait MemoryRepository:
  def save(entry: MemoryEntry): IO[Throwable, Unit]
  def searchRelevant(scope: Scope, query: String, limit: Int, filter: MemoryFilter): IO[Throwable, List[ScoredMemory]]
  def listByScope(scope: Scope, filter: MemoryFilter, page: Int, pageSize: Int): IO[Throwable, List[MemoryEntry]]
  def listAll(filter: MemoryFilter, page: Int, pageSize: Int): IO[Throwable, List[MemoryEntry]] =
    ZIO.fail(new UnsupportedOperationException("listAll is not supported by this MemoryRepository implementation"))
  def deleteById(scope: Scope, id: MemoryId): IO[Throwable, Unit]
  def deleteBySession(sessionId: SessionId): IO[Throwable, Unit]
```

Companion object accessor methods (lines 14-45) — update parameter names and types to match the trait. Rename `listForUser` → `listByScope`:
```scala
object MemoryRepository:
  def save(entry: MemoryEntry): ZIO[MemoryRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[MemoryRepository](_.save(entry))

  def searchRelevant(
    scope: Scope,
    query: String,
    limit: Int,
    filter: MemoryFilter,
  ): ZIO[MemoryRepository, Throwable, List[ScoredMemory]] =
    ZIO.serviceWithZIO[MemoryRepository](_.searchRelevant(scope, query, limit, filter))

  def listByScope(
    scope: Scope,
    filter: MemoryFilter,
    page: Int,
    pageSize: Int,
  ): ZIO[MemoryRepository, Throwable, List[MemoryEntry]] =
    ZIO.serviceWithZIO[MemoryRepository](_.listByScope(scope, filter, page, pageSize))

  def listAll(
    filter: MemoryFilter,
    page: Int,
    pageSize: Int,
  ): ZIO[MemoryRepository, Throwable, List[MemoryEntry]] =
    ZIO.serviceWithZIO[MemoryRepository](_.listAll(filter, page, pageSize))

  def deleteById(scope: Scope, id: MemoryId): ZIO[MemoryRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[MemoryRepository](_.deleteById(scope, id))

  def deleteBySession(sessionId: SessionId): ZIO[MemoryRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[MemoryRepository](_.deleteBySession(sessionId))
```

Update the import in this file: the `UserId` import is implicit via wildcard `memory.entity.*` (same package) so no import change needed.

- [ ] **Step 2: Verify the module compiles**

Run: `sbt --client 'memoryDomain / compile'`
Expected: success

- [ ] **Step 3: Commit**

```bash
git add modules/memory-domain/src/main/scala/memory/entity/MemoryRepository.scala
git commit -m "refactor(memory): rename userId params to scope in MemoryRepository trait"
```

---

### Task 3: Update `MemoryRepositoryES` implementation and `MemoryStoreModule` index

**Files:**
- Modify: `src/main/scala/memory/control/MemoryRepositoryES.scala`
- Modify: `src/main/scala/shared/store/MemoryStoreModule.scala`

- [ ] **Step 1: Update the GigaMap index in MemoryStoreModule**

In `src/main/scala/shared/store/MemoryStoreModule.scala`, line 33, change the index name and accessor:

```scala
// Before
GigaMapIndex.single("userId", _.userId.value),
// After
GigaMapIndex.single("scope", _.scope.value),
```

- [ ] **Step 2: Update MemoryRepositoryES implementation**

In `src/main/scala/memory/control/MemoryRepositoryES.scala`:

**searchRelevant** (lines 36-55) — rename parameter `userId` → `scope`, update filter on line 49:
```scala
override def searchRelevant(
  scope: Scope,
  query: String,
  limit: Int,
  filter: MemoryFilter,
): IO[Throwable, List[ScoredMemory]] =
  for
    queryVec <- embedService.embed(query)
    idx      <- ensureIndex
    results  <- idx
                  .search(vectorChunk(queryVec), limit * 3, Some(0.5f))
                  .mapError(toThrowable)
    filtered  = results
                  .filter(r => r.entity.scope == scope)
                  .filter(r => filter.kind.forall(_ == r.entity.kind))
                  .filter(r => filter.sessionId.forall(_ == r.entity.sessionId))
                  .filter(r => filter.tags.forall(tag => r.entity.tags.contains(tag)))
                  .take(limit)
                  .map(r => ScoredMemory(r.entity, r.score))
  yield filtered
```

**listForUser → listByScope** (lines 57-71) — rename method and parameter:
```scala
override def listByScope(
  scope: Scope,
  filter: MemoryFilter,
  page: Int,
  pageSize: Int,
): IO[Throwable, List[MemoryEntry]] =
  queryEntriesByScope(scope)
    .map(
      _.toList
        .filter(entry => filter.sessionId.forall(_ == entry.sessionId))
        .filter(entry => filter.kind.forall(_ == entry.kind))
        .filter(entry => filter.tags.forall(tag => entry.tags.contains(tag)))
        .sortBy(_.createdAt)(Ordering[java.time.Instant].reverse)
        .slice(math.max(0, page) * math.max(1, pageSize), math.max(0, page + 1) * math.max(1, pageSize))
    )
```

**listAll** (line 83) — update the filter field reference:
```scala
// Before
.filter(entry => filter.userId.forall(_ == entry.userId))
// After
.filter(entry => filter.scope.forall(_ == entry.scope))
```

**deleteById** (lines 91-102) — rename parameter, update field references and error message:
```scala
override def deleteById(scope: Scope, id: MemoryId): IO[Throwable, Unit] =
  for
    existing <- memoryMap.get(StoreMemoryId(id.value)).mapError(toThrowable)
    _        <- existing match
                  case Some(entry) if entry.scope == scope =>
                    memoryMap.remove(StoreMemoryId(id.value)).unit.mapError(toThrowable)
                  case Some(_)                             =>
                    ZIO.fail(new RuntimeException(s"Memory $id does not belong to scope ${scope.value}"))
                  case None                                => ZIO.unit
    idx      <- ensureIndex
    _        <- idx.remove(id.value.hashCode.toLong).mapError(toThrowable).ignore
  yield ()
```

**queryEntriesForUser → queryEntriesByScope** (lines 131-139) — rename method, update index query and log message:
```scala
private def queryEntriesByScope(scope: Scope): IO[Throwable, Chunk[MemoryEntry]] =
  memoryMap
    .query(GigaMapQuery.ByIndex("scope", scope.value))
    .catchSome {
      case GigaMapError.IndexNotDefined("scope") =>
        ZIO.logWarning("memoryEntries 'scope' index missing; falling back to full scan") *>
          memoryMap.query(GigaMapQuery.All[MemoryEntry]()).map(_.filter(_.scope == scope))
    }
    .mapError(toThrowable)
```

- [ ] **Step 3: Compile**

Run: `sbt --client compile`
Expected: fails — downstream consumers still reference `UserId` and `userId`. This is expected; we fix them in the next tasks.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/memory/control/MemoryRepositoryES.scala src/main/scala/shared/store/MemoryStoreModule.scala
git commit -m "refactor(memory): update MemoryRepositoryES and GigaMap index from userId to scope"
```

---

### Task 4: Update `MemoryController` and `MemoryView`

**Files:**
- Modify: `src/main/scala/memory/boundary/MemoryController.scala`
- Modify: `modules/shared-web/src/main/scala/shared/web/MemoryView.scala`

- [ ] **Step 1: Update MemoryController**

In `src/main/scala/memory/boundary/MemoryController.scala`:

**Import:** The file uses `import memory.entity.*` which will pick up `Scope` automatically. No import change needed.

**`readUserIdOpt` → `readScopeOpt`** (line 111-112):
```scala
private def readScopeOpt(req: Request): Option[Scope] =
  req.queryParam("scope").map(_.trim).filter(_.nonEmpty).map(Scope.apply)
```

**All call sites** — replace `readUserIdOpt(req)` → `readScopeOpt(req)` at lines 32, 44, 76, 90. Replace the `userId` local variable name with `scope` throughout:

Line 30-40 (GET /memory route):
```scala
Method.GET / "memory"                           -> handler { (req: Request) =>
  execute {
    val scope  = readScopeOpt(req)
    val filter = baseFilter(req, scope)
    for
      entries <- listEntries(filter, page = 0, pageSize = defaultPageSize)
      total   <- listEntries(filter, page = 0, pageSize = 10000).map(_.size)
      oldest   = entries.lastOption.map(_.createdAt.toString)
      newest   = entries.headOption.map(_.createdAt.toString)
    yield html(MemoryView.page(scope, entries, total, oldest, newest, defaultRetentionDs))
  }
},
```

Line 42-72 (GET /api/memory/search route):
```scala
Method.GET / "api" / "memory" / "search"        -> handler { (req: Request) =>
  execute {
    val scope  = readScopeOpt(req)
    val q      = req.queryParam("q").map(_.trim).getOrElse("")
    val limit  = req.queryParam("limit").flatMap(_.toIntOption).map(v => Math.max(1, v)).getOrElse(defaultLimit)
    val filter = baseFilter(req, scope)

    val effect =
      if q.isEmpty then
        listEntries(filter, page = 0, pageSize = limit).map(_.map(entry =>
          ScoredMemory(entry, 0.0f)
        ))
      else
        scope match
          case Some(s) => repository.searchRelevant(s, q, limit, filter)
          case None    =>
            listEntries(filter, page = 0, pageSize = 10000).map { entries =>
              val needle = q.toLowerCase
              entries
                .filter(entry => entry.text.toLowerCase.contains(needle))
                .take(limit)
                .map(entry => ScoredMemory(entry, 0.0f))
            }

    effect.map { results =>
      req.queryParam("format").map(_.trim.toLowerCase) match
        case Some("html") => html(MemoryView.searchFragment(results, scope))
        case _            =>
          Response.json(SearchResponse(results.map(SearchItem.fromScored)).toJson)
    }
  }
},
```

Line 74-86 (GET /api/memory/list route):
```scala
Method.GET / "api" / "memory" / "list"          -> handler { (req: Request) =>
  execute {
    val scope    = readScopeOpt(req)
    val page     = req.queryParam("page").flatMap(_.toIntOption).map(v => Math.max(0, v)).getOrElse(0)
    val pageSize =
      req.queryParam("pageSize").flatMap(_.toIntOption).map(v => Math.max(1, v)).getOrElse(defaultPageSize)
    val filter   = baseFilter(req, scope)
    listEntries(filter, page, pageSize).map { entries =>
      req.queryParam("format").map(_.trim.toLowerCase) match
        case Some("html") => html(MemoryView.entriesFragment(entries, scope))
        case _            => Response.json(ListResponse(entries.map(ListItem.fromEntry), page, pageSize).toJson)
    }
  }
},
```

Line 88-105 (DELETE route) — rename `userId` → `scope`, update error message:
```scala
Method.DELETE / "api" / "memory" / string("id") -> handler { (id: String, req: Request) =>
  execute {
    readScopeOpt(req) match
      case Some(scope) =>
        repository.deleteById(scope, MemoryId(id)).as(
          Response(
            status = Status.NoContent,
            headers = Headers(
              Header.Custom("HX-Reswap", "delete")
            ),
          )
        )
      case None        =>
        ZIO.succeed(
          Response.json(Map("error" -> "scope query parameter is required").toJson).status(Status.BadRequest)
        )
  }
},
```

**`baseFilter`** (lines 117-122):
```scala
private def baseFilter(req: Request, scope: Option[Scope]): MemoryFilter =
  MemoryFilter(
    scope = scope,
    sessionId = readSessionIdOpt(req),
    kind = parseKind(req.queryParam("kind")),
  )
```

**`listEntries`** (lines 124-133):
```scala
private def listEntries(
  filter: MemoryFilter,
  page: Int,
  pageSize: Int,
): IO[Throwable, List[MemoryEntry]] =
  filter.scope match
    case Some(scope) =>
      repository.listByScope(scope, filter, page, pageSize)
    case None        =>
      repository.listAll(filter, page, pageSize)
```

**DTO fields** — rename `userId` → `scope` in `SearchItem` (line 151) and `ListItem` (line 183), and their `fromScored`/`fromEntry` factory methods:

```scala
final private case class SearchItem(
  id: String,
  scope: String,
  sessionId: String,
  text: String,
  tags: List[String],
  kind: String,
  createdAt: String,
  lastAccessedAt: String,
  score: Float,
) derives JsonCodec

private object SearchItem:
  def fromScored(value: ScoredMemory): SearchItem =
    SearchItem(
      id = value.entry.id.value,
      scope = value.entry.scope.value,
      sessionId = value.entry.sessionId.value,
      text = value.entry.text,
      tags = value.entry.tags,
      kind = value.entry.kind.value,
      createdAt = value.entry.createdAt.toString,
      lastAccessedAt = value.entry.lastAccessedAt.toString,
      score = value.score,
    )

final private case class ListItem(
  id: String,
  scope: String,
  sessionId: String,
  text: String,
  tags: List[String],
  kind: String,
  createdAt: String,
  lastAccessedAt: String,
) derives JsonCodec

private object ListItem:
  def fromEntry(entry: MemoryEntry): ListItem =
    ListItem(
      id = entry.id.value,
      scope = entry.scope.value,
      sessionId = entry.sessionId.value,
      text = entry.text,
      tags = entry.tags,
      kind = entry.kind.value,
      createdAt = entry.createdAt.toString,
      lastAccessedAt = entry.lastAccessedAt.toString,
    )
```

- [ ] **Step 2: Update MemoryView**

In `modules/shared-web/src/main/scala/shared/web/MemoryView.scala`:

**Import** (line 3): change `UserId` → `Scope`:
```scala
import memory.entity.{ MemoryEntry, MemoryKind, ScoredMemory, Scope }
```

**All method signatures** — rename `userId: Option[UserId]` → `scope: Option[Scope]`:
- `page(...)` line 8: param `userId` → `scope`
- `entriesFragment(...)` line 28: param `userId` → `scope`
- `searchFragment(...)` line 46: param `userId` → `scope`
- `searchPanel(...)` line 66: param `userId` → `scope`
- `memoryCard(...)` line 166-167: param `userId: UserId` → `scope: Scope`, `showUserId` → `showScope`

**Inside `page`** (lines 16-26): replace `userId` → `scope` in all references:
```scala
def page(
  scope: Option[Scope],
  entries: List[MemoryEntry],
  totalEntries: Int,
  oldest: Option[String],
  newest: Option[String],
  retentionDays: Int,
): String =
  Layout.page("Memory", "/memory")(
    div(cls := "space-y-6")(
      div(
        h1(cls := "text-2xl font-bold text-white")("Memory"),
        p(cls := "mt-2 text-sm text-gray-400")("Browse, search, and delete long-term memory entries."),
      ),
      searchPanel(scope),
      statsBar(totalEntries, oldest, newest, retentionDays),
      div(id := "memory-results", cls := "space-y-3")(raw(entriesFragment(entries, scope))),
    )
  )
```

**Inside `entriesFragment`** (line 28-44): rename param and field reference:
```scala
def entriesFragment(entries: List[MemoryEntry], scope: Option[Scope]): String =
  if entries.isEmpty then
    div(cls := "rounded-lg border border-white/10 bg-white/5 p-6 text-sm text-gray-300")("No memories found.").render
  else
    div(cls := "space-y-3")(
      entries.map(entry =>
        memoryCard(
          entry.id.value,
          entry.text,
          entry.kind.value,
          entry.createdAt.toString,
          None,
          entry.scope,
          showScope = scope.isEmpty,
        )
      ).toSeq*
    ).render
```

**Inside `searchFragment`** (lines 46-64): rename param and reference:
```scala
def searchFragment(results: List[ScoredMemory], scope: Option[Scope]): String =
  if results.isEmpty then
    div(
      cls := "rounded-lg border border-white/10 bg-white/5 p-6 text-sm text-gray-300"
    )("No matching memories.").render
  else
    div(cls := "space-y-3")(
      results.map { result =>
        memoryCard(
          result.entry.id.value,
          result.entry.text,
          result.entry.kind.value,
          result.entry.createdAt.toString,
          Some(f"${result.score}%.3f"),
          result.entry.scope,
          showScope = scope.isEmpty,
        )
      }.toSeq*
    ).render
```

**Inside `searchPanel`** (lines 66-143) — rename param, update the filter input label, name, id, and placeholder:
```scala
private def searchPanel(scope: Option[Scope]): Frag =
```

The "User" filter input (lines 85-99) — change label to "Scope", id/name to `scope`, placeholder:
```scala
div(
  label(cls := "mb-2 block text-sm font-medium text-gray-200", `for` := "memory-scope")("Scope"),
  input(
    id                 := "memory-scope",
    name               := "scope",
    cls                := "block w-full rounded-md border-0 bg-white/5 px-3 py-2 text-sm text-white ring-1 ring-inset ring-white/10 placeholder:text-gray-500 focus:ring-2 focus:ring-inset focus:ring-indigo-500",
    placeholder        := "All scopes",
    value              := scope.map(_.value).getOrElse(""),
    attr("hx-get")     := "/api/memory/search",
    attr("hx-trigger") := "input changed delay:300ms, search",
    attr("hx-target")  := "#memory-results",
    attr("hx-include") := "#memory-filters",
    attr("hx-swap")    := "innerHTML",
    attr("hx-vals")    := "{\"format\":\"html\"}",
  ),
),
```

**Inside `memoryCard`** (lines 166-191) — rename params and references:
```scala
private def memoryCard(
  memoryId: String,
  text: String,
  kind: String,
  createdAt: String,
  score: Option[String],
  scope: Scope,
  showScope: Boolean,
): Frag =
  div(scalatags.Text.all.id := s"memory-card-$memoryId", cls := "rounded-lg border border-white/10 bg-black/20 p-4")(
    div(cls := "mb-2 flex items-center justify-between gap-3")(
      div(cls := "flex items-center gap-2")(
        span(cls := "rounded-full bg-indigo-500/20 px-2 py-1 text-xs font-semibold text-indigo-300")(kind),
        score.map(value => span(cls := "text-xs text-gray-400")(s"score $value")),
        Option.when(showScope)(span(cls := "rounded-full bg-white/10 px-2 py-1 text-xs text-gray-300")(scope.value)),
      ),
      button(
        cls               := "rounded-md bg-red-600/80 px-2 py-1 text-xs font-medium text-white hover:bg-red-500",
        attr("hx-delete") := s"/api/memory/$memoryId?scope=${scope.value}",
        attr("hx-target") := s"#memory-card-$memoryId",
        attr("hx-swap")   := "outerHTML",
      )("Delete"),
    ),
    p(cls := "text-sm text-gray-200 whitespace-pre-wrap")(text),
    p(cls := "mt-2 text-xs text-gray-500")(createdAt),
  )
```

- [ ] **Step 3: Compile**

Run: `sbt --client compile`
Expected: still fails — creation-site files not yet updated.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/memory/boundary/MemoryController.scala modules/shared-web/src/main/scala/shared/web/MemoryView.scala
git commit -m "refactor(memory): rename userId to scope in MemoryController and MemoryView"
```

---

### Task 5: Update all memory entry creation sites

**Files:**
- Modify: `src/main/scala/knowledge/control/KnowledgeExtractionService.scala`
- Modify: `src/main/scala/knowledge/boundary/KnowledgeController.scala`
- Modify: `modules/knowledge-domain/src/main/scala/knowledge/control/KnowledgeGraphService.scala`
- Modify: `src/main/scala/orchestration/control/Llm4zioAdapters.scala`
- Modify: `src/main/scala/orchestration/control/AgentDispatcher.scala`
- Modify: `src/main/scala/gateway/control/GatewayService.scala`
- Modify: `src/main/scala/mcp/GatewayMcpTools.scala`

- [ ] **Step 1: Update KnowledgeExtractionService**

In `src/main/scala/knowledge/control/KnowledgeExtractionService.scala`:

Line 26 — rename constant:
```scala
// Before
private val knowledgeUserId = UserId("knowledge")
// After
private val knowledgeScope = Scope("knowledge")
```

Lines 209, 223, 237, 250, 263, 276 — in every `MemoryEntry(...)` constructor inside `persistKnowledgeMemories`, change the field:
```scala
// Before (every occurrence)
userId = knowledgeUserId,
// After (every occurrence)
scope = knowledgeScope,
```

There are 6 occurrences of `userId = knowledgeUserId,` in the `persistKnowledgeMemories` method (lines 195-285). Replace all of them.

- [ ] **Step 2: Update KnowledgeController**

In `src/main/scala/knowledge/boundary/KnowledgeController.scala`:

Line 8 — update import:
```scala
// Before
import memory.entity.{ MemoryFilter, MemoryKind, MemoryRepository, UserId }
// After
import memory.entity.{ MemoryFilter, MemoryKind, MemoryRepository, Scope }
```

Line 17 — rename constant:
```scala
// Before
private val knowledgeUserId = UserId("knowledge")
// After
private val knowledgeScope = Scope("knowledge")
```

Lines 67-68 — update the call:
```scala
// Before
.listForUser(knowledgeUserId, MemoryFilter(userId = Some(knowledgeUserId)), 0, 200)
// After
.listByScope(knowledgeScope, MemoryFilter(scope = Some(knowledgeScope)), 0, 200)
```

- [ ] **Step 3: Update KnowledgeGraphService**

In `modules/knowledge-domain/src/main/scala/knowledge/control/KnowledgeGraphService.scala`:

Line 7 — update import:
```scala
// Before
import memory.entity.{ MemoryEntry, MemoryFilter, MemoryKind, MemoryRepository, UserId }
// After
import memory.entity.{ MemoryEntry, MemoryFilter, MemoryKind, MemoryRepository, Scope }
```

Line 24 — rename constant:
```scala
// Before
private val knowledgeUserId = UserId("knowledge")
// After
private val knowledgeScope = Scope("knowledge")
```

Line 111 — update the call:
```scala
// Before
.searchRelevant(knowledgeUserId, query, limit, MemoryFilter(userId = Some(knowledgeUserId)))
// After
.searchRelevant(knowledgeScope, query, limit, MemoryFilter(scope = Some(knowledgeScope)))
```

- [ ] **Step 4: Update Llm4zioAdapters (ConversationMemory)**

In `src/main/scala/orchestration/control/Llm4zioAdapters.scala`:

The file uses type aliases on line ~30 (verify exact location). Find these imports/aliases:
```scala
type MemoryUserId    = UserId
type MemorySessionId = SessionId
```
Change to:
```scala
type MemoryScope     = Scope
type MemorySessionId = SessionId
```

Line 67 — rename method and return type:
```scala
// Before
def memoryFilter(userId: MemoryUserId): MemoryFilter =
  MemoryFilter(userId = Some(userId))
// After
def memoryFilter(scope: MemoryScope): MemoryFilter =
  MemoryFilter(scope = Some(scope))
```

Lines 70-73 — rename method:
```scala
// Before
def userIdFromSession(sessionKey: SessionKey): MemoryUserId =
  val raw = sessionKey.value.trim
  if raw.startsWith("user:") then MemoryUserId(raw.stripPrefix("user:"))
  else MemoryUserId(sessionKey.asString)
// After
def scopeFromSession(sessionKey: SessionKey): MemoryScope =
  MemoryScope(sessionKey.asString)
```

Note: the `user:` prefix stripping is removed since there are no users — the session key IS the scope.

- [ ] **Step 5: Update GatewayService**

In `src/main/scala/gateway/control/GatewayService.scala`:

Line 494 — update variable name and method call:
```scala
// Before
val userId = ConversationMemory.userIdFromSession(inbound.sessionKey)
// After
val scope = ConversationMemory.scopeFromSession(inbound.sessionKey)
```

Line 500 — update filter call:
```scala
// Before
filter = ConversationMemory.memoryFilter(userId),
// After
filter = ConversationMemory.memoryFilter(scope),
```

Lines 496-498 — update `searchRelevant` call:
```scala
// Before
userId = userId,
// After
scope = scope,
```

Lines 553-554 — update the memory entry creation in `maybeScheduleSummarization`:
```scala
// Before
userId = ConversationMemory.userIdFromSession(sessionKey),
// After
scope = ConversationMemory.scopeFromSession(sessionKey),
```

- [ ] **Step 6: Update AgentDispatcher**

In `src/main/scala/orchestration/control/AgentDispatcher.scala`:

Line 341 — update the MemoryEntry field:
```scala
// Before
userId = UserId(s"task-run:$taskRunId"),
// After
scope = Scope(s"task-run:$taskRunId"),
```

- [ ] **Step 7: Update GatewayMcpTools**

In `src/main/scala/mcp/GatewayMcpTools.scala`:

Line 222 — update the call:
```scala
// Before
.searchRelevant(UserId("mcp"), query, limit, MemoryFilter())
// After
.searchRelevant(Scope("mcp"), query, limit, MemoryFilter())
```

- [ ] **Step 8: Compile**

Run: `sbt --client compile`
Expected: success (all source files now updated). If there are errors, they'll be about test files which we fix in the next task.

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/knowledge/control/KnowledgeExtractionService.scala \
  src/main/scala/knowledge/boundary/KnowledgeController.scala \
  modules/knowledge-domain/src/main/scala/knowledge/control/KnowledgeGraphService.scala \
  src/main/scala/orchestration/control/Llm4zioAdapters.scala \
  src/main/scala/orchestration/control/AgentDispatcher.scala \
  src/main/scala/gateway/control/GatewayService.scala \
  src/main/scala/mcp/GatewayMcpTools.scala
git commit -m "refactor(memory): update all creation sites from userId to scope"
```

---

### Task 6: Update all test files

**Files:**
- Modify: `src/test/scala/memory/MemoryRepositoryESSpec.scala`
- Modify: `src/test/scala/web/controllers/MemoryControllerSpec.scala`
- Modify: `src/test/scala/knowledge/boundary/KnowledgeControllerSpec.scala`
- Modify: `src/test/scala/knowledge/control/KnowledgeServicesSpec.scala`
- Modify: `src/test/scala/gateway/GatewayServiceSpec.scala`
- Modify: `src/test/scala/mcp/GatewayMcpToolsSpec.scala`
- Modify: `src/test/scala/web/controllers/ChatControllerGatewaySpec.scala`
- Modify: `src/test/scala/shared/web/KnowledgeViewSpec.scala`

- [ ] **Step 1: Update MemoryRepositoryESSpec**

In `src/test/scala/memory/MemoryRepositoryESSpec.scala`:

Line 17 — rename constant:
```scala
// Before
private val userId: UserId       = UserId("web:42")
// After
private val scope: Scope         = Scope("web:42")
```

Lines 68, 82-83, 91-92 — replace all `userId` references with `scope`:
```scala
// In MemoryEntry constructor (line 68):
scope = scope,    // was: userId = userId,

// In searchRelevant call (line 82-83):
userId = scope,   // was: userId = userId,

// In assertions (line 91):
hits.head.entry.scope == scope,   // was: hits.head.entry.userId == userId,
```

- [ ] **Step 2: Update MemoryControllerSpec**

In `src/test/scala/web/controllers/MemoryControllerSpec.scala`:

Line 17 — rename field in seeded entry:
```scala
// Before
userId = UserId("web:default"),
// After
scope = Scope("web:default"),
```

Lines 31-32, 39-40, 54 — rename `userId` → `scope` in all stub method signatures:
```scala
override def searchRelevant(
  scope: Scope,
  query: String,
  limit: Int,
  filter: MemoryFilter,
): IO[Throwable, List[ScoredMemory]] = ...

override def listByScope(
  scope: Scope,
  filter: MemoryFilter,
  page: Int,
  pageSize: Int,
): IO[Throwable, List[MemoryEntry]] = ...

override def deleteById(scope: Scope, id: MemoryId): IO[Throwable, Unit] = ZIO.unit
```

Note: `listForUser` → `listByScope` method rename.

- [ ] **Step 3: Update KnowledgeControllerSpec**

In `src/test/scala/knowledge/boundary/KnowledgeControllerSpec.scala`:

Line 37 — rename field:
```scala
// Before
userId = UserId("knowledge"),
// After
scope = Scope("knowledge"),
```

Lines 89, 91, 93 — update stub method signatures:
```scala
override def searchRelevant(scope: Scope, query: String, limit: Int, filter: MemoryFilter) = ...
override def listByScope(scope: Scope, filter: MemoryFilter, page: Int, pageSize: Int) = ...
override def deleteById(scope: Scope, id: MemoryId): IO[Throwable, Unit] = ZIO.unit
```

Note: `listForUser` → `listByScope` method rename.

- [ ] **Step 4: Update KnowledgeServicesSpec**

In `src/test/scala/knowledge/control/KnowledgeServicesSpec.scala`:

Lines 75, 78, 80 — update stub method signatures:
```scala
override def searchRelevant(s: Scope, q: String, limit: Int, f: MemoryFilter) = ...
override def listByScope(s: Scope, f: MemoryFilter, page: Int, size: Int): IO[Throwable, List[MemoryEntry]] = ...
override def deleteById(s: Scope, id: MemoryId): IO[Throwable, Unit] = ZIO.unit
```

Note: `listForUser` → `listByScope` method rename.

- [ ] **Step 5: Update GatewayServiceSpec**

In `src/test/scala/gateway/GatewayServiceSpec.scala`:

Lines 96, 103, 109 — update stub method signatures:
```scala
override def searchRelevant(scope: Scope, ...) = ...
override def listByScope(scope: Scope, ...) = ...
override def deleteById(scope: Scope, id: MemoryId): IO[Throwable, Unit] = ZIO.unit
```

Line 150 — update seeded MemoryEntry:
```scala
// Before
userId = UserId("telegram:conversation:chat-memory-1"),
// After
scope = Scope("telegram:conversation:chat-memory-1"),
```

Lines 163, 172, 178 — update the second stub block similarly. Note: `listForUser` → `listByScope` method rename.

- [ ] **Step 6: Update GatewayMcpToolsSpec**

In `src/test/scala/mcp/GatewayMcpToolsSpec.scala`:

Lines 114, 117, 120 — update stub method signatures:
```scala
override def searchRelevant(scope: Scope, query: String, limit: Int, filter: MemoryFilter) = ...
override def listByScope(scope: Scope, filter: MemoryFilter, page: Int, pageSize: Int) = ...
override def deleteById(scope: Scope, id: memory.entity.MemoryId): IO[Throwable, Unit] = ZIO.unit
```

Note: `listForUser` → `listByScope` method rename.

- [ ] **Step 7: Update ChatControllerGatewaySpec**

In `src/test/scala/web/controllers/ChatControllerGatewaySpec.scala`:

Lines 122, 129, 135 — update stub method signatures:
```scala
override def searchRelevant(scope: Scope, ...) = ...
override def listByScope(scope: Scope, ...) = ...
override def deleteById(scope: Scope, id: MemoryId): IO[Throwable, Unit] = ZIO.unit
```

Note: `listForUser` → `listByScope` method rename.

- [ ] **Step 8: Update KnowledgeViewSpec**

In `src/test/scala/shared/web/KnowledgeViewSpec.scala`:

Line 9 — update import:
```scala
// Before
import memory.entity.{ MemoryEntry, MemoryId, MemoryKind, SessionId, UserId }
// After
import memory.entity.{ MemoryEntry, MemoryId, MemoryKind, Scope, SessionId }
```

Line 43 — rename field in test data:
```scala
// Before
userId = UserId("knowledge"),
// After
scope = Scope("knowledge"),
```

- [ ] **Step 9: Compile and run all tests**

```bash
sbt --client compile
sbt --client test
```

Expected: all compile, all tests pass.

- [ ] **Step 10: Commit**

```bash
git add src/test/scala/memory/MemoryRepositoryESSpec.scala \
  src/test/scala/web/controllers/MemoryControllerSpec.scala \
  src/test/scala/knowledge/boundary/KnowledgeControllerSpec.scala \
  src/test/scala/knowledge/control/KnowledgeServicesSpec.scala \
  src/test/scala/gateway/GatewayServiceSpec.scala \
  src/test/scala/mcp/GatewayMcpToolsSpec.scala \
  src/test/scala/web/controllers/ChatControllerGatewaySpec.scala \
  src/test/scala/shared/web/KnowledgeViewSpec.scala
git commit -m "refactor(memory): update all test files from userId to scope"
```

---

## Verification

After all tasks:

1. `sbt compile` passes with zero errors
2. `sbt test` passes — all 1121+ tests green
3. Start the gateway (`sbt run`), navigate to `/memory` — the filter label says "Scope" instead of "User"
4. The warning `memoryEntries 'userId' index missing` no longer appears (replaced with `scope` index)
5. Search and delete operations work with `?scope=...` query parameter
