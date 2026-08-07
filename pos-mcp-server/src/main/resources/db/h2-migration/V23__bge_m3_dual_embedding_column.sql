-- V23 H2 variant of pg V31 (Gate 5 G5.4, #1194): intentionally a no-op.
-- H2 has no pgvector, so the vector(768) columns, ivfflat indexes, and the mcp_document_embedding
-- table itself only exist on PostgreSQL (the H2 V6 variant likewise skipped the vector index).
-- There is nothing to mirror for the embedding_1024 dual-column here; dev/test profiles keep
-- mcp.rag.embedding-column=embedding and never execute vector SQL against H2.
SELECT 1;
