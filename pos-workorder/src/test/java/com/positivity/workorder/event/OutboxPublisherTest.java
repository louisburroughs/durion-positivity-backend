package com.positivity.workorder.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.tenancy.kafka.TenantKafkaHeaders;
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
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxPublisherTest {
    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");
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
                .tenantId(TENANT)
                .id(UUID.randomUUID())
                .topic("workorder.events.v1")
                .recordKey(key)
                .payload("{\"eventId\":\"" + key + "\"}")
                .createdAt(Instant.parse("2026-07-08T11:59:00Z"))
                .attempts(2)
                .lastError("previous failure")
                .build();
    }

    @Test
    @DisplayName("Marks row published only after the broker acknowledges the send")
    void marksPublishedAfterAcknowledgedSend() {
        OutboxEvent row = event("k1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.captor();
        verify(kafkaTemplate).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("workorder.events.v1");
        assertThat(sent.getValue().key()).isEqualTo("k1");
        assertThat(sent.getValue().value()).isEqualTo(row.getPayload());
        assertThat(TenantKafkaHeaders.read(sent.getValue().headers()))
                .as("the row's tenant travels on the record header (ADR-0062)")
                .contains(TENANT);
        assertThat(row.getPublishedAt()).isEqualTo(Instant.parse("2026-07-08T12:00:00Z"));
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();
        verify(repository).save(row);
    }

    @Test
    @DisplayName("On failure: records attempt and error, keeps row unpublished, stops the batch")
    void recordsFailureAndStopsBatch() {
        OutboxEvent first = event("k1");
        OutboxEvent second = event("k2");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(3);
        assertThat(first.getLastError()).contains("broker down");
        // Batch stops at the first failure to preserve publish order: only k1 was attempted.
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.captor();
        verify(kafkaTemplate).send(sent.capture());
        assertThat(sent.getValue().key()).isEqualTo("k1");
        assertThat(sent.getValue().value()).isEqualTo(first.getPayload());
        assertThat(second.getAttempts()).isEqualTo(2);
    }
}
