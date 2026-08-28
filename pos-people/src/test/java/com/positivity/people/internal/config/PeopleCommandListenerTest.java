package com.positivity.people.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link PeopleCommandListener} (ADR-0044 §4).
 *
 * <p>
 * This is the repair end of reconciliation: a downstream replica that detected
 * drift asks this module to re-emit a window of its outbox. Two guards make that
 * safe to expose over Kafka:
 *
 * <ul>
 * <li><b>A `since` older than the configured lookback is refused.</b> Replay
 * cost scales with the window, so a malformed or ancient timestamp must not
 * trigger a re-emit of the entire outbox.</li>
 * <li><b>The window is widened by a second at each end.</b> Outbox
 * {@code createdAt} and the UUIDv7 timestamp inside the eventId can disagree by
 * under a millisecond, and a window that excluded a boundary event would leave
 * the drift it was sent to repair still in place.</li>
 * </ul>
 *
 * <p>
 * Failure handling splits by kind: a transient database error is rethrown so the
 * container retries and eventually routes to the DLQ (replay is idempotent, so
 * redelivery is harmless), while a malformed command is logged and dropped —
 * retrying cannot fix it and would poison the partition.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PeopleCommandListener — outbox replay commands")
class PeopleCommandListenerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Mock
    private OutboxReplayService outboxReplayService;

    private PeopleCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new PeopleCommandListener(Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper(), outboxReplayService);
        ReflectionTestUtils.setField(listener, "replayMaxLookback", Duration.ofDays(30));
    }

    private static String command(String commandType, String since, String until) {
        return """
                {"commandType":"%s","payload":{"since":%s,"until":%s}}""".formatted(
                        commandType,
                        since == null ? "null" : "\"" + since + "\"",
                        until == null ? "null" : "\"" + until + "\"");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PEOPLE_OUTBOX_REPLAY_REQUESTED",
                "people.outbox.replay-requested",
                "People.Outbox.Replay-Requested"
            })
    @DisplayName("accepts the replay command in dotted, hyphenated, or already-normalized form")
    void commandTypeIsNormalized(String commandType) {
        listener.onCommand(command(commandType, NOW.minusSeconds(600).toString(), null));

        // Producers spell this differently; normalizing here is what keeps them interoperable.
        verify(outboxReplayService).replaySince(any());
    }

    @Test
    @DisplayName("widens a bounded window by a second at each end to cover timestamp skew")
    void boundedWindowGetsSlack() {
        Instant since = NOW.minusSeconds(600);
        Instant until = NOW.minusSeconds(300);

        listener.onCommand(command("people.outbox.replay-requested", since.toString(), until.toString()));

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(outboxReplayService).replayBetween(from.capture(), to.capture());
        // Outbox createdAt and the eventId's embedded timestamp can disagree sub-millisecond; a
        // window that clipped a boundary event would leave the drift unrepaired.
        assertThat(from.getValue()).isEqualTo(since.minusSeconds(1));
        assertThat(to.getValue()).isEqualTo(until.plusSeconds(1));
    }

    @Test
    @DisplayName("falls back to an open-ended replay when no usable end is given")
    void openEndedReplay() {
        Instant since = NOW.minusSeconds(600);

        listener.onCommand(command("people.outbox.replay-requested", since.toString(), null));

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(outboxReplayService).replaySince(from.capture());
        assertThat(from.getValue()).isEqualTo(since.minusSeconds(1));
        verify(outboxReplayService, never()).replayBetween(any(), any());
    }

    @Test
    @DisplayName("treats an end at or before the start as no end at all")
    void invertedWindowFallsBackToOpenEnded() {
        Instant since = NOW.minusSeconds(600);

        listener.onCommand(command("people.outbox.replay-requested", since.toString(), since.toString()));

        // An inverted or empty window would otherwise replay nothing and silently leave the drift.
        verify(outboxReplayService).replaySince(any());
        verify(outboxReplayService, never()).replayBetween(any(), any());
    }

    @Test
    @DisplayName("refuses a start older than the configured lookback")
    void ancientSinceIsRefused() {
        listener.onCommand(command(
                "people.outbox.replay-requested", NOW.minus(Duration.ofDays(31)).toString(), null));

        // Replay cost scales with the window; an ancient start must not re-emit the whole outbox.
        verifyNoInteractions(outboxReplayService);
    }

    @Test
    @DisplayName("accepts a start just inside the lookback boundary")
    void sinceInsideLookbackIsAccepted() {
        listener.onCommand(command(
                "people.outbox.replay-requested", NOW.minus(Duration.ofDays(29)).toString(), null));

        verify(outboxReplayService).replaySince(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-timestamp"})
    @DisplayName("ignores a replay command whose start is missing or unparseable")
    void unusableSinceIsIgnored(String since) {
        listener.onCommand(command("people.outbox.replay-requested", since.isEmpty() ? null : since, null));

        verifyNoInteractions(outboxReplayService);
    }

    @Test
    @DisplayName("ignores a command with no payload at all")
    void missingPayloadIsIgnored() {
        listener.onCommand("{\"commandType\":\"people.outbox.replay-requested\"}");

        verifyNoInteractions(outboxReplayService);
    }

    @Test
    @DisplayName("ignores a command with no commandType and one this module does not handle")
    void unknownCommandsAreIgnored() {
        listener.onCommand("{\"payload\":{}}");
        listener.onCommand(command("people.something.else", NOW.toString(), null));

        verifyNoInteractions(outboxReplayService);
    }

    @Test
    @DisplayName("drops a malformed message rather than poisoning the partition")
    void malformedMessageIsDropped() {
        listener.onCommand("{not json");

        verifyNoInteractions(outboxReplayService);
    }

    @Test
    @DisplayName("rethrows a transient database error so the container retries and can reach the DLQ")
    void transientErrorIsRethrown() {
        when(outboxReplayService.replaySince(any())).thenThrow(new QueryTimeoutException("lock wait"));

        // Replay is idempotent, so a redelivery is harmless — losing the repair request is not.
        assertThatThrownBy(() -> listener.onCommand(command(
                        "people.outbox.replay-requested", NOW.minusSeconds(600).toString(), null)))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    @DisplayName("swallows a non-transient failure from the replay service")
    void permanentFailureIsSwallowed() {
        when(outboxReplayService.replaySince(any())).thenThrow(new IllegalStateException("bad state"));

        listener.onCommand(
                command("people.outbox.replay-requested", NOW.minusSeconds(600).toString(), null));

        // Retrying cannot fix it; the next reconciliation window re-detects and re-requests.
        verify(outboxReplayService).replaySince(any());
    }
}
