package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

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
                .update(sql.capture(), any(UUID.class), any(Instant.class), any(Instant.class), anyString());
        assertThat(sql.getValue()).contains("INSERT INTO mcp_eval_turn_trace").contains("CAST(? AS jsonb)");
    }

    @Test
    void deleteExpiredUsesInclusiveExpiryBoundary() {
        Instant boundary = Instant.parse("2026-09-04T20:00:00Z");

        repository.deleteExpired(boundary);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Instant.class));
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
                null);
    }
}
