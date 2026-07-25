-- Align mcp_document_embedding with Spring AI PgVectorStore's fixed schema.
-- PgVectorStore hardcodes the column names id / content / metadata / embedding
-- (see PgVectorStore.DocumentRowMapper and its CREATE TABLE / INSERT templates),
-- but V2 created the table with embedding_id / text. That mismatch made
-- similaritySearch fail with "The column name id was not found in this ResultSet"
-- (RAG silently degraded to empty context via ResilientContentRetriever) and would
-- likewise have broken PgVectorStore.add() inserts. Rename the two columns to match;
-- metadata (jsonb) and embedding (vector) already carry the expected names.
ALTER TABLE mcp_document_embedding RENAME COLUMN embedding_id TO id;
ALTER TABLE mcp_document_embedding RENAME COLUMN text TO content;
