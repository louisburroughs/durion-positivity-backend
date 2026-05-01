CREATE TABLE IF NOT EXISTS mcp_rag_preload_record (
  id UUID NOT NULL,
  document_id VARCHAR(120) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  source_path VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL,
  loaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_rag_preload_record PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_rag_preload_document_id
  ON mcp_rag_preload_record (document_id, loaded_at DESC);