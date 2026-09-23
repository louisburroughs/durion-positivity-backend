package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import com.positivity.warranty.internal.repository.ExtWorkorderLineReplicaRepository;
import com.positivity.warranty.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins {@link WorkorderEventsListener}'s transaction shape (#2146) against a real transaction
 * manager, which the mock-based {@link WorkorderEventsListenerTest} cannot do.
 *
 * <p>The handler ends in {@link AutoRegistrationService#registerFromWorkorderSale}, a
 * {@code @Transactional} service. An exception leaving it marks the transaction it joined
 * rollback-only, so when the listener ran every event inside its own {@code @Transactional}, a
 * "permanent" failure that was caught and logged still made the commit after the catch throw
 * {@code UnexpectedRollbackException}; the container retried the record and dead-lettered it, and
 * the {@code processed_events} mark was rolled back on every attempt.
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
    private ExtWorkorderReplicaRepository extWorkorderReplicaRepository;

    @Autowired
    private ExtWorkorderLineReplicaRepository extWorkorderLineReplicaRepository;

    @Autowired
    private FailingAutoRegistrationService failingAutoRegistration;

    private WorkorderEventsListener listener;
    private String eventId;
    private UUID workorderId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        workorderId = UUID.randomUUID();
        failingAutoRegistration.reset();
        listener = new WorkorderEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                failingAutoRegistration,
                extWorkorderReplicaRepository,
                extWorkorderLineReplicaRepository,
                mock(ObjectProvider.class),
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
        assertThatCode(() -> listener.onWorkorderEvent(workorderUpdated())).doesNotThrowAnyException();

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

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onWorkorderEvent(workorderUpdated())))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndEventWasRecorded() {
        assertThat(failingAutoRegistration.sawActiveTransaction())
                .as("the handler must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the handler's rollback")
                .isTrue();
        assertThat(extWorkorderReplicaRepository.existsById(workorderId))
                .as("the failed handler's own writes must roll back")
                .isFalse();
    }

    private String workorderUpdated() {
        return """
                {"eventId":"%s","eventType":"workorder.workorder.updated","aggregateVersion":1,
                 "payload":{"workorderId":"%s","workorderNumber":"WO-1","status":"COMPLETED",
                            "customerId":"%s","invoiceId":"%s","parts":[],"services":[]}}
                """.formatted(eventId, workorderId, UUID.randomUUID(), UUID.randomUUID());
    }

    @TestConfiguration
    static class FailingAutoRegistrationConfiguration {

        @Bean
        @Primary
        FailingAutoRegistrationService failingAutoRegistrationService() {
            return new FailingAutoRegistrationService();
        }
    }

    /** Mirrors the real service's shape: a {@code @Transactional} registration that throws. */
    @Transactional
    static class FailingAutoRegistrationService implements AutoRegistrationService {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        public void reset() {
            sawActiveTransaction.set(false);
        }

        @Override
        public int registerFromWorkorderSale(@NonNull WorkorderUpdatedV1 event) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("simulated permanent failure");
        }
    }
}
