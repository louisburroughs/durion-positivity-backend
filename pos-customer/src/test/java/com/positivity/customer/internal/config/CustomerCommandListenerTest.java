package com.positivity.customer.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.AddSuppressionRequest;
import com.positivity.customer.internal.dto.SuppressionEntryResponse;
import com.positivity.customer.internal.enums.ConsentChangeSource;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.SuppressionReason;
import com.positivity.customer.service.OutboxReplayService;
import com.positivity.customer.service.SuppressionService;
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
 * Unit tests for {@link CustomerCommandListener} (ADR-0044 §4, #889).
 */
class CustomerCommandListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);

    private final OutboxReplayService replayService = mock(OutboxReplayService.class);
    private final com.positivity.customer.service.SegmentService segmentService =
            mock(com.positivity.customer.service.SegmentService.class);
    private final SuppressionService suppressionService = mock(SuppressionService.class);

    private CustomerCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new CustomerCommandListener(
                TEST_CLOCK,
                new ObjectMapper(),
                replayService,
                new CustomerCommandHandlers(segmentService, suppressionService));
        ReflectionTestUtils.setField(listener, "replayMaxLookback", Duration.ofDays(30));
    }

    private String replayCommand(String since, String until) {
        return """
                {"commandType":"customer.outbox.replay-requested",
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
                {"commandType":"CUSTOMER_OUTBOX_REPLAY_REQUESTED","payload":{"since":"2026-07-13T10:00:00Z"}}
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
    @DisplayName("Suppression-add command from provider feedback lands in SuppressionService (#1150)")
    void appliesSuppressionAddCommand() {
        when(suppressionService.add(any()))
                .thenReturn(new SuppressionEntryResponse(
                        java.util.UUID.fromString("01960006-0000-7000-8000-000000000080"),
                        "EMAIL",
                        "j***@example.com",
                        java.util.UUID.fromString("01960006-0000-7000-8000-000000000081"),
                        "HARD_BOUNCE",
                        "SYSTEM",
                        null,
                        Instant.parse("2026-07-13T12:00:00Z")));

        listener.onCommand("""
                {"commandType":"customer.suppression.add-requested","eventId":"evt-1",
                 "payload":{"channel":"EMAIL","address":"jane@example.com",
                            "partyId":"01960006-0000-7000-8000-000000000081","reason":"HARD_BOUNCE"}}
                """);

        ArgumentCaptor<AddSuppressionRequest> request = ArgumentCaptor.forClass(AddSuppressionRequest.class);
        verify(suppressionService).add(request.capture());
        assertThat(request.getValue().getChannel()).isEqualTo(MarketingChannel.EMAIL);
        assertThat(request.getValue().getAddress()).isEqualTo("jane@example.com");
        assertThat(request.getValue().getReason()).isEqualTo(SuppressionReason.HARD_BOUNCE);
        assertThat(request.getValue().getSource()).isEqualTo(ConsentChangeSource.SYSTEM);
    }

    @Test
    @DisplayName("Suppression-add command missing the address is dropped, not retried")
    void dropsSuppressionCommandWithoutAddress() {
        assertThatCode(() -> listener.onCommand("""
                        {"commandType":"customer.suppression.add-requested",
                         "payload":{"channel":"EMAIL","reason":"HARD_BOUNCE"}}
                        """)).doesNotThrowAnyException();
        verify(suppressionService, never()).add(any());
    }

    @Test
    @DisplayName("Transient DB errors rethrow for container retry/DLQ (ADR-0044 §4)")
    void rethrowsTransientErrors() {
        when(replayService.replayBetween(any(), any())).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onCommand(replayCommand("2026-07-13T10:00:00Z", "2026-07-13T11:00:00Z")));
    }
}
