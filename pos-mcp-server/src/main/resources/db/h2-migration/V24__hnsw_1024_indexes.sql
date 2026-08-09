-- H2 mirror of postgres V32 (#1194: 1024-dim ivfflat -> HNSW). Deliberate no-op: H2 has no
-- pgvector extension, no vector columns, and no ANN indexes (see V23). Present so the H2 and
-- postgres version streams stay aligned.
SELECT 1;
