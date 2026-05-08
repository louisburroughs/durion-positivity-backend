UPDATE mcp_document_embedding
SET metadata = jsonb_set(
        COALESCE(metadata, '{}'::jsonb),
        '{rag_scope}',
        to_jsonb('master'::text),
        true)
WHERE metadata IS NULL
   OR NOT (metadata ? 'rag_scope')
   OR btrim(COALESCE(metadata ->> 'rag_scope', '')) = '';
