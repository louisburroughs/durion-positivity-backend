package com.positivity.workorder.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.positivity.workorder.internal.config.KafkaCommandListener;
import com.positivity.workorder.internal.dto.AssignmentUpdatePayload;
import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import com.positivity.workorder.service.OutboxReplayService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

class WorkorderKafkaCommandListenerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(java.time.Instant.parse("2024-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    private final OutboxReplayService outboxReplayService = org.mockito.Mockito.mock(OutboxReplayService.class);

    private KafkaCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new KafkaCommandListener(FIXED_CLOCK, objectMapper, eventPublisher, outboxReplayService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                listener, "replayMaxLookback", java.time.Duration.ofDays(30));
    }

    @Test
    @DisplayName("Publishes AssignmentUpdatedEvent when ASSIGNMENT_UPDATED command is received")
    void publishesAssignmentUpdatedEventFromEnvelope() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID resourceId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        String message = """
                {
                  "commandType":"ASSIGNMENT_UPDATED",
                  "payload":{
                    "workorderId":"%s",
                    "payload":{
                      "locationId":"%s",
                      "resourceId":"%s",
                      "mechanicIds":[]
                    }
                  }
                }
                """.formatted(workorderId, locationId, resourceId);

        listener.onCommand(message);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        AssignmentUpdatedEvent event = (AssignmentUpdatedEvent) captor.getValue();
        org.assertj.core.api.Assertions.assertThat(event.getWorkorderId()).isEqualTo(workorderId);
        org.assertj.core.api.Assertions.assertThat(event.getPayload()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(event.getPayload().getLocationId())
                .isEqualTo(locationId);
        org.assertj.core.api.Assertions.assertThat(event.getPayload().getResourceId())
                .isEqualTo(resourceId);
    }

    @Test
    @DisplayName("Publishes AssignmentUpdatedEvent when payload is direct AssignmentUpdatedEvent JSON")
    void publishesAssignmentUpdatedEventFromDirectPayload() throws Exception {
        AssignmentUpdatedEvent direct = AssignmentUpdatedEvent.builder()
                .workorderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .payload(AssignmentUpdatePayload.builder()
                        .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .resourceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .build())
                .build();

        listener.onCommand(objectMapper.writeValueAsString(direct));

        verify(eventPublisher).publishEvent(any(AssignmentUpdatedEvent.class));
    }

    @Test
    @DisplayName("Ignores unsupported command types")
    void ignoresUnsupportedCommandType() {
        listener.onCommand("""
                {"commandType":"UNKNOWN_COMMAND","payload":{"x":"y"}}
                """);

        verify(eventPublisher, never()).publishEvent(any());
        verify(outboxReplayService, never()).replaySince(any());
    }

    @Test
    @DisplayName("workorder.outbox.replay-requested without until replays from since (slack-padded)")
    void replayCommandTriggersOutboxReplay() {
        listener.onCommand("""
                {"commandType":"workorder.outbox.replay-requested","payload":{"since":"2026-07-08T10:00:00Z"}}
                """);

        verify(outboxReplayService).replaySince(Instant.parse("2026-07-08T09:59:59Z"));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Replay command with until repairs only the window (slack-padded on both ends)")
    void replayCommandWithUntilIsBounded() {
        listener.onCommand("""
                {"commandType":"workorder.outbox.replay-requested",
                 "payload":{"since":"2026-07-08T10:00:00Z","until":"2026-07-08T11:00:00Z"}}
                """);

        verify(outboxReplayService)
                .replayBetween(Instant.parse("2026-07-08T09:59:59Z"), Instant.parse("2026-07-08T11:00:01Z"));
        verify(outboxReplayService, never()).replaySince(any());
    }

    @Test
    @DisplayName("Replay command older than the max lookback is rejected")
    void replayCommandBeyondLookbackIsIgnored() {
        // Clock is 2024-01-01; since is >30 days earlier.
        listener.onCommand("""
                {"commandType":"workorder.outbox.replay-requested","payload":{"since":"2023-10-01T00:00:00Z"}}
                """);

        verify(outboxReplayService, never()).replaySince(any());
        verify(outboxReplayService, never()).replayBetween(any(), any());
    }

    @Test
    @DisplayName("Replay command without a parseable payload.since is ignored")
    void replayCommandWithoutSinceIsIgnored() {
        listener.onCommand("""
                {"commandType":"WORKORDER_OUTBOX_REPLAY_REQUESTED","payload":{}}
                """);
        listener.onCommand("""
                {"commandType":"workorder.outbox.replay-requested","payload":{"since":"not-a-timestamp"}}
                """);

        verify(outboxReplayService, never()).replaySince(any());
    }
}
