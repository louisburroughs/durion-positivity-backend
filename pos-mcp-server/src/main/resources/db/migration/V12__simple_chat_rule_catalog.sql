CREATE TABLE IF NOT EXISTS mcp_simple_chat_rule (
  id UUID PRIMARY KEY,
  rule_type VARCHAR(64) NOT NULL,
  phrase VARCHAR(255) NOT NULL,
  language_code VARCHAR(16) NOT NULL,
  priority INTEGER NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_mcp_simple_chat_rule_type_phrase UNIQUE (rule_type, phrase)
);

CREATE INDEX IF NOT EXISTS idx_mcp_simple_chat_rule_enabled_priority
  ON mcp_simple_chat_rule (enabled, priority);
