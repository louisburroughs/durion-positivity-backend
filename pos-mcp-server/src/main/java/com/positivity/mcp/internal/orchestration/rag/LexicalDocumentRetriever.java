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

    // #1124 item 4 (residual): websearch_to_tsquery parses free text and quoted phrases safely (input
    // is data, never SQL), but it joins the terms with AND. For a natural-language question that merely
    // CONTAINS an identifier ("what is the exact format of a VIN and which characters are not allowed"),
    // AND requires one chunk to hold every lexeme (exact & format & vin & …) and matches nothing — on
    // alpha the bare token `VIN` matched 9 chunks while the full question matched 0. We therefore switch
    // the parsed query to OR by swapping its `&` operators for `|` in the tsquery's text form. The raw
    // user text still passes ONLY through websearch_to_tsquery (safe parsing); the ::text/replace/
    // ::tsquery runs on the already-parsed query, so there is no injection surface. Phrase (<->) and
    // negation (!) operators are left intact, a bare token (no `&`) is unaffected, and an empty parse
    // stays empty (zero rows). ts_rank_cd still ranks the OR matches so the best chunk sorts first.
    // The tsquery term binds twice: once for the @@ match, once for ts_rank_cd ordering.
    private static final String OR_TSQUERY = "replace(websearch_to_tsquery('english', ?)::text, ' & ', ' | ')::tsquery";

    private static final String SQL_SCOPED = "SELECT id, content, metadata::text AS metadata\n"
            + "FROM mcp_document_embedding\n"
            + "WHERE content_tsv @@ " + OR_TSQUERY + "\n"
            + "  AND metadata ->> 'rag_scope' = ?\n"
            + "ORDER BY ts_rank_cd(content_tsv, " + OR_TSQUERY + ") DESC, id\n"
            + "LIMIT ?";

    // #1124 item 4: the `master` scope is the catch-all agent, not a literal doc scope, so it must
    // search all scopes (mirrors the dense path in ScopedContentRetrieverFactory#create). Permission
    // gating is applied downstream by PermissionAwareMetadataFilter, not here.
    private static final String SQL_ALL_SCOPES = "SELECT id, content, metadata::text AS metadata\n"
            + "FROM mcp_document_embedding\n"
            + "WHERE content_tsv @@ " + OR_TSQUERY + "\n"
            + "ORDER BY ts_rank_cd(content_tsv, " + OR_TSQUERY + ") DESC, id\n"
            + "LIMIT ?";

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
        if (RagScope.MASTER.equals(ragScope)) {
            return jdbcTemplate.query(SQL_ALL_SCOPES, this::mapRow, queryText, queryText, maxResults);
        }
        return jdbcTemplate.query(SQL_SCOPED, this::mapRow, queryText, ragScope, queryText, maxResults);
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
