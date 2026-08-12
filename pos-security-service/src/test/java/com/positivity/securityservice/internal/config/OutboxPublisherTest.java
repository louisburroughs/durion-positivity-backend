package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.OutboxEvent;
import com.positivity.securityservice.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

/**
 * Unit tests for the pos-security-service {@link OutboxPublisher} (ADR-0044 §4).
 *
 * <p>
 * The publisher drains {@code event_outbox} to Kafka with at-least-once
 * semantics. Three properties carry the correctness weight:
 *
 * <ul>
 * <li>a row is marked published <em>only</em> after the broker acknowledges, so
 * a crash mid-send re-sends rather than silently dropping the event;</li>
 * <li>the batch stops at the first failure, so a struggling broker cannot
 * reorder events past a stuck row; and</li>
 * <li>the failure path records {@code attempts} and a bounded
 * {@code last_error} for alerting instead of throwing out of the scheduled
 * method.</li>
 * </ul>
 */
@DisplayName("pos-security-service OutboxPublisher — outbox drain contract")
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private SimpleMeterRegistry meterRegistry;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = newPublisher(meterRegistry);
    }

    @SuppressWarnings("unchecked")
    private OutboxPublisher newPublisher(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        OutboxPublisher created = new OutboxPublisher(repository, kafkaTemplate, TEST_CLOCK, provider);
        ReflectionTestUtils.setField(created, "sendTimeoutMs", 1000L);
        return created;
    }

    private OutboxEvent event(String key) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .topic("security_service.events.v1")
                .recordKey(key)
                .payload("{\"eventId\":\"" + key + "\"}")
                .createdAt(NOW.minusSeconds(60))
                .attempts(2)
                .lastError("previous failure")
                .build();
    }

    private void brokerAcknowledges() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private void brokerFailsWith(Throwable failure) {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(failure));
    }

    private double counter(String name) {
        var found = meterRegistry.find(name).counter();
        return found == null ? 0d : found.count();
    }

    @Test
    @DisplayName("marks the row published only after the broker acknowledges the send")
    void marksPublishedAfterAcknowledgedSend() {
        OutboxEvent row = event("k1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(row));
        brokerAcknowledges();

        publisher.publishPending();

        verify(kafkaTemplate).send("security_service.events.v1", "k1", row.getPayload());
        assertThat(row.getPublishedAt()).isEqualTo(NOW);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();
        verify(repository).save(row);
        assertThat(counter("security.outbox.published")).isEqualTo(1d);
    }

    @Test
    @DisplayName("drains the whole batch in order while the broker keeps acknowledging")
    void drainsWholeBatchWhenAllSendsSucceed() {
        OutboxEvent first = event("k1");
        OutboxEvent second = event("k2");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second));
        brokerAcknowledges();

        publisher.publishPending();

        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isEqualTo(NOW);
        assertThat(counter("security.outbox.published")).isEqualTo(2d);
    }

    @Test
    @DisplayName("on failure: records the attempt and error, leaves the row unpublished, stops the batch")
    void recordsFailureAndStopsBatch() {
        OutboxEvent first = event("k1");
        OutboxEvent second = event("k2");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second));
        brokerFailsWith(new RuntimeException("broker down"));

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(3);
        assertThat(first.getLastError()).contains("broker down");
        verify(repository).save(first);

        // The batch stops at the first failure so publish order is preserved:
        // the second row is never attempted and keeps its prior attempt count.
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
        verify(kafkaTemplate).send("security_service.events.v1", "k1", first.getPayload());
        assertThat(second.getAttempts()).isEqualTo(2);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(counter("security.outbox.publish.failures")).isEqualTo(1d);
    }

    @Test
    @DisplayName("falls back to the exception class name when the failure carries no message")
    void recordsClassNameWhenFailureMessageIsNull() {
        OutboxEvent row = event("k1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(row));
        // Thrown by send() itself rather than completing the future exceptionally:
        // a failed future surfaces as an ExecutionException whose message is the
        // cause's toString, so only a synchronous throw reaches the null-message
        // fallback in recordFailure().
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException());

        publisher.publishPending();

        assertThat(row.getLastError()).isEqualTo("IllegalStateException");
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("truncates an oversized failure message to the 2000-character column bound")
    void truncatesOversizedFailureMessage() {
        OutboxEvent row = event("k1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(row));
        brokerFailsWith(new RuntimeException("x".repeat(2500)));

        publisher.publishPending();

        assertThat(row.getLastError()).hasSize(2000);
    }

    @Test
    @DisplayName("does nothing when the outbox is empty")
    void doesNothingWhenNoPendingRows() {
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of());

        publisher.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("publishes normally when no MeterRegistry is available")
    void publishesWithoutMeterRegistry() {
        OutboxPublisher withoutMetrics = newPublisher(null);
        OutboxEvent row = event("k1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(row));
        brokerAcknowledges();

        withoutMetrics.publishPending();

        assertThat(row.getPublishedAt()).isEqualTo(NOW);
        verify(repository).save(row);
    }
}
