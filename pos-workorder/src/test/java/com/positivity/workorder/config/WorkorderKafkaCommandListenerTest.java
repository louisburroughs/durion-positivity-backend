package com.positivity.workorder.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.positivity.workorder.internal.config.WorkorderKafkaCommandListener;
import com.positivity.workorder.internal.dto.AssignmentUpdatePayload;
import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;

import tools.jackson.databind.ObjectMapper;

class WorkorderKafkaCommandListenerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(java.time.Instant.parse("2024-01-01T00:00:00Z"),
            java.time.ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);

    private WorkorderKafkaCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new WorkorderKafkaCommandListener(FIXED_CLOCK, objectMapper, eventPublisher);
    }

    @Test
    @DisplayName("Publishes AssignmentUpdatedEvent when ASSIGNMENT_UPDATED command is received")
    void publishesAssignmentUpdatedEventFromEnvelope() {
        UUID workorderId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

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
        org.assertj.core.api.Assertions.assertThat(event.getPayload().getLocationId()).isEqualTo(locationId);
        org.assertj.core.api.Assertions.assertThat(event.getPayload().getResourceId()).isEqualTo(resourceId);
    }

    @Test
    @DisplayName("Publishes AssignmentUpdatedEvent when payload is direct AssignmentUpdatedEvent JSON")
    void publishesAssignmentUpdatedEventFromDirectPayload() throws Exception {
        AssignmentUpdatedEvent direct = AssignmentUpdatedEvent.builder()
                .workorderId(UUID.randomUUID())
                .payload(AssignmentUpdatePayload.builder()
                        .locationId(UUID.randomUUID())
                        .resourceId(UUID.randomUUID())
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
    }
}
