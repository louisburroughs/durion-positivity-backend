package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.domainevents.accounting.InvoiceGlPostedV1;
import com.positivity.invoice.internal.dto.FinalizationEligibilityResult;
import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the listener's transaction shape against a real transaction manager, which the mock-based
 * {@link AccountingEventsListenerTest} cannot do (#2146).
 *
 * <p>{@code markPosted} is a {@code @Transactional} service method. An exception leaving it marks
 * the transaction it joined rollback-only, so when the listener method was {@code @Transactional}
 * a "permanent" failure that was caught and logged still made the commit after the catch throw
 * {@code UnexpectedRollbackException}; the container then retried the record through its back-off
 * ladder before dead-lettering it, and the {@code processed_events} mark was rolled back on every
 * attempt.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AccountingEventsListener transaction shape")
class AccountingEventsListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    private FailingFinalizationService failingService;
    private AccountingEventsListener listener;
    private String eventId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        failingService = new FailingFinalizationService();
        listener = new AccountingEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                transactionalProxy(failingService),
                meterRegistry,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(eventId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent failure inside a transactional handler still records the event and does not throw")
    void permanentFailureInsideTransactionalHandlerIsRecordedNotRetried() {
        assertThatCode(() -> listener.onAccountingEvent(glPosted(eventId))).doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    /**
     * The shape the defect had: the whole event inside one enclosing transaction, as a
     * {@code @Transactional} listener method would run it. The handler's failure must not mark that
     * transaction rollback-only, or its commit throws {@code UnexpectedRollbackException} and the
     * container retries the record.
     */
    @Test
    @DisplayName("Inside an enclosing transaction, the handler's failure does not poison its commit")
    void permanentFailureDoesNotPoisonAnEnclosingTransaction() {
        TransactionTemplate enclosing = new TransactionTemplate(transactionManager);

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onAccountingEvent(glPosted(eventId))))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndEventWasRecorded() {
        assertThat(failingService.sawActiveTransaction())
                .as("the handler must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the handler's rollback")
                .isTrue();
    }

    private static String glPosted(String eventId) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":1,
                 "payload":{"invoiceId":"%s","journalEntryId":"%s","postingKind":"POSTED",
                   "finalizedAt":"2026-09-23T09:00:00Z","postedAt":"2026-09-23T09:00:03Z",
                   "reversedJournalEntryId":null}}
                """.formatted(eventId, InvoiceGlPostedV1.EVENT_TYPE, UUID.randomUUID(), UUID.randomUUID());
    }

    /** Applies {@code @Transactional} exactly as the container would, without replacing a context bean. */
    private InvoiceFinalizationService transactionalProxy(InvoiceFinalizationService target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (InvoiceFinalizationService) factory.getProxy();
    }

    /** Mirrors the real service's shape: a {@code @Transactional} {@code markPosted} that throws. */
    @Transactional
    static class FailingFinalizationService implements InvoiceFinalizationService {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        @Override
        public void markPosted(@NonNull UUID invoiceId, @NonNull UUID glEntryId, @NonNull Instant finalizedAt) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("simulated permanent business failure");
        }

        @Override
        public @NonNull FinalizationEligibilityResult checkEligibility(@NonNull UUID invoiceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull InvoiceDetailsResponse completeInvoice(
                @NonNull UUID invoiceId, @NonNull FinalizationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull InvoiceDetailsResponse revert(
                @NonNull UUID invoiceId, @NonNull String managerApprovalCode, @NonNull String reason) {
            throw new UnsupportedOperationException();
        }
    }
}
