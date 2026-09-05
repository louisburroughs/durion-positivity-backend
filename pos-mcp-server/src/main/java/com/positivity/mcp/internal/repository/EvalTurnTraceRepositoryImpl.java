package com.positivity.mcp.internal.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("alpha")
@ConditionalOnProperty(name = "mcp.eval.turn-trace.enabled", havingValue = "true")
public class EvalTurnTraceRepositoryImpl implements EvalTurnTraceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EvalTurnTraceRepositoryImpl(@NonNull JdbcTemplate jdbcTemplate, @NonNull ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(@NonNull EvalTurnTrace trace) {
        jdbcTemplate.update("""
            INSERT INTO mcp_eval_turn_trace (turn_id, created_at, expires_at, trace_payload)
            VALUES (?, ?, ?, CAST(? AS jsonb))
            """, trace.turnId(), trace.startedAt(), trace.expiresAt(), serialize(trace));
    }

    @Override
    public void deleteExpired(@NonNull Instant expiresAtOrBefore) {
        jdbcTemplate.update("DELETE FROM mcp_eval_turn_trace WHERE expires_at <= ?", expiresAtOrBefore);
    }

    @Override
    public @NonNull List<EvalTurnTrace> findRecorded(@NonNull Instant since, int limit) {
        // Ordered newest-first and capped, because the caller is reading back the turns of a run
        // that just happened, not browsing history. Bounded by the retention window either way.
        return jdbcTemplate.query(
                """
                SELECT trace_payload FROM mcp_eval_turn_trace
                WHERE created_at >= ?
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> deserialize(rs.getString("trace_payload")), since, Math.max(1, limit));
    }

    private @NonNull EvalTurnTrace deserialize(@NonNull String payload) {
        try {
            return objectMapper.readValue(payload, EvalTurnTrace.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to read alpha evaluation turn trace", exception);
        }
    }

    private @NonNull String serialize(EvalTurnTrace trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize alpha evaluation turn trace", exception);
        }
    }
}
