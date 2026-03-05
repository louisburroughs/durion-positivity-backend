package com.positivity.workorder.config;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.positivity.workorder.internal.config.WorkorderKafkaEventRelay;
import com.positivity.workorder.internal.config.WorkorderKafkaProducer;
import com.positivity.workorder.internal.domain.WorkSessionStartedEvent;
import com.positivity.workorder.internal.event.EstimateRevisedEvent;

class WorkorderKafkaEventRelayTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);


    private final WorkorderKafkaProducer producer = org.mockito.Mockito.mock(WorkorderKafkaProducer.class);
    private final WorkorderKafkaEventRelay relay = new WorkorderKafkaEventRelay(producer);

    @Test
    @DisplayName("Relays WorkSessionStartedEvent to Kafka with expected event type")
    void relaysWorkSessionStartedEvent() {
        WorkSessionStartedEvent event = new WorkSessionStartedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(TEST_CLOCK));

        relay.onWorkSessionStarted(event);

        verify(producer).publish("workorder.work_session.started.v1", event.workSessionId().toString(), event);
    }

    @Test
    @DisplayName("Skips EstimateRevisedEvent publish when workorderId is null")
    void skipsEstimateRevisedWithNullWorkorderId() {
        EstimateRevisedEvent event = EstimateRevisedEvent.builder()
                .estimateId(UUID.randomUUID())
                .workorderId(null)
                .timestamp(Instant.now(TEST_CLOCK))
                .build();

        relay.onEstimateRevised(event);

        verifyNoInteractions(producer);
    }
}
