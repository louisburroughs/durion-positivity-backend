-- #784: lexical (full-text/BM25-style) retrieval path for hybrid RAG.
--
-- Adds a STORED generated tsvector over `content` so the lexical vector is maintained
-- automatically on every insert/update — documents are written by Spring AI
-- PgVectorStore.add(), outside application control, so a generated column avoids any
-- app-side sync. A GIN index backs websearch_to_tsquery / ts_rank_cd lookups issued by
-- LexicalDocumentRetriever.
--
-- Postgres-only: the H2 dev/test schema (db/h2-migration) has no FTS, and the lexical
-- retriever is alpha/prod profile-gated, so tests never execute this SQL. Requires
-- Postgres 12+ for GENERATED ... STORED (alpha is PG16). Existing rows are computed at
-- ADD COLUMN time, so no backfill step is needed.
ALTER TABLE mcp_document_embedding
    ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED;

CREATE INDEX IF NOT EXISTS mcp_doc_content_tsv_idx
    ON mcp_document_embedding USING gin (content_tsv);
