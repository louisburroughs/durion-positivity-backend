package com.positivity.workorder.config;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.positivity.workorder.internal.config.KafkaEventRelay;
import com.positivity.workorder.internal.config.KafkaProducer;
import com.positivity.workorder.internal.domain.WorkSessionStartedEvent;
import com.positivity.workorder.internal.event.EstimateRevisedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkorderKafkaEventRelayTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final KafkaProducer producer = org.mockito.Mockito.mock(KafkaProducer.class);
    private final KafkaEventRelay relay = new KafkaEventRelay(producer);

    @Test
    @DisplayName("Relays WorkSessionStartedEvent to Kafka with expected event type")
    void relaysWorkSessionStartedEvent() {
        WorkSessionStartedEvent event = new WorkSessionStartedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Instant.now(TEST_CLOCK));

        relay.onWorkSessionStarted(event);

        verify(producer)
                .publish(
                        "workorder.work_session.started.v1",
                        event.workSessionId().toString(),
                        event);
    }

    @Test
    @DisplayName("Skips EstimateRevisedEvent publish when workorderId is null")
    void skipsEstimateRevisedWithNullWorkorderId() {
        EstimateRevisedEvent event = EstimateRevisedEvent.builder()
                .estimateId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderId(null)
                .timestamp(Instant.now(TEST_CLOCK))
                .build();

        relay.onEstimateRevised(event);

        verifyNoInteractions(producer);
    }
}
