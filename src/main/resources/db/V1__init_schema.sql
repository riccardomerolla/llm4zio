PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS migration_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source_dir TEXT NOT NULL,
  output_dir TEXT NOT NULL,
  status TEXT NOT NULL,
  started_at TEXT NOT NULL,
  completed_at TEXT,
  total_files INTEGER NOT NULL DEFAULT 0,
  processed_files INTEGER NOT NULL DEFAULT 0,
  successful_conversions INTEGER NOT NULL DEFAULT 0,
  failed_conversions INTEGER NOT NULL DEFAULT 0,
  current_phase TEXT,
  error_message TEXT
);

CREATE TABLE IF NOT EXISTS cobol_files (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER NOT NULL,
  path TEXT NOT NULL,
  name TEXT NOT NULL,
  file_type TEXT NOT NULL,
  size INTEGER NOT NULL,
  line_count INTEGER NOT NULL,
  encoding TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY (run_id) REFERENCES migration_runs(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS cobol_analyses (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER NOT NULL,
  file_id INTEGER NOT NULL,
  analysis_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY (run_id) REFERENCES migration_runs(id) ON DELETE CASCADE,
  FOREIGN KEY (file_id) REFERENCES cobol_files(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS dependencies (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER NOT NULL,
  source_node TEXT NOT NULL,
  target_node TEXT NOT NULL,
  edge_type TEXT NOT NULL,
  FOREIGN KEY (run_id) REFERENCES migration_runs(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS phase_progress (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER NOT NULL,
  phase TEXT NOT NULL,
  status TEXT NOT NULL,
  item_total INTEGER NOT NULL DEFAULT 0,
  item_processed INTEGER NOT NULL DEFAULT 0,
  error_count INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (run_id) REFERENCES migration_runs(id) ON DELETE CASCADE,
  UNIQUE (run_id, phase)
);

CREATE INDEX IF NOT EXISTS idx_cobol_files_run_id ON cobol_files(run_id);
CREATE INDEX IF NOT EXISTS idx_cobol_analyses_run_id ON cobol_analyses(run_id);
CREATE INDEX IF NOT EXISTS idx_dependencies_run_id ON dependencies(run_id);
CREATE INDEX IF NOT EXISTS idx_phase_progress_run_id ON phase_progress(run_id);
