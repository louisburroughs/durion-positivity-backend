package com.positivity.warranty.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import com.positivity.tenancy.testing.TenantTestSupport;
import com.positivity.warranty.internal.entity.OutboxEvent;
import com.positivity.warranty.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Outbox drain contract (ADR-0044 §4, at-least-once): a row is marked published only after the
 * broker acknowledges, rows are processed in id order, the batch stops at the first failure so
 * a struggling broker cannot reorder events, and failures record {@code attempts}/{@code
 * lastError} for alerting.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TOPIC = "warranty.events.v1";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterRegistry = mock(ObjectProvider.class);
        when(meterRegistry.getIfAvailable()).thenReturn(null);
        publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, CLOCK, meterRegistry);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 1_000L);
    }

    @AfterEach
    void clearInterruptFlag() {
        // The InterruptedException test re-sets the thread's interrupt flag on purpose.
        Thread.interrupted();
    }

    private static OutboxEvent event(String key, int attempts, String lastError) {
        return OutboxEvent.builder()
                .tenantId(TenantTestSupport.TENANT_A)
                .id(UUID.randomUUID())
                .topic(TOPIC)
                .recordKey(key)
                .payload("{\"eventId\":\"" + key + "\"}")
                .createdAt(NOW.minusSeconds(60))
                .attempts(attempts)
                .lastError(lastError)
                .build();
    }

    /**
     * Stubs the broker per record key: the publisher sends one {@link ProducerRecord} per row, so
     * the stub answers by the record's key and the assertions read the captured records back (topic,
     * key, payload and the producing tenant on the {@code tenantId} header, ADR-0062 §3).
     */
    @SafeVarargs
    private final void brokerAnswers(Map.Entry<String, CompletableFuture<SendResult<String, String>>>... byKey) {
        Map<String, CompletableFuture<SendResult<String, String>>> outcomes = Map.ofEntries(byKey);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            CompletableFuture<SendResult<String, String>> outcome = outcomes.get(record.key());
            if (outcome == null) {
                throw new AssertionError("unexpected send for key " + record.key());
            }
            return outcome;
        });
    }

    private List<ProducerRecord<String, String>> sentRecords() {
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.captor();
        verify(kafkaTemplate, atLeast(0)).send(sent.capture());
        return sent.getAllValues();
    }

    private static void assertSent(ProducerRecord<String, String> record, OutboxEvent event) {
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(event.getRecordKey());
        assertThat(record.value()).isEqualTo(event.getPayload());
        assertThat(TenantKafkaHeaders.read(record.headers()))
                .as("the row's tenant travels on the record header (ADR-0062)")
                .contains(TenantTestSupport.TENANT_A);
    }

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    @Test
    void marksRowsPublishedInReturnedOrderAfterBrokerAckAndResetsFailureBookkeeping() {
        OutboxEvent first = event("k1", 3, "previous broker outage");
        OutboxEvent second = event("k2", 0, null);
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second));
        brokerAnswers(Map.entry("k1", acked()), Map.entry("k2", acked()));

        publisher.publishPending();

        List<ProducerRecord<String, String>> sent = sentRecords();
        assertThat(sent).hasSize(2);
        assertSent(sent.get(0), first);
        assertSent(sent.get(1), second);
        InOrder inOrder = Mockito.inOrder(outboxEventRepository);
        inOrder.verify(outboxEventRepository).save(first);
        inOrder.verify(outboxEventRepository).save(second);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(first.getAttempts()).isZero();
        assertThat(first.getLastError()).isNull();
        assertThat(second.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void failedSendRecordsAttemptsAndLastErrorAndNeverMarksPublished() {
        OutboxEvent failing = event("k1", 4, null);
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(failing));
        brokerAnswers(Map.entry("k1", CompletableFuture.failedFuture(new RuntimeException("broker unavailable"))));

        publisher.publishPending();

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.captor();
        verify(outboxEventRepository).save(saved.capture());
        assertThat(saved.getValue().getPublishedAt()).isNull();
        assertThat(saved.getValue().getAttempts()).isEqualTo(5);
        assertThat(saved.getValue().getLastError()).contains("broker unavailable");
    }

    @Test
    void firstFailureStopsTheBatchSoOrderIsPreserved() {
        OutboxEvent first = event("k1", 0, null);
        OutboxEvent second = event("k2", 0, null);
        OutboxEvent third = event("k3", 0, null);
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(first, second, third));
        brokerAnswers(
                Map.entry("k1", acked()),
                Map.entry("k2", CompletableFuture.failedFuture(new TimeoutException("send timed out"))));

        publisher.publishPending();

        // Row three is untouched: not sent, not saved — it retries behind row two next poll.
        List<ProducerRecord<String, String>> sent = sentRecords();
        assertThat(sent).extracting(ProducerRecord::key).containsExactly("k1", "k2");
        verify(outboxEventRepository).save(first);
        verify(outboxEventRepository).save(second);
        verify(outboxEventRepository, never()).save(third);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).isNotBlank();
        assertThat(third.getPublishedAt()).isNull();
        assertThat(third.getAttempts()).isZero();
    }

    @Test
    void lastErrorIsTruncatedToTwoThousandCharacters() {
        OutboxEvent failing = event("k1", 0, null);
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(failing));
        brokerAnswers(Map.entry("k1", CompletableFuture.failedFuture(new RuntimeException("x".repeat(3_000)))));

        publisher.publishPending();

        assertThat(failing.getLastError()).hasSize(2_000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptedSendRestoresTheInterruptFlagAndRecordsTheFailure() throws Exception {
        OutboxEvent first = event("k1", 0, null);
        OutboxEvent second = event("k2", 0, null);
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second));
        CompletableFuture<SendResult<String, String>> interrupted = mock(CompletableFuture.class);
        when(interrupted.get(anyLong(), any())).thenThrow(new InterruptedException("shutdown"));
        brokerAnswers(Map.entry("k1", interrupted));

        publisher.publishPending();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(1);
        verify(outboxEventRepository).save(first);
        // Batch stops: the second row is neither sent nor saved.
        assertThat(sentRecords()).extracting(ProducerRecord::key).containsExactly("k1");
        verify(outboxEventRepository).findTop100ByPublishedAtIsNullOrderByIdAsc();
        verifyNoMoreInteractions(outboxEventRepository);
    }
}
