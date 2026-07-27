package com.positivity.mcp.internal.orchestration.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.RagScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #784: lexical (Postgres full-text) retrieval over the RAG corpus, scope-filtered exactly like the
 * dense path. Ranks candidates by {@code ts_rank_cd(websearch_to_tsquery(...))} against the
 * {@code content_tsv} generated column (migration V28), returning Spring AI {@link Document}s that
 * carry the DB {@code id} so hybrid fusion can dedup dense + lexical hits by id.
 *
 * <p>Package-private; built by {@link ScopedContentRetrieverFactory}, which is {@code @Profile("alpha")},
 * so this is alpha-only today (the H2 test schema has no FTS, and it never runs in tests).
 */
final class LexicalDocumentRetriever implements QueryDocumentRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalDocumentRetriever.class);
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};

    // websearch_to_tsquery parses free text and quoted phrases safely (input is data, never SQL) and
    // yields an empty query — zero rows, not an error — on unparseable input. The query term binds
    // twice: once for the @@ match, once for ts_rank_cd ordering.
    private static final String SQL = """
            SELECT id, content, metadata::text AS metadata
            FROM mcp_document_embedding
            WHERE content_tsv @@ websearch_to_tsquery('english', ?)
              AND metadata ->> 'rag_scope' = ?
            ORDER BY ts_rank_cd(content_tsv, websearch_to_tsquery('english', ?)) DESC, id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String ragScope;
    private final int maxResults;

    LexicalDocumentRetriever(
            @NonNull JdbcTemplate jdbcTemplate,
            @NonNull ObjectMapper objectMapper,
            @Nullable String ragScope,
            int maxResults) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ragScope = RagScope.normalize(ragScope);
        this.maxResults = Math.max(1, maxResults);
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull String queryText) {
        if (queryText.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(SQL, this::mapRow, queryText, ragScope, queryText, maxResults);
    }

    private @NonNull Document mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        String content = rs.getString("content");
        Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
        return new Document(id, content == null ? "" : content, metadata);
    }

    private @NonNull Map<String, Object> parseMetadata(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return minimalMetadata();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, METADATA_TYPE);
            // Spring AI Document rejects null metadata values; strip them and guarantee rag_scope.
            Map<String, Object> cleaned = new LinkedHashMap<>();
            parsed.forEach((key, value) -> {
                if (value != null) {
                    cleaned.put(key, value);
                }
            });
            cleaned.putIfAbsent("rag_scope", ragScope);
            return cleaned;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            LOGGER.debug("Failed to parse lexical document metadata; using minimal scope metadata: {}", e.getMessage());
            return minimalMetadata();
        }
    }

    private @NonNull Map<String, Object> minimalMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rag_scope", ragScope);
        return metadata;
    }
}
