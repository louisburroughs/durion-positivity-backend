package com.positivity.order.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.entity.OutboxEvent;
import com.positivity.order.internal.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the pos-order {@link OutboxPublisher} (ADR-0044 §4).
 *
 * <p>
 * This publisher deliberately diverges from its siblings in catalog, invoice,
 * inventory and the rest: it carries no Micrometer counters, it does not reset
 * {@code attempts} on a successful publish, and it stores the raw exception
 * message without a class-name fallback or length cap. The divergences are safe
 * — {@code last_error} is a {@code TEXT} column, so an unbounded message cannot
 * overflow it — but they are easy to "fix" by accident when someone copies a
 * sibling publisher over this one, so the tests below pin them down explicitly.
 *
 * <p>
 * The shared contract still holds and is what carries the correctness weight: a
 * row is marked published only after the broker acknowledges, and the batch
 * stops at the first failure so a struggling broker cannot reorder events.
 */
@DisplayName("pos-order OutboxPublisher — outbox drain contract")
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TOPIC = "order.events.v1";

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(repository, kafkaTemplate, TEST_CLOCK);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 1000L);
    }

    private OutboxEvent event(String key) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .topic(TOPIC)
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

    @Test
    @DisplayName("marks the row published only after the broker acknowledges the send")
    void marksPublishedAfterAcknowledgedSend() {
        OutboxEvent row = event("k1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(row));
        brokerAcknowledges();

        publisher.publishPending();

        verify(kafkaTemplate).send(TOPIC, "k1", row.getPayload());
        assertThat(row.getPublishedAt()).isEqualTo(NOW);
        assertThat(row.getLastError()).isNull();
        verify(repository).save(row);
    }

    @Test
    @DisplayName("keeps the prior attempt count on success — unlike the sibling publishers, which reset it")
    void doesNotResetAttemptsOnSuccess() {
        OutboxEvent row = event("k1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(row));
        brokerAcknowledges();

        publisher.publishPending();

        assertThat(row.getAttempts()).isEqualTo(2);
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
        verify(kafkaTemplate).send(TOPIC, "k1", first.getPayload());
        assertThat(second.getAttempts()).isEqualTo(2);
        assertThat(second.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("stores a null last_error when the failure carries no message — no class-name fallback here")
    void recordsNullWhenFailureMessageIsNull() {
        OutboxEvent row = event("k1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(row));
        // Thrown by send() itself: a failed future surfaces as an ExecutionException
        // whose message is the cause's toString, so only a synchronous throw
        // produces a null message here.
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException());

        publisher.publishPending();

        assertThat(row.getLastError()).isNull();
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("stores an oversized failure message untruncated — last_error is an unbounded TEXT column")
    void storesOversizedFailureMessageUntruncated() {
        OutboxEvent row = event("k1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(row));
        brokerFailsWith(new RuntimeException("x".repeat(2500)));

        publisher.publishPending();

        assertThat(row.getLastError()).hasSizeGreaterThan(2000);
    }

    @Test
    @DisplayName("does nothing when the outbox is empty")
    void doesNothingWhenNoPendingRows() {
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of());

        publisher.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).save(any());
    }
}
