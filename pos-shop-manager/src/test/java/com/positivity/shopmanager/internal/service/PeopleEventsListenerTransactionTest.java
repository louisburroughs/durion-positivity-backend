package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.shopmanager.internal.repository.ExtPersonCredentialReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
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
import org.mockito.Mockito;
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
 * Pins {@link PeopleEventsListener}'s transaction shape (#2146) against a real transaction manager,
 * which the mock-based listener tests cannot do.
 *
 * <p>The mechanic sync is a {@code @Transactional} service. An exception leaving it marks the
 * transaction it joined rollback-only, so when the listener method was {@code @Transactional}, a
 * "permanent" failure that the catch-all logged still made the commit after the catch throw
 * {@code UnexpectedRollbackException}: the container retried the record through its back-off ladder
 * and dead-lettered it, and the {@code processed_events} mark was rolled back every time. The
 * enclosing-transaction case below is the one that fails on that shape.
 *
 * <p>Needs real commits, so the class is not {@code @Transactional} and cleans up after itself.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PeopleEventsListener transaction shape")
class PeopleEventsListenerTransactionTest {

    private static final UUID ASSIGNMENT_ID = UUID.fromString("01990000-0000-7000-8000-0000000021a1");

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository;

    @Autowired
    private ExtPersonReplicaRepository personReplicaRepository;

    @Autowired
    private ExtPersonCredentialReplicaRepository credentialReplicaRepository;

    @Autowired
    private FailingMechanicSyncService failingMechanicSync;

    private PeopleEventsListener listener;
    private String eventId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        failingMechanicSync.reset();
        listener = new PeopleEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                assignmentReplicaRepository,
                failingMechanicSync,
                personReplicaRepository,
                credentialReplicaRepository,
                Mockito.mock(ObjectProvider.class),
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(eventId);
        assignmentReplicaRepository.deleteById(ASSIGNMENT_ID);
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent failure inside a transactional handler still records the event and does not throw")
    void permanentFailureInsideTransactionalHandlerIsRecordedNotRetried() {
        assertThatCode(() -> listener.onPeopleEvent(technicianAssignmentEvent()))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    /**
     * The shape the defect had: the whole event inside one enclosing transaction, as the
     * {@code @Transactional} listener method ran it. The handler's failure must not mark that
     * transaction rollback-only, or its commit throws {@code UnexpectedRollbackException} and the
     * container retries the record.
     */
    @Test
    @DisplayName("Inside an enclosing transaction, the handler's failure does not poison its commit")
    void permanentFailureDoesNotPoisonAnEnclosingTransaction() {
        TransactionTemplate enclosing = new TransactionTemplate(transactionManager);

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onPeopleEvent(technicianAssignmentEvent())))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndEventWasRecorded() {
        assertThat(failingMechanicSync.sawActiveTransaction())
                .as("the mechanic sync must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the handler's rollback")
                .isTrue();
        assertThat(assignmentReplicaRepository.existsById(ASSIGNMENT_ID))
                .as("the failed handler's replica write rolls back with it")
                .isFalse();
    }

    private String technicianAssignmentEvent() {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":1,
                 "payload":{"assignmentId":"%s","employeeId":"%s","personId":"%s","locationId":"%s",
                            "role":"TECHNICIAN","primary":true,"status":"ACTIVE",
                            "effectiveFrom":"2026-02-01","effectiveTo":null}}""".formatted(
                        eventId,
                        StaffingAssignmentUpdatedV1.EVENT_TYPE,
                        ASSIGNMENT_ID,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID());
    }

    @TestConfiguration
    static class FailingMechanicSyncConfiguration {

        @Bean
        @Primary
        FailingMechanicSyncService failingMechanicSyncService() {
            return new FailingMechanicSyncService();
        }
    }

    /** Mirrors the real sync's shape: a {@code @Transactional} service that throws. */
    @Transactional
    static class FailingMechanicSyncService implements MechanicSyncService {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        public void reset() {
            sawActiveTransaction.set(false);
        }

        @Override
        public void processHrEvent(@NonNull HrMechanicEvent event) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("simulated permanent mechanic sync failure");
        }

        @Override
        public void reconcileFromHr() {
            // Not exercised.
        }
    }
}
