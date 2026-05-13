ALTER TABLE mcp_rag_preload_record
    ADD COLUMN IF NOT EXISTS rag_scope VARCHAR(80) NOT NULL DEFAULT 'master';

UPDATE mcp_rag_preload_record
SET rag_scope = 'master'
WHERE rag_scope IS NULL;

ALTER TABLE mcp_rag_preload_record
    ALTER COLUMN rag_scope DROP DEFAULT;

DROP INDEX IF EXISTS idx_rag_preload_document_id;

CREATE INDEX IF NOT EXISTS idx_rag_preload_document_id
    ON mcp_rag_preload_record (document_id, rag_scope, status, loaded_at DESC);
