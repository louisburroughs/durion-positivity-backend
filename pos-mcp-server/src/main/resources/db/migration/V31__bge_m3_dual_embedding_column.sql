-- Gate 5 (G5.4, #1194): reversible dual-column path for the bge-m3 768 -> 1024 embedding migration.
--
-- Adds a nullable embedding_1024 vector(1024) column next to each existing embedding vector(768)
-- column. Nothing reads or writes the new columns until the operator sets
-- mcp.rag.embedding-column=embedding_1024 (which also requires mcp.rag.dimension=1024 — enforced
-- at startup by RagEmbeddingSettings). The default configuration keeps using the 768 columns, so
-- the unpopulated 1024 columns cannot be read by accident.
--
-- Rollback: DROP VIEW mcp_document_embedding_1024; DROP INDEX mcp_doc_embedding_1024_idx;
-- DROP INDEX idx_mcp_tool_embedding_1024; then drop the three embedding_1024 columns.
-- No existing data is touched in either direction.

ALTER TABLE mcp_document_embedding ADD COLUMN IF NOT EXISTS embedding_1024 vector(1024);
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS embedding_1024 vector(1024);
ALTER TABLE mcp_screen_registry ADD COLUMN IF NOT EXISTS embedding_1024 vector(1024);

-- Same index shape as the 768 columns (V2 for documents, V6 for tools; mcp_screen_registry has no
-- vector index today, so none is added here either). Plain CREATE INDEX matches the repo's Flyway
-- pattern — migrations run inside a transaction, so CONCURRENTLY is not available. Both tables are
-- small enough (thousands of rows) that the lock window is negligible; ivfflat now, HNSW can be a
-- follow-up once the corpus grows (see docs/gate5-rag-hybrid-design.md).
CREATE INDEX IF NOT EXISTS mcp_doc_embedding_1024_idx
    ON mcp_document_embedding USING ivfflat (embedding_1024 vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_mcp_tool_embedding_1024
    ON mcp_tool USING ivfflat (embedding_1024 vector_cosine_ops) WITH (lists = 100);

-- Spring AI PgVectorStore hardcodes the column names id / content / metadata / embedding (see V24).
-- This auto-updatable view aliases embedding_1024 as embedding so the store can both read (<=>)
-- and write (INSERT ... ON CONFLICT (id) DO UPDATE — supported through auto-updatable views on
-- PostgreSQL 16, verified) the 1024 column when mcp.rag.embedding-column=embedding_1024 points the
-- store at this view instead of the base table.
CREATE OR REPLACE VIEW mcp_document_embedding_1024 AS
SELECT id, content, metadata, embedding_1024 AS embedding, created_at
FROM mcp_document_embedding;
