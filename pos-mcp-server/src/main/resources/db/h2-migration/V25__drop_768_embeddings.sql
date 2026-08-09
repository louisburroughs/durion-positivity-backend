-- H2 mirror of postgres V33 (#1207: drop the 768-dim embedding columns, rename embedding_1024 to
-- embedding). Deliberate no-op: H2 has no pgvector, no vector columns, and no ANN indexes (see
-- V23/V24). Present so the H2 and postgres version streams stay aligned.
SELECT 1;
