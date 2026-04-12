-- pgvector: PostgreSQL only
-- Enable pgvector extension (requires superuser or pre-installed extension)
CREATE EXTENSION IF NOT EXISTS vector;
-- RAG document embedding store
CREATE TABLE mcp_document_embedding (
  embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  embedding VECTOR(768),
  text TEXT NOT NULL,
  metadata JSONB DEFAULT '{}',
  created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX mcp_doc_embedding_idx ON mcp_document_embedding USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);