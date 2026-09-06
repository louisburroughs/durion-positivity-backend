package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.EvalTurnTrace;
import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #1706 ask 2: the read path for recorded turn traces.
 *
 * <p>The behaviour that matters most here is the caller scope. A trace carries the assembled system
 * prompt, the caller's message and every tool result, so returning another actor's trace would turn
 * an eval convenience into a disclosure surface.
 */
class EvalTurnTraceQueryServiceTest {

    private static EvalTurnTrace trace(String username) {
        Instant now = Instant.now();
        return new EvalTurnTrace(
                UUID.randomUUID(),
                now,
                now,
                now.plus(Duration.ofDays(1)),
                UUID.randomUUID(),
                username,
                "ROLE_ADMIN",
                "what is our AR?",
                false,
                null,
                null,
                null,
                List.of("AccountingFacadeTool"),
                "system prompt with layers",
                List.of(),
                List.of(),
                "the answer",
                null,
                "sha-test0000",
                "CONTENT");
    }

    private static EvalTurnTraceQueryService serviceReturning(EvalTurnTrace... traces) {
        EvalTurnTraceRepository repository = mock(EvalTurnTraceRepository.class);
        when(repository.findRecorded(any(Instant.class), anyInt())).thenReturn(List.of(traces));
        return new EvalTurnTraceQueryService(repository);
    }

    @Test
    @DisplayName("returns the caller's own traces")
    void list_returnsCallersTraces() {
        EvalTurnTraceQueryService service = serviceReturning(trace("admin.alpha"), trace("admin.alpha"));

        List<EvalTurnTrace> body = service.findForCaller("admin.alpha", Instant.now(), 50);

        assertThat(body).hasSize(2);
    }

    @Test
    @DisplayName("never returns another actor's trace, whatever the caller holds")
    void list_excludesOtherActors() {
        // The whole point of the caller filter. A trace carries the assembled system prompt and the
        // other user's message; leaking it would be worse than the missing read path it replaces.
        EvalTurnTraceQueryService service =
                serviceReturning(trace("margaret.olsen"), trace("admin.alpha"), trace("someone.else"));

        List<EvalTurnTrace> body = service.findForCaller("admin.alpha", Instant.now(), 50);

        assertThat(body).hasSize(1);
        assertThat(body).allSatisfy(trace -> assertThat(trace.username()).isEqualTo("admin.alpha"));
    }

    @Test
    @DisplayName("the limit applies after the caller filter, not before it")
    void list_limitAppliesToTheCallersOwnTraces() {
        // Filtering after limiting would let another actor's traces consume the caller's quota, so
        // a busy shared window could return zero of the caller's own while reporting success.
        EvalTurnTraceQueryService service = serviceReturning(
                trace("margaret.olsen"), trace("margaret.olsen"), trace("admin.alpha"), trace("admin.alpha"));

        List<EvalTurnTrace> body = service.findForCaller("admin.alpha", Instant.now(), 2);

        assertThat(body).hasSize(2);
        assertThat(body).allSatisfy(trace -> assertThat(trace.username()).isEqualTo("admin.alpha"));
    }

    @Test
    @DisplayName("an absurd limit is clamped rather than honoured")
    void list_clampsTheLimit() {
        EvalTurnTraceQueryService service = serviceReturning(trace("admin.alpha"));

        assertThat(service.findForCaller("admin.alpha", Instant.now(), 100000)).hasSize(1);
        assertThat(service.findForCaller("admin.alpha", Instant.now(), 0)).hasSize(1);
    }
}
