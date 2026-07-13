package com.positivity.peoplecontact.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.peoplecontact.service.OutboxReplayService;
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
 * Unit tests for {@link PeopleContactCommandListener} (ADR-0044 §4, #874).
 */
class PeopleContactCommandListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC);

    private final OutboxReplayService replayService = mock(OutboxReplayService.class);

    private PeopleContactCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new PeopleContactCommandListener(TEST_CLOCK, new ObjectMapper(), replayService);
        ReflectionTestUtils.setField(listener, "replayMaxLookback", Duration.ofDays(30));
    }

    private String replayCommand(String since, String until) {
        return """
                {"commandType":"people-contact.outbox.replay-requested",
                 "payload":{"since":"%s","until":"%s"}}
                """.formatted(since, until);
    }

    @Test
    @DisplayName("Bounded replay command re-queues the window with slack")
    void boundedReplay() {
        listener.onCommand(replayCommand("2026-07-12T10:00:00Z", "2026-07-12T11:00:00Z"));

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
        verify(replayService).replayBetween(since.capture(), until.capture());
        assertThat(since.getValue()).isEqualTo(Instant.parse("2026-07-12T09:59:59Z"));
        assertThat(until.getValue()).isEqualTo(Instant.parse("2026-07-12T11:00:01Z"));
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
    @DisplayName("Transient DB errors rethrow for container retry/DLQ (ADR-0044 §4, PR #865 review)")
    void rethrowsTransientErrors() {
        when(replayService.replayBetween(any(), any())).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onCommand(replayCommand("2026-07-12T10:00:00Z", "2026-07-12T11:00:00Z")));
    }
}
