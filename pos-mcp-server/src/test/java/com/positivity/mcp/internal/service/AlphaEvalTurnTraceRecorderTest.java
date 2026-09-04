package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AlphaEvalTurnTraceRecorderTest {

    private static final Instant NOW = Instant.parse("2026-09-03T20:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final CurrentUserContext USER = new CurrentUserContext(
            "diana.rowe",
            UUID.fromString("01960010-0000-7000-8000-000000000002"),
            "LOCATION_MANAGER",
            Set.of("LOCATION_MANAGER"),
            Set.of(),
            Set.of("accounting:report:view"));

    private final EvalTurnTraceRepository repository = mock(EvalTurnTraceRepository.class);
    private final AlphaEvalTurnTraceRecorder recorder =
            new AlphaEvalTurnTraceRecorder(repository, Clock.fixed(NOW, ZoneOffset.UTC), RETENTION);

    @Test
    void completedTurnPersistsAllStagesAndOrderedToolIo() {
        recorder.begin(USER, "Show revenue for last month");
        recorder.recordSimpleChat(false);
        recorder.recordRouting("ANALYTICS", "T2_COMPLEX");
        recorder.recordWorkflowState("IDLE");
        recorder.recordSelectedTools(List.of("DateWindowFacadeTool", "AccountingFacadeTool"));
        recorder.recordPrompt(
                "assembled prompt",
                List.of(new EvalTurnTrace.ToolDefinitionTrace(
                        "resolveDateWindow", "Resolve a date window", "{\"type\":\"object\"}")));
        recorder.recordToolCall(
                "resolveDateWindow",
                "{\"expression\":\"last month\"}",
                "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-31\",\"shape\":\"calendar\"}",
                null,
                4);
        recorder.recordToolCall(
                "getRevenueByCustomer",
                "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-31\"}",
                "{\"customers\":[]}",
                null,
                8);

        recorder.complete("Revenue was $1,500.00.");

        EvalTurnTrace trace = savedTrace();
        assertThat(trace.turnId().version()).isEqualTo(7);
        assertThat(trace.startedAt()).isEqualTo(NOW);
        assertThat(trace.completedAt()).isEqualTo(NOW);
        assertThat(trace.expiresAt()).isEqualTo(NOW.plus(RETENTION));
        assertThat(trace.username()).isEqualTo("diana.rowe");
        assertThat(trace.userId()).isEqualTo(USER.userId());
        assertThat(trace.role()).isEqualTo("LOCATION_MANAGER");
        assertThat(trace.userMessage()).isEqualTo("Show revenue for last month");
        assertThat(trace.simpleChat()).isFalse();
        assertThat(trace.intent()).isEqualTo("ANALYTICS");
        assertThat(trace.modelTier()).isEqualTo("T2_COMPLEX");
        assertThat(trace.workflowState()).isEqualTo("IDLE");
        assertThat(trace.selectedTools()).containsExactly("DateWindowFacadeTool", "AccountingFacadeTool");
        assertThat(trace.systemPrompt()).isEqualTo("assembled prompt");
        assertThat(trace.offeredTools())
                .extracting(EvalTurnTrace.ToolDefinitionTrace::name)
                .containsExactly("resolveDateWindow");
        assertThat(trace.toolCalls())
                .extracting(EvalTurnTrace.ToolCallTrace::sequence)
                .containsExactly(1, 2);
        assertThat(trace.toolCalls())
                .extracting(EvalTurnTrace.ToolCallTrace::name)
                .containsExactly("resolveDateWindow", "getRevenueByCustomer");
        assertThat(trace.finalResponse()).isEqualTo("Revenue was $1,500.00.");
        assertThat(trace.error()).isNull();
        assertThat(recorder.hasActiveTurn()).isFalse();
    }

    @Test
    void failedTurnPersistsErrorAndAlwaysClearsActiveState() {
        recorder.begin(USER, "broken turn");

        recorder.fail(new IllegalStateException("model unavailable"));

        EvalTurnTrace trace = savedTrace();
        assertThat(trace.finalResponse()).isNull();
        assertThat(trace.error()).isEqualTo("IllegalStateException: model unavailable");
        assertThat(recorder.hasActiveTurn()).isFalse();
    }

    @Test
    void persistenceFailureNeverLeaksTurnState() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository)
                .save(org.mockito.ArgumentMatchers.any());
        recorder.begin(USER, "safe failure");

        recorder.complete("answer");

        assertThat(recorder.hasActiveTurn()).isFalse();
    }

    private EvalTurnTrace savedTrace() {
        ArgumentCaptor<EvalTurnTrace> captor = ArgumentCaptor.forClass(EvalTurnTrace.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
