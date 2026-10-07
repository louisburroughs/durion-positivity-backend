package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S16 (#2512): {@code accounting.outbox.replay-requested} on {@code accounting.commands.v1}
 * re-queues the drifted window of the requesting tenant's accounting facts (ADR-0044 §4).
 */
@DisplayName("AccountingCommandListener — accounting.commands.v1 replay requests")
class AccountingCommandListenerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);

    private final OutboxReplayService replayService = mock(OutboxReplayService.class);
    private final AccountingCommandListener listener =
            new AccountingCommandListener(CLOCK, new ObjectMapper(), replayService);

    @Test
    @DisplayName("a bounded window is re-queued with a second of slack either side")
    void replaysTheWindow() {
        listener.onCommand("""
                {"commandType":"accounting.outbox.replay-requested",
                 "payload":{"since":"2026-10-07T10:00:00Z","until":"2026-10-07T11:00:00Z"}}
                """);

        verify(replayService)
                .replayBetween(Instant.parse("2026-10-07T09:59:59Z"), Instant.parse("2026-10-07T11:00:01Z"));
    }

    @Test
    @DisplayName("an open window replays everything since its start")
    void replaysSince() {
        listener.onCommand("""
                {"commandType":"ACCOUNTING_OUTBOX_REPLAY_REQUESTED","payload":{"since":"2026-10-07T10:00:00Z"}}
                """);

        verify(replayService).replaySince(Instant.parse("2026-10-07T09:59:59Z"));
    }

    @Test
    @DisplayName("a request older than the look-back, a missing since, or another command replays nothing")
    void ignoresWhatItCannotServe() {
        listener.onCommand("""
                {"commandType":"accounting.outbox.replay-requested","payload":{"since":"2026-01-01T00:00:00Z"}}
                """);
        listener.onCommand("{\"commandType\":\"accounting.outbox.replay-requested\",\"payload\":{}}");
        listener.onCommand("{\"commandType\":\"accounting.something-else\",\"payload\":{}}");
        listener.onCommand("not json");

        verifyNoInteractions(replayService);
    }

    @Test
    @DisplayName("a transient database failure propagates for container retry")
    void transientFailurePropagates() {
        when(replayService.replayBetween(any(), any())).thenThrow(new QueryTimeoutException("timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class).isThrownBy(() -> listener.onCommand("""
                {"commandType":"accounting.outbox.replay-requested",
                 "payload":{"since":"2026-10-07T10:00:00Z","until":"2026-10-07T11:00:00Z"}}
                """));
    }
}
