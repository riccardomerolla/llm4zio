CREATE TABLE IF NOT EXISTS custom_agents (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  description TEXT,
  system_prompt TEXT NOT NULL,
  tags TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_custom_agents_name ON custom_agents(name);
CREATE INDEX IF NOT EXISTS idx_custom_agents_enabled ON custom_agents(enabled);
