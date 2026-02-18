-- Chat and Issues Management Schema

CREATE TABLE IF NOT EXISTS chat_conversations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER,
  title TEXT NOT NULL,
  description TEXT,
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  created_by TEXT,
  FOREIGN KEY (run_id) REFERENCES task_runs(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS chat_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL,
  sender TEXT NOT NULL,
  sender_type TEXT NOT NULL CHECK (sender_type IN ('user', 'assistant', 'system')),
  content TEXT NOT NULL,
  message_type TEXT NOT NULL DEFAULT 'text' CHECK (message_type IN ('text', 'code', 'error', 'status')),
  metadata TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS agent_issues (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER,
  conversation_id INTEGER,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  issue_type TEXT NOT NULL,
  tags TEXT,
  preferred_agent TEXT,
  context_path TEXT,
  source_folder TEXT,
  priority TEXT NOT NULL DEFAULT 'medium' CHECK (priority IN ('low', 'medium', 'high', 'critical')),
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'assigned', 'in_progress', 'completed', 'failed', 'skipped')),
  assigned_agent TEXT,
  assigned_at TEXT,
  completed_at TEXT,
  error_message TEXT,
  result_data TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (run_id) REFERENCES task_runs(id) ON DELETE CASCADE,
  FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS agent_assignments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  issue_id INTEGER NOT NULL,
  agent_name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'processing', 'completed', 'failed')),
  assigned_at TEXT NOT NULL,
  started_at TEXT,
  completed_at TEXT,
  execution_log TEXT,
  result TEXT,
  FOREIGN KEY (issue_id) REFERENCES agent_issues(id) ON DELETE CASCADE
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_chat_conversations_run_id ON chat_conversations(run_id);
CREATE INDEX IF NOT EXISTS idx_chat_conversations_status ON chat_conversations(status);
CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation_id ON chat_messages(conversation_id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_created_at ON chat_messages(created_at);
CREATE INDEX IF NOT EXISTS idx_agent_issues_run_id ON agent_issues(run_id);
CREATE INDEX IF NOT EXISTS idx_agent_issues_status ON agent_issues(status);
CREATE INDEX IF NOT EXISTS idx_agent_issues_assigned_agent ON agent_issues(assigned_agent);
CREATE INDEX IF NOT EXISTS idx_agent_issues_conversation_id ON agent_issues(conversation_id);
CREATE INDEX IF NOT EXISTS idx_agent_assignments_issue_id ON agent_assignments(issue_id);
CREATE INDEX IF NOT EXISTS idx_agent_assignments_status ON agent_assignments(status);
