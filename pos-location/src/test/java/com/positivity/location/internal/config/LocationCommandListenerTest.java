package com.positivity.location.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link LocationCommandListener} (ADR-0044 §4, #890).
 */
class LocationCommandListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);

    private final OutboxReplayService replayService = mock(OutboxReplayService.class);
    private final FactBackfillService factBackfillService = mock(FactBackfillService.class);

    private LocationCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new LocationCommandListener(TEST_CLOCK, new ObjectMapper(), replayService, factBackfillService);
        ReflectionTestUtils.setField(listener, "replayMaxLookback", Duration.ofDays(30));
    }

    private String replayCommand(String since, String until) {
        return """
                {"commandType":"location.outbox.replay-requested",
                 "payload":{"since":"%s","until":"%s"}}
                """.formatted(since, until);
    }

    @Test
    @DisplayName("Bounded replay command re-queues the window with slack")
    void boundedReplay() {
        listener.onCommand(replayCommand("2026-07-13T10:00:00Z", "2026-07-13T11:00:00Z"));

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
        verify(replayService).replayBetween(since.capture(), until.capture());
        assertThat(since.getValue()).isEqualTo(Instant.parse("2026-07-13T09:59:59Z"));
        assertThat(until.getValue()).isEqualTo(Instant.parse("2026-07-13T11:00:01Z"));
    }

    @Test
    @DisplayName("Open-ended replay command falls back to replaySince")
    void openEndedReplay() {
        listener.onCommand("""
                {"commandType":"LOCATION_OUTBOX_REPLAY_REQUESTED","payload":{"since":"2026-07-13T10:00:00Z"}}
                """);

        verify(replayService).replaySince(Instant.parse("2026-07-13T09:59:59Z"));
    }

    @Test
    @DisplayName("Replay commands beyond the max lookback are rejected")
    void rejectsAncientReplay() {
        listener.onCommand(replayCommand("2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z"));

        verify(replayService, never()).replayBetween(any(), any());
        verify(replayService, never()).replaySince(any());
    }

    @Test
    @DisplayName("Malformed commands are logged and dropped, not rethrown")
    void dropsMalformedCommands() {
        assertThatCode(() -> listener.onCommand("{ not json ")).doesNotThrowAnyException();
        assertThatCode(() -> listener.onCommand("{\"commandType\":\"something.else\"}"))
                .doesNotThrowAnyException();
        verify(replayService, never()).replayBetween(any(), any());
    }

    @Test
    @DisplayName("Transient DB errors rethrow for container retry/DLQ (ADR-0044 §4)")
    void rethrowsTransientErrors() {
        when(replayService.replayBetween(any(), any())).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onCommand(replayCommand("2026-07-13T10:00:00Z", "2026-07-13T11:00:00Z")));
    }

    // ---------------------------------------------------------------------------------------
    // Fact backfill command (issue #1668)
    // ---------------------------------------------------------------------------------------

    private String backfillCommand(String aggregate) {
        return aggregate == null ? "{\"commandType\":\"location.fact-backfill.requested\"}" : """
                {"commandType":"location.fact-backfill.requested",
                 "payload":{"aggregate":"%s"}}
                """.formatted(aggregate);
    }

    @Test
    @DisplayName("#1668 backfill command with no aggregate selector seeds both replicas")
    void backfillDefaultsToAll() {
        listener.onCommand(backfillCommand(null));

        verify(factBackfillService).backfillBays();
        verify(factBackfillService).backfillMobileUnits();
    }

    @Test
    @DisplayName("#1668 backfill command scoped to one aggregate leaves the other alone")
    void backfillScopedToOneAggregate() {
        listener.onCommand(backfillCommand("bay"));

        verify(factBackfillService).backfillBays();
        verify(factBackfillService, never()).backfillMobileUnits();
    }

    @Test
    @DisplayName("#1668 backfill command accepts the mobile-unit selector by its dotted contract name")
    void backfillMobileUnitOnly() {
        listener.onCommand(backfillCommand("mobile-unit"));

        verify(factBackfillService).backfillMobileUnits();
        verify(factBackfillService, never()).backfillBays();
    }

    @Test
    @DisplayName("#1668 an unsupported aggregate selector backfills nothing rather than everything")
    void backfillRejectsUnknownAggregate() {
        // A typo must not silently re-emit every fact the module owns.
        listener.onCommand(backfillCommand("bays"));

        verify(factBackfillService, never()).backfillBays();
        verify(factBackfillService, never()).backfillMobileUnits();
    }

    @Test
    @DisplayName("#1668 backfill is not triggered by an unrelated command type")
    void backfillIgnoresOtherCommands() {
        listener.onCommand(replayCommand("2026-07-13T00:00:00Z", "2026-07-13T06:00:00Z"));

        verify(factBackfillService, never()).backfillBays();
        verify(factBackfillService, never()).backfillMobileUnits();
    }
}
