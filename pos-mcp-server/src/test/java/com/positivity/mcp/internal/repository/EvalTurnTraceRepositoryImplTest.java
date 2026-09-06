package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class EvalTurnTraceRepositoryImplTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final EvalTurnTraceRepositoryImpl repository = new EvalTurnTraceRepositoryImpl(jdbcTemplate, objectMapper);

    @Test
    void saveInsertsJsonPayloadWithTraceIdentityAndExpiry() throws Exception {
        EvalTurnTrace trace = trace();
        when(objectMapper.writeValueAsString(trace)).thenReturn("{\"turnId\":\"trace\"}");

        repository.save(trace);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate)
                .update(
                        sql.capture(),
                        any(UUID.class),
                        any(OffsetDateTime.class),
                        any(OffsetDateTime.class),
                        anyString());
        assertThat(sql.getValue()).contains("INSERT INTO mcp_eval_turn_trace").contains("CAST(? AS jsonb)");
    }

    @Test
    void deleteExpiredUsesInclusiveExpiryBoundary() {
        Instant boundary = Instant.parse("2026-09-04T20:00:00Z");

        repository.deleteExpired(boundary);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(OffsetDateTime.class));
        assertThat(sql.getValue()).contains("expires_at <= ?");
    }

    private static EvalTurnTrace trace() {
        Instant started = Instant.parse("2026-09-03T20:00:00Z");
        return new EvalTurnTrace(
                UUID.fromString("0199b1be-7080-7000-8000-000000000001"),
                started,
                started.plusSeconds(5),
                started.plusSeconds(86400),
                UUID.fromString("01960010-0000-7000-8000-000000000002"),
                "diana.rowe",
                "LOCATION_MANAGER",
                "question",
                false,
                "ANALYTICS",
                "T2_COMPLEX",
                "IDLE",
                List.of("AccountingFacadeTool"),
                "prompt",
                List.of(),
                List.of(),
                "answer",
                null,
                "sha-test0000",
                "CONTENT");
    }

    @Test
    void findRecordedFiltersFromTheBoundaryNewestFirstAndCapsTheRead() {
        Instant since = Instant.parse("2026-09-04T00:00:00Z");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        repository.findRecorded(since, 50);

        verify(jdbcTemplate)
                .query(
                        sql.capture(),
                        ArgumentMatchers.<RowMapper<EvalTurnTrace>>any(),
                        eq(since.atOffset(ZoneOffset.UTC)),
                        eq(50));
        // Ordering and the limit are the semantics a caller depends on: it is reading back the turns
        // of a run that just happened, so oldest-first or an unbounded read would both be wrong.
        assertThat(sql.getValue())
                .contains("created_at >= ?")
                .contains("ORDER BY created_at DESC")
                .contains("LIMIT ?");
    }

    @Test
    void findRecordedNeverPassesANonPositiveLimitToSql() {
        // LIMIT 0 would return nothing and LIMIT -1 is a syntax error on Postgres; both would read
        // as "no traces recorded", which is exactly the ambiguity this read path exists to remove.
        repository.findRecorded(Instant.parse("2026-09-04T00:00:00Z"), 0);

        verify(jdbcTemplate).query(anyString(), ArgumentMatchers.<RowMapper<EvalTurnTrace>>any(), any(), eq(1));
    }

    // ── every timestamp bound to JDBC must be a type the pg driver can infer ──

    @Test
    void saveBindsTimestampsAsOffsetDateTimeNotInstant() {
        // The pg driver cannot infer a SQL type for java.time.Instant:
        //   "Can't infer the SQL type to use for an instance of java.time.Instant"
        // Every write since #1682 failed with that, swallowed by ToolInvocationRecorder's catch, so
        // mcp_eval_turn_trace held zero rows on alpha while the feature looked enabled. A mocked
        // JdbcTemplate accepts any Object, which is why the existing tests passed over it — so this
        // asserts the BOUND TYPES, not just the statement text.
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);

        repository.save(trace());

        verify(jdbcTemplate).update(anyString(), args.capture(), args.capture(), args.capture(), args.capture());
        assertThat(args.getAllValues())
                .as("no argument bound to JDBC may be a java.time.Instant")
                .noneMatch(Instant.class::isInstance);
    }

    @Test
    void deleteExpiredBindsABindableTimestamp() {
        ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);

        repository.deleteExpired(Instant.parse("2026-09-04T00:00:00Z"));

        verify(jdbcTemplate).update(anyString(), arg.capture());
        assertThat(arg.getValue()).isNotInstanceOf(Instant.class);
    }

    @Test
    void findRecordedBindsABindableTimestamp() {
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);

        repository.findRecorded(Instant.parse("2026-09-04T00:00:00Z"), 25);

        verify(jdbcTemplate)
                .query(anyString(), ArgumentMatchers.<RowMapper<EvalTurnTrace>>any(), args.capture(), args.capture());
        assertThat(args.getAllValues()).noneMatch(Instant.class::isInstance);
    }
}
