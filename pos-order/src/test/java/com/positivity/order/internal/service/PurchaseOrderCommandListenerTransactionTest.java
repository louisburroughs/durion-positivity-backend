package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.domainevents.order.PurchaseOrderRequestedV1;
import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the listener's transaction shape against a real transaction manager, which the mock-based
 * listener tests cannot do (#2146).
 *
 * <p>{@code createRequested} is a {@code @Transactional} service method. An exception leaving it
 * marks the transaction it joined rollback-only, so when the listener ran every command inside its
 * own {@code @Transactional}, a "permanent" failure that was caught and logged still made the
 * commit after the catch throw {@code UnexpectedRollbackException}; the container then retried the
 * record through its whole back-off ladder before dead-lettering it, and the
 * {@code processed_events} mark was rolled back on every attempt.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PurchaseOrderCommandListener transaction shape")
class PurchaseOrderCommandListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private FailingPurchaseOrderService failingService;

    private PurchaseOrderCommandListener listener;
    private String eventId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        listener = new PurchaseOrderCommandListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                purchaseOrderRepository,
                failingService,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(eventId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent failure inside the transactional service still records the command and does not throw")
    void permanentFailureInsideTransactionalServiceIsRecordedNotRetried() {
        assertThatCode(() -> listener.onOrderCommand(purchaseOrderRequested(eventId)))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndCommandWasRecorded();
    }

    /**
     * The shape the defect had: the whole command inside one enclosing transaction, as a
     * {@code @Transactional} listener method would run it. The service's failure must not mark that
     * transaction rollback-only, or its commit throws {@code UnexpectedRollbackException} and the
     * container retries the record.
     */
    @Test
    @DisplayName("Inside an enclosing transaction, the service's failure does not poison its commit")
    void permanentFailureDoesNotPoisonAnEnclosingTransaction() {
        TransactionTemplate enclosing = new TransactionTemplate(transactionManager);

        assertThatCode(() ->
                        enclosing.executeWithoutResult(_ -> listener.onOrderCommand(purchaseOrderRequested(eventId))))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndCommandWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndCommandWasRecorded() {
        assertThat(failingService.sawActiveTransaction())
                .as("the service must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the service's rollback")
                .isTrue();
    }

    private static String purchaseOrderRequested(String eventId) {
        return """
                {"eventType":"%s","eventId":"%s",
                 "payload":{"purchaseOrderId":"%s","vendorId":"%s","currency":"USD",
                            "requestedAt":"2026-09-23T12:00:00Z","lines":[]}}
                """.formatted(PurchaseOrderRequestedV1.EVENT_TYPE, eventId, UUID.randomUUID(), UUID.randomUUID());
    }

    @TestConfiguration
    static class FailingServiceConfiguration {

        @Bean
        @Primary
        FailingPurchaseOrderService failingPurchaseOrderService() {
            return new FailingPurchaseOrderService();
        }
    }

    /**
     * Mirrors the real service's shape: a {@code @Transactional} {@code createRequested} that throws.
     * The collaborators are never touched, so the superclass is given none.
     */
    static class FailingPurchaseOrderService extends PurchaseOrderServiceImpl {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        FailingPurchaseOrderService() {
            super(null, null, null, null, null, null, null);
        }

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        @Override
        @Transactional
        void createRequested(
                @NonNull UUID purchaseOrderId, @NonNull CreatePurchaseOrderRequest request, @NonNull String actorId) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("simulated permanent business failure");
        }
    }
}
