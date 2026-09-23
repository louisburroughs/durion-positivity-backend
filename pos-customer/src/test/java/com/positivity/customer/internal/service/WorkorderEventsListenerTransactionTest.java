package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.customer.internal.entity.CustomerInteraction;
import com.positivity.customer.internal.repository.CustomerInteractionRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.PartyNoteRepository;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the listener's transaction shape against a real transaction manager, which the mock-based
 * {@link WorkorderEventsListenerTest} cannot do (#2146).
 *
 * <p>The note projection writes {@code party_note} through a Spring Data repository and then calls
 * {@code CustomerInteractionServiceImpl.ingest}, both transactional. An exception leaving either
 * marks the transaction it joined rollback-only, so when the listener method was
 * {@code @Transactional} a "permanent" failure that was caught and logged still made the commit
 * after the catch throw {@code UnexpectedRollbackException}; the container retried the record
 * through its back-off ladder before dead-lettering it, and the {@code processed_events} mark was
 * rolled back on every attempt.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("WorkorderEventsListener transaction shape")
class WorkorderEventsListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ServiceHistoryRepository serviceHistoryRepository;

    @Autowired
    private FollowUpTaskRepository followUpTaskRepository;

    @Autowired
    private PartyNoteRepository partyNoteRepository;

    @Autowired
    private CustomerInteractionRepository customerInteractionRepository;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    private FailingInteractionService failingService;
    private WorkorderEventsListener listener;
    private String eventId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        failingService = new FailingInteractionService(customerInteractionRepository);
        listener = new WorkorderEventsListener(
                Clock.systemUTC(),
                JsonMapper.builder().findAndAddModules().build(),
                processedEventRepository,
                serviceHistoryRepository,
                followUpTaskRepository,
                partyNoteRepository,
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
        assertThatCode(() -> listener.onWorkorderEvent(noteEvent(eventId))).doesNotThrowAnyException();

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

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onWorkorderEvent(noteEvent(eventId))))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndEventWasRecorded() {
        assertThat(failingService.sawActiveTransaction())
                .as("the handler must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(partyNoteRepository.existsBySourceEventId(eventId))
                .as("the handler's own write must roll back with it")
                .isFalse();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the handler's rollback")
                .isTrue();
    }

    private static String noteEvent(String eventId) {
        return """
                {"eventId":"%s","eventType":"workorder.note.added.v1","sourceService":"pos-workorder",
                 "payload":{"workorderId":"%s","noteId":"%s","partyId":"%s","noteType":"CUSTOMER_REQUEST",
                            "noteText":"Rattle on a cold start.","addedAt":"2026-09-23T11:00:00Z"}}
                """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    /** Applies {@code @Transactional} exactly as the container would, without replacing a context bean. */
    private CustomerInteractionServiceImpl transactionalProxy(CustomerInteractionServiceImpl target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (CustomerInteractionServiceImpl) factory.getProxy();
    }

    /** Mirrors the real service's shape: a {@code @Transactional} {@code ingest} that throws. */
    static class FailingInteractionService extends CustomerInteractionServiceImpl {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        FailingInteractionService(CustomerInteractionRepository interactionRepository) {
            super(Clock.systemUTC(), interactionRepository);
        }

        boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        @Override
        @Transactional
        public boolean ingest(@NonNull CustomerInteraction interaction) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("simulated permanent business failure");
        }
    }
}
