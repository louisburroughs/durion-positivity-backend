package com.positivity.mcp.internal.eval;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AlphaEvalTraceRetentionSchedulerTest {

    @Test
    void cleanupDeletesEveryTraceExpiredAtTheCurrentClockInstant() {
        Instant now = Instant.parse("2026-09-04T20:00:00Z");
        EvalTurnTraceRepository repository = mock(EvalTurnTraceRepository.class);
        AlphaEvalTraceRetentionScheduler scheduler =
                new AlphaEvalTraceRetentionScheduler(repository, Clock.fixed(now, ZoneOffset.UTC));

        scheduler.deleteExpiredTraces();

        verify(repository).deleteExpired(now);
    }
}
