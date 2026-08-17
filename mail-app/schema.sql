-- springcat-mail D1 schema
CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY,
  folder TEXT NOT NULL DEFAULT 'inbox', -- inbox | spam | sent | trash | draft
  thread_id TEXT,
  from_addr TEXT NOT NULL,
  from_name TEXT,
  to_addr TEXT NOT NULL,
  reply_to TEXT,
  subject TEXT NOT NULL DEFAULT '(件名なし)',
  body_text TEXT,
  body_html TEXT,
  snippet TEXT,
  is_read INTEGER NOT NULL DEFAULT 0,
  is_starred INTEGER NOT NULL DEFAULT 0,
  is_important INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_messages_folder_date
  ON messages (folder, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_messages_thread
  ON messages (thread_id);
