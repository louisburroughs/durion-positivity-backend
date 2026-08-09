-- #1194: replace the 1024-dim ivfflat indexes with HNSW.
--
-- V31 created the ivfflat indexes while embedding_1024 was still empty. An ivfflat index trains
-- its centroids from the rows present at build time: built empty, its lists are degenerate and
-- ANN scans return almost nothing once data arrives. Even a REINDEX after population leaves
-- lists=100 spreading the ~163-chunk document corpus at one-to-two rows per list, of which the
-- default ivfflat.probes=1 scans exactly one — discovered live during the 2026-08-09 alpha
-- cutover, where document dense retrieval through the 1024 column returned a single candidate.
--
-- HNSW (pgvector >= 0.5; alpha runs 0.7.2) has no training pass, no probes tuning, tolerates
-- incremental inserts from a cold start, and gives near-exact recall at this corpus scale. It is
-- also the planned successor per gate5-rag-hybrid-design.md ("ivfflat now, HNSW when the
-- tool/doc count grows"). The 768 ivfflat indexes are left untouched: they are on the rollback
-- path only, and their empty-built degenerate layout (everything in effectively one list) is
-- what made the 768 path exhaustive-and-correct in practice.
DROP INDEX IF EXISTS mcp_doc_embedding_1024_idx;
DROP INDEX IF EXISTS idx_mcp_tool_embedding_1024;
CREATE INDEX mcp_doc_embedding_1024_idx
    ON mcp_document_embedding USING hnsw (embedding_1024 vector_cosine_ops);
CREATE INDEX idx_mcp_tool_embedding_1024
    ON mcp_tool USING hnsw (embedding_1024 vector_cosine_ops);
