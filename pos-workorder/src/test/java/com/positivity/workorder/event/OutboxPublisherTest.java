package com.positivity.workorder.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.config.OutboxPublisher;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxPublisherTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-08T12:00:00Z"), ZoneOffset.UTC);

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> meterRegistry = mock(ObjectProvider.class);

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        when(meterRegistry.getIfAvailable()).thenReturn(null);
        publisher = new OutboxPublisher(repository, kafkaTemplate, TEST_CLOCK, meterRegistry);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 1000L);
    }

    private OutboxEvent event(String key) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .topic("workorder.events.v1")
                .recordKey(key)
                .payload("{\"eventId\":\"" + key + "\"}")
                .createdAt(Instant.parse("2026-07-08T11:59:00Z"))
                .build();
    }

    @Test
    @DisplayName("Marks row published only after the broker acknowledges the send")
    void marksPublishedAfterAcknowledgedSend() {
        OutboxEvent row = event("k1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(row));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishPending();

        verify(kafkaTemplate).send("workorder.events.v1", "k1", row.getPayload());
        assertThat(row.getPublishedAt()).isEqualTo(Instant.parse("2026-07-08T12:00:00Z"));
        assertThat(row.getAttempts()).isZero();
        verify(repository).save(row);
    }

    @Test
    @DisplayName("On failure: records attempt and error, keeps row unpublished, stops the batch")
    void recordsFailureAndStopsBatch() {
        OutboxEvent first = event("k1");
        OutboxEvent second = event("k2");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).contains("broker down");
        // Batch stops at the first failure to preserve publish order: only k1 was attempted.
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
        verify(kafkaTemplate).send("workorder.events.v1", "k1", first.getPayload());
        assertThat(second.getAttempts()).isZero();
    }
}
