CREATE TABLE IF NOT EXISTS mcp_document_ingestion_job (
  id UUID PRIMARY KEY,
  document_id VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  metadata_json TEXT NOT NULL,
  chunk_count INTEGER,
  error_message TEXT,
  started_at TIMESTAMP WITH TIME ZONE,
  completed_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mcp_document_ingestion_job_status
  ON mcp_document_ingestion_job (status);

CREATE INDEX IF NOT EXISTS idx_mcp_document_ingestion_job_document_id
  ON mcp_document_ingestion_job (document_id);

CREATE INDEX IF NOT EXISTS idx_mcp_document_ingestion_job_created_at
  ON mcp_document_ingestion_job (created_at);
