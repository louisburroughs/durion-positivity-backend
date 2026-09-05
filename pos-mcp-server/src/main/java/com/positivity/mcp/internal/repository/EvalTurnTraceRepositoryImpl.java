package com.positivity.mcp.internal.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
            """, trace.turnId(), at(trace.startedAt()), at(trace.expiresAt()), serialize(trace));
    }

    @Override
    public void deleteExpired(@NonNull Instant expiresAtOrBefore) {
        jdbcTemplate.update("DELETE FROM mcp_eval_turn_trace WHERE expires_at <= ?", at(expiresAtOrBefore));
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
                """, (rs, rowNum) -> deserialize(rs.getString("trace_payload")), at(since), Math.max(1, limit));
    }

    private @NonNull EvalTurnTrace deserialize(@NonNull String payload) {
        try {
            return objectMapper.readValue(payload, EvalTurnTrace.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to read alpha evaluation turn trace", exception);
        }
    }

    /**
     * The pg JDBC driver cannot infer a SQL type for {@link Instant} — it fails with
     * "Can't infer the SQL type to use for an instance of java.time.Instant", translated by Spring
     * into a {@code BadSqlGrammarException} that names the statement and not the parameter.
     *
     * <p>Every write here failed that way from #1682 until this fix. The failures were swallowed by
     * {@code ToolInvocationRecorder}'s catch, so {@code mcp_eval_turn_trace} stayed empty on alpha
     * while the feature reported itself enabled, and the retention job logged an hourly error
     * nobody connected to it.
     *
     * <p>{@link OffsetDateTime} at UTC is what the driver binds to {@code timestamptz}. The column
     * is {@code timestamp with time zone}, so the instant survives the round trip unchanged.
     */
    private static @NonNull OffsetDateTime at(@NonNull Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private @NonNull String serialize(EvalTurnTrace trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize alpha evaluation turn trace", exception);
        }
    }
}
