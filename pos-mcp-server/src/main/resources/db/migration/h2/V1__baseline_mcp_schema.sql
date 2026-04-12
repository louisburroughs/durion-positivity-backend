CREATE TABLE IF NOT EXISTS nlti_session (
  id UUID PRIMARY KEY,
  subject_id VARCHAR(255) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS nlti_request (
  id UUID PRIMARY KEY,
  correlation_id UUID NOT NULL,
  session_id UUID NOT NULL,
  status VARCHAR(255) NOT NULL,
  prompt_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_nlti_request_session_id ON nlti_request (session_id);
CREATE TABLE IF NOT EXISTS nlti_intent (
  id UUID PRIMARY KEY,
  request_id UUID,
  session_id UUID NOT NULL,
  correlation_id UUID NOT NULL,
  intent_type VARCHAR(255),
  status VARCHAR(255),
  risk_level VARCHAR(255),
  slots_json TEXT,
  clarification_questions_json TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_nlti_intent_session_id ON nlti_intent (session_id);
CREATE INDEX IF NOT EXISTS idx_nlti_intent_request_id ON nlti_intent (request_id);
CREATE TABLE IF NOT EXISTS nlti_audit_event (
  id UUID PRIMARY KEY,
  correlation_id UUID NOT NULL,
  session_id UUID,
  request_id UUID,
  event_type VARCHAR(255) NOT NULL,
  timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  actor_subject_id VARCHAR(255),
  payload_ref VARCHAR(255),
  payload_hash VARCHAR(255),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_nlti_audit_event_correlation_id ON nlti_audit_event (correlation_id);
CREATE TABLE IF NOT EXISTS system_prompt (
  id UUID PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_system_prompt_name UNIQUE (name)
);
CREATE TABLE IF NOT EXISTS llm_api_config (
  id UUID PRIMARY KEY,
  api_id VARCHAR(120) NOT NULL,
  model VARCHAR(120) NOT NULL,
  base_url VARCHAR(255) NOT NULL,
  api_key VARCHAR(255) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_llm_api_config_api_id UNIQUE (api_id)
);
CREATE TABLE IF NOT EXISTS llm_api_config_headers (
  llm_api_config_id UUID NOT NULL,
  headers_key VARCHAR(255) NOT NULL,
  header_value VARCHAR(255),
  CONSTRAINT fk_llm_api_config_headers_config FOREIGN KEY (llm_api_config_id) REFERENCES llm_api_config(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_llm_api_config_headers_config_id ON llm_api_config_headers (llm_api_config_id);