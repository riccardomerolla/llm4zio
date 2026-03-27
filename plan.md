## Issue 627 Plan

### Group 1: Move legacy type definitions into domain packages
- [x] Identify the concrete types currently defined in `db/LegacyTypes.scala` and `shared/store/LegacyRows.scala` and map each one to its owning domain package.
- [x] Add domain-owned model files for task-run rows/status, config rows, conversation store rows, issue store rows, and any remaining shared UI enums still defined in legacy files.
- [x] Add the minimal compatibility aliases needed so existing callers keep compiling while the legacy buckets are removed.
- [x] Review the new type locations for domain ownership, serialization support, and storage compatibility before proceeding.

### Group 2: Remove legacy files and align callers
- [x] Update core services and controllers that still reference the legacy files directly so they resolve the moved domain types cleanly.
- [x] Delete `src/main/scala/db/LegacyTypes.scala`.
- [x] Delete `src/main/scala/shared/store/LegacyRows.scala`.
- [x] Review the migration for missed imports, dead aliases, and any lingering references to the deleted files before verification.

### Group 3: Verify and finalize
- [x] Run `sbt --client scalafmtAll`.
- [x] Run `sbt --client compile` and `sbt --client Test/compile`, fixing any migration regressions.
- [x] Run `sbt --client test`. The migration was also spot-checked earlier with `sbt --client "testOnly db.TaskRepositoryESSpec db.ChatRepositoryESSpec shared.store.DataStoreModuleSpec shared.store.StoreIsolationSpec"` while restarting onto a fresh sbt server.
- [x] Mark the completed tasks in this plan.
