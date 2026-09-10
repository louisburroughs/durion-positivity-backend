package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * The at-least-once outbox drain (ADR-0044 §4). The two properties that matter most are that a
 * row is marked published only once Kafka has acknowledged it, and that a batch stops at its
 * first failure rather than reordering a PRICAT completion event ahead of a stuck chunk.
 */
@DisplayName("SupplierOutboxPublisher — draining supplier_event_outbox to Kafka")
class SupplierOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    private final SupplierOutboxEventRepository repository = mock(SupplierOutboxEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void clearInterruptFlag() {
        // The InterruptedException test below deliberately sets this; leaving it set would corrupt
        // whichever test runs next on this thread.
        Thread.interrupted();
    }

    private static SupplierOutboxEventEntity pendingEvent(String topic) {
        return SupplierOutboxEventEntity.builder()
                .tenantId(TenantTestSupport.TENANT_A)
                .topic(topic)
                .recordKey("key-1")
                .eventType("SUPPLIER_TEST_EVENT")
                .payload("{}")
                .attempts(0)
                .build();
    }

    /**
     * Stubs the broker per topic: the publisher sends one {@link ProducerRecord} per row, so the
     * stub answers by the record's topic and the assertions read the captured records back
     * (topic, key, payload and the producing tenant on the {@code tenantId} header, ADR-0062 §3).
     */
    @SafeVarargs
    private final void brokerAnswers(Map.Entry<String, CompletableFuture<SendResult<String, String>>>... byTopic) {
        Map<String, CompletableFuture<SendResult<String, String>>> outcomes = Map.ofEntries(byTopic);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            CompletableFuture<SendResult<String, String>> outcome = outcomes.get(record.topic());
            if (outcome == null) {
                throw new AssertionError("unexpected send to topic " + record.topic());
            }
            return outcome;
        });
    }

    private List<ProducerRecord<String, String>> sentRecords() {
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.captor();
        verify(kafkaTemplate, atLeast(0)).send(sent.capture());
        return sent.getAllValues();
    }

    private static void assertSent(ProducerRecord<String, String> record, String topic) {
        assertThat(record.topic()).isEqualTo(topic);
        assertThat(record.key()).isEqualTo("key-1");
        assertThat(record.value()).isEqualTo("{}");
        assertThat(TenantKafkaHeaders.read(record.headers()))
                .as("the producing tenant rides on the record header (ADR-0062)")
                .contains(TenantTestSupport.TENANT_A);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        return CompletableFuture.completedFuture((SendResult<String, String>) mock(SendResult.class));
    }

    @Test
    @DisplayName("an empty outbox does nothing: no send, no save")
    void emptyOutboxPublishesNothing() {
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of());
        SupplierOutboxPublisher publisher =
                new SupplierOutboxPublisher(repository, kafkaTemplate, clock, noMeterRegistry());

        publisher.publishPending();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a successful send marks the row published at the clock's instant and resets attempts/lastError")
    void successfulSendMarksRowPublished() {
        SupplierOutboxEventEntity event = pendingEvent("supplier.events.v1");
        event.setAttempts(2);
        event.setLastError("previous failure");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(event));
        brokerAnswers(Map.entry("supplier.events.v1", acknowledged()));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SupplierOutboxPublisher publisher =
                new SupplierOutboxPublisher(repository, kafkaTemplate, clock, provider(registry));

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getLastError()).isNull();
        verify(repository).save(event);
        assertThat(registry.get("supplier.outbox.published").counter().count()).isEqualTo(1.0);
        List<ProducerRecord<String, String>> sent = sentRecords();
        assertThat(sent).hasSize(1);
        assertSent(sent.get(0), "supplier.events.v1");
    }

    @Test
    @DisplayName("a batch of several rows all publishes in order when every send succeeds")
    void publishesEveryPendingRowInOrder() {
        SupplierOutboxEventEntity first = pendingEvent("topic-a");
        SupplierOutboxEventEntity second = pendingEvent("topic-b");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second));
        brokerAnswers(Map.entry("topic-a", acknowledged()), Map.entry("topic-b", acknowledged()));
        SupplierOutboxPublisher publisher =
                new SupplierOutboxPublisher(repository, kafkaTemplate, clock, noMeterRegistry());

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isEqualTo(NOW);
        // times(2) alone would not catch topic-b going out before topic-a -- pin the exact
        // sequence with InOrder, which is the property "in order" actually promises.
        List<ProducerRecord<String, String>> sent = sentRecords();
        assertThat(sent).hasSize(2);
        assertSent(sent.get(0), "topic-a");
        assertSent(sent.get(1), "topic-b");
    }

    @Test
    @DisplayName("a broker failure records the attempt and error, but does not mark the row published")
    void brokerFailureRecordsAttemptAndError() {
        SupplierOutboxEventEntity event = pendingEvent("supplier.events.v1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        brokerAnswers(Map.entry("supplier.events.v1", failed));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SupplierOutboxPublisher publisher =
                new SupplierOutboxPublisher(repository, kafkaTemplate, clock, provider(registry));

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("broker unavailable");
        verify(repository).save(event);
        assertThat(registry.get("supplier.outbox.publish.failures").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a batch stops at the first failure, so a later event never overtakes a stuck one")
    void batchStopsAtFirstFailure() {
        SupplierOutboxEventEntity stuck = pendingEvent("topic-a");
        SupplierOutboxEventEntity later = pendingEvent("topic-b");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(stuck, later));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        brokerAnswers(Map.entry("topic-a", failed));
        SupplierOutboxPublisher publisher =
                new SupplierOutboxPublisher(repository, kafkaTemplate, clock, noMeterRegistry());

        publisher.publishPending();

        List<ProducerRecord<String, String>> sent = sentRecords();
        assertThat(sent).as("the batch stops at the stuck row").hasSize(1);
        assertSent(sent.get(0), "topic-a");
        assertThat(later.getPublishedAt()).isNull();
        assertThat(later.getAttempts()).isZero();
    }

    @Test
    @DisplayName("an error message over 2000 characters is truncated, not stored unbounded")
    void longErrorMessageIsTruncated() {
        SupplierOutboxEventEntity event = pendingEvent("supplier.events.v1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(event));
        String longMessage = "x".repeat(2500);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException(longMessage));
        brokerAnswers(Map.entry("supplier.events.v1", failed));
        SupplierOutboxPublisher publisher =
                new SupplierOutboxPublisher(repository, kafkaTemplate, clock, noMeterRegistry());

        publisher.publishPending();

        assertThat(event.getLastError()).hasSize(2000);
    }

    @Test
    @DisplayName("an exception with no message (a bare send timeout) falls back to its class's simple name")
    void exceptionWithNoMessageFallsBackToClassName() {
        // Future.get() wraps a completeExceptionally cause in ExecutionException, whose own
        // message is the cause's toString() -- never null. The one checked exception `.get()`
        // throws un-wrapped with no message by default is a genuine send timeout, so that is what
        // exercises the "no message" branch here rather than an unreachable completeExceptionally
        // case.
        SupplierOutboxEventEntity event = pendingEvent("supplier.events.v1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> timingOut = new CompletableFuture<>() {
            @Override
            public SendResult<String, String> get(long timeout, TimeUnit unit)
                    throws java.util.concurrent.TimeoutException {
                throw new java.util.concurrent.TimeoutException();
            }
        };
        brokerAnswers(Map.entry("supplier.events.v1", timingOut));
        SupplierOutboxPublisher publisher =
                new SupplierOutboxPublisher(repository, kafkaTemplate, clock, noMeterRegistry());

        publisher.publishPending();

        assertThat(event.getLastError()).isEqualTo("TimeoutException");
    }

    @Test
    @DisplayName("an interrupted send restores the thread's interrupt flag and records the failure")
    void interruptedSendRestoresInterruptFlagAndRecordsFailure() {
        SupplierOutboxEventEntity event = pendingEvent("supplier.events.v1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> interrupting = new CompletableFuture<>() {
            @Override
            public SendResult<String, String> get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("interrupted mid-send");
            }
        };
        brokerAnswers(Map.entry("supplier.events.v1", interrupting));
        SupplierOutboxPublisher publisher =
                new SupplierOutboxPublisher(repository, kafkaTemplate, clock, noMeterRegistry());

        publisher.publishPending();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("interrupted mid-send");
    }

    @Test
    @DisplayName("with no MeterRegistry bean available, counters stay null and publishing still works")
    void worksWithNoMeterRegistryAvailable() {
        SupplierOutboxEventEntity event = pendingEvent("supplier.events.v1");
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(event));
        brokerAnswers(Map.entry("supplier.events.v1", acknowledged()));
        SupplierOutboxPublisher publisher =
                new SupplierOutboxPublisher(repository, kafkaTemplate, clock, noMeterRegistry());

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isEqualTo(NOW);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> noMeterRegistry() {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider(
            io.micrometer.core.instrument.MeterRegistry registry) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }
}
