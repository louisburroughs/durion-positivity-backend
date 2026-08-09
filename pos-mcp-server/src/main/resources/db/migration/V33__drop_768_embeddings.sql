-- #1207: retire the 768-dim (nomic-embed-text) embedding columns after the #1194 bge-m3 cutover
-- was signed off live (see gate5-rag-hybrid-design.md, "Alpha cutover record — 2026-08-09").
--
-- The dual-column rollback window is closed: the 1024 column becomes THE `embedding` column, the
-- V31 view indirection (which existed only because PgVectorStore hardcodes the `embedding` column
-- name) is dropped, and the 1024 indexes take over the historical index names. Recovery from here
-- is a re-embed from `content`/`description` (embeddings are derived data), not a column flip.
--
-- Order matters: the view must go before the column it aliases; the 768 columns must go before
-- the rename frees their name.
DROP VIEW IF EXISTS mcp_document_embedding_1024;

DROP INDEX IF EXISTS mcp_doc_embedding_idx;   -- V2, 768 ivfflat
DROP INDEX IF EXISTS idx_mcp_tool_embedding;  -- V6, 768 ivfflat

ALTER TABLE mcp_document_embedding DROP COLUMN IF EXISTS embedding;
ALTER TABLE mcp_tool DROP COLUMN IF EXISTS embedding;
ALTER TABLE mcp_screen_registry DROP COLUMN IF EXISTS embedding;

ALTER TABLE mcp_document_embedding RENAME COLUMN embedding_1024 TO embedding;
ALTER TABLE mcp_tool RENAME COLUMN embedding_1024 TO embedding;
ALTER TABLE mcp_screen_registry RENAME COLUMN embedding_1024 TO embedding;

-- Keep the historical index names now that the 1024 indexes (HNSW since V32) are the only ones.
ALTER INDEX IF EXISTS mcp_doc_embedding_1024_idx RENAME TO mcp_doc_embedding_idx;
ALTER INDEX IF EXISTS idx_mcp_tool_embedding_1024 RENAME TO idx_mcp_tool_embedding;
