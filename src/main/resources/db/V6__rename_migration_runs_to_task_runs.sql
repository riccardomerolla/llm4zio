-- Renames legacy table to task-centric terminology.
-- Safe for fresh databases because the statement is ignored when table is absent.
ALTER TABLE migration_runs RENAME TO task_runs;
