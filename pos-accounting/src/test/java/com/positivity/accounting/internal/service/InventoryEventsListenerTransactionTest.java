package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.inventory.InventoryAdjustedV1;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins {@link InventoryEventsListener}'s transaction shape (ADR-0044 as amended by #2146, issue
 * #2191) against a real transaction manager, which the module's mock-based listener tests cannot
 * do.
 *
 * <p>The posting service is {@code @Transactional}. An exception leaving it marks the transaction
 * it joined rollback-only, so when the listener method was itself {@code @Transactional}, a
 * permanent failure that was caught and marked still made the commit after the catch throw {@code
 * UnexpectedRollbackException}; the record went round the container's retry ladder to the DLQ and
 * the mark was rolled back on every attempt. Each case runs bare and inside an enclosing
 * transaction (the old listener shape).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("InventoryEventsListener transaction shape")
class InventoryEventsListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    @Autowired
    private InventoryShrinkagePostingService shrinkagePostingService;

    @Autowired
    private InventoryFactIngestionRecorder ingestionRecorder;

    @Autowired
    private FailingAdjustmentPostingService failingPostingService;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    private InventoryEventsListener listener;
    private String eventId;
    private UUID adjustmentId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        adjustmentId = UUID.randomUUID();
        failingPostingService.reset();
        listener = new InventoryEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                shrinkagePostingService,
                failingPostingService,
                ingestionRecorder,
                meterRegistry,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(eventId);
        accountingEventRepository.deleteAll(records());
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent failure inside the transactional posting service is recorded and does not throw")
    void permanentFailureIsRecordedNotRetried() {
        failingPostingService.failWith(DatabindException.from((JsonParser) null, "simulated permanent failure"));

        assertThatCode(() -> listener.onInventoryEvent(adjustment("7.25"))).doesNotThrowAnyException();

        assertPermanentFailureRecorded();
    }

    /**
     * The shape the defect had: the whole event inside one enclosing transaction, as the old
     * {@code @Transactional} listener method ran it. The service's failure must not mark that
     * transaction rollback-only, or its commit throws {@code UnexpectedRollbackException}.
     */
    @Test
    @DisplayName("Inside an enclosing transaction, the permanent failure does not poison its commit")
    void permanentFailureDoesNotPoisonAnEnclosingTransaction() {
        failingPostingService.failWith(DatabindException.from((JsonParser) null, "simulated permanent failure"));

        assertThatCode(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(_ -> listener.onInventoryEvent(adjustment("7.25"))))
                .doesNotThrowAnyException();

        assertPermanentFailureRecorded();
    }

    @Test
    @DisplayName("A propagating failure (e.g. PERIOD_CLOSED) surfaces as itself and leaves no mark")
    void propagatingFailureSurfacesUnmarked() {
        failingPostingService.failWith(new IllegalStateException("simulated PERIOD_CLOSED"));

        assertThatThrownBy(() -> listener.onInventoryEvent(adjustment("7.25")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class);

        assertPropagatingFailureUnmarked();
    }

    @Test
    @DisplayName("Inside an enclosing transaction, a propagating failure still surfaces as itself, unmarked")
    void propagatingFailureInsideEnclosingTransactionSurfacesUnmarked() {
        failingPostingService.failWith(new IllegalStateException("simulated PERIOD_CLOSED"));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(_ -> listener.onInventoryEvent(adjustment("7.25"))))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertPropagatingFailureUnmarked();
    }

    /**
     * The uncosted skip commits its SKIPPED record and mark in a transaction of its own: they survive
     * even when the enclosing transaction rolls back.
     */
    @Test
    @DisplayName("The uncosted skip commits its record and mark in its own transaction")
    void uncostedSkipCommitsInItsOwnTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            listener.onInventoryEvent(adjustment("null"));
            status.setRollbackOnly();
        });

        assertThat(failingPostingService.invoked()).isFalse();
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(AccountingEventStatus.SKIPPED);
            assertThat(record.getFailureReasonCode()).isEqualTo("UNCOSTED_FACT");
        });
    }

    private void assertPermanentFailureRecorded() {
        assertThat(failingPostingService.sawActiveTransaction())
                .as("the service must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the handler's rollback")
                .isTrue();
        assertThat(records())
                .as("the failed handler's ingestion record rolls back with it")
                .isEmpty();
    }

    private void assertPropagatingFailureUnmarked() {
        assertThat(failingPostingService.sawActiveTransaction()).isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("a propagated failure is retried, so it must not be marked")
                .isFalse();
        assertThat(records()).isEmpty();
    }

    private List<AccountingEvent> records() {
        return accountingEventRepository.findAll().stream()
                .filter(record -> adjustmentId.toString().equals(record.getDomainKeyId()))
                .toList();
    }

    private String adjustment(String unitCost) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s",
                 "payload":{"adjustmentId":"%s","adjustmentKind":"CYCLE_COUNT","ledgerEventType":"COUNT_VARIANCE_OUT",
                            "ledgerEntryId":"%s","sku":"SKU-1","locationId":null,"taskId":null,
                            "reasonCode":"COUNT_ERROR","quantityDelta":-2,"unitCost":%s,
                            "costSource":"AVERAGE","occurredAt":"2026-07-21T09:15:00Z"}}
                """.formatted(
                eventId, InventoryAdjustedV1.EVENT_TYPE, adjustmentId, adjustmentId, UUID.randomUUID(), unitCost);
    }

    @TestConfiguration
    static class FailingServiceConfiguration {

        @Bean
        @Primary
        FailingAdjustmentPostingService failingAdjustmentPostingService() {
            return new FailingAdjustmentPostingService();
        }
    }

    /** Mirrors the real service's shape: a {@code @Transactional} posting that throws. */
    static class FailingAdjustmentPostingService extends InventoryAdjustmentPostingService {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);
        private final AtomicBoolean invoked = new AtomicBoolean(false);
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        FailingAdjustmentPostingService() {
            super(null, null, null, null, null);
        }

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        public boolean invoked() {
            return invoked.get();
        }

        public void failWith(RuntimeException exception) {
            failure.set(exception);
        }

        public void reset() {
            sawActiveTransaction.set(false);
            invoked.set(false);
            failure.set(new IllegalStateException("simulated failure"));
        }

        @Override
        @Transactional
        public @Nullable UUID postAdjustment(@NonNull InventoryAdjustedV1 fact) {
            invoked.set(true);
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw failure.get();
        }
    }
}
