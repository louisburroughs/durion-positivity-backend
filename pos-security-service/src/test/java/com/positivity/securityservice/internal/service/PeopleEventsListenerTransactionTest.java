package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.securityservice.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.securityservice.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.securityservice.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * which the mock-based {@link PeopleEventsListenerTest} cannot do.
 *
 * <p>Token revocation is a {@code @Transactional} service. An exception leaving it marks the
 * transaction it joined rollback-only, so when the listener method was {@code @Transactional}, a
 * "permanent" failure that the catch-all logged still made the commit after the catch throw
 * {@code UnexpectedRollbackException}: the container retried the record through its back-off ladder
 * and dead-lettered it, and the {@code processed_events} mark was rolled back every time. The
 * enclosing-transaction case below is the one that fails on that shape.
 *
 * <p>Needs real commits, so the class is not {@code @Transactional} and cleans up after itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("PeopleEventsListener transaction shape")
class PeopleEventsListenerTransactionTest {

    private static final UUID ASSIGNMENT_ID = UUID.fromString("01990000-0000-7000-8000-0000000021b1");
    private static final UUID PERSON_ID = UUID.fromString("01990000-0000-7000-8000-0000000021b2");
    private static final UUID LOCATION_ID = UUID.fromString("01990000-0000-7000-8000-0000000021b3");

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ExtStaffingAssignmentReplicaRepository replicaRepository;

    @Autowired
    private FailingTokenRevocation failingRevocation;

    private PeopleEventsListener listener;
    private String eventId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        failingRevocation.reset();
        // An assignment contributing reach today, so the ENDED fact below narrows it and the
        // listener reaches the revocation service.
        replicaRepository.save(ExtStaffingAssignmentReplica.builder()
                .assignmentId(ASSIGNMENT_ID)
                .personId(PERSON_ID)
                .locationId(LOCATION_ID)
                .primary(true)
                .status(ExtStaffingAssignmentReplica.STATUS_ACTIVE)
                .effectiveFrom(LocalDate.now(ZoneOffset.UTC).minusDays(30))
                .aggregateVersion(1L)
                .updatedAt(Instant.now())
                .build());
        listener = new PeopleEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                replicaRepository,
                failingRevocation,
                Mockito.mock(ObjectProvider.class),
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(eventId);
        replicaRepository.deleteById(ASSIGNMENT_ID);
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent failure inside a transactional handler still records the event and does not throw")
    void permanentFailureInsideTransactionalHandlerIsRecordedNotRetried() {
        assertThatCode(() -> listener.onPeopleEvent(endedAssignmentEvent())).doesNotThrowAnyException();

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

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onPeopleEvent(endedAssignmentEvent())))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndEventWasRecorded() {
        assertThat(failingRevocation.sawActiveTransaction())
                .as("the revocation must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the handler's rollback")
                .isTrue();
        assertThat(replicaRepository.findById(ASSIGNMENT_ID))
                .as("the failed handler's replica write rolls back with it")
                .get()
                .extracting(ExtStaffingAssignmentReplica::getStatus)
                .isEqualTo(ExtStaffingAssignmentReplica.STATUS_ACTIVE);
    }

    private String endedAssignmentEvent() {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":2,
                 "payload":{"assignmentId":"%s","employeeId":"%s","personId":"%s","locationId":"%s",
                            "role":"TECHNICIAN","primary":true,"status":"ENDED",
                            "effectiveFrom":"%s","effectiveTo":null}}""".formatted(
                        eventId,
                        StaffingAssignmentUpdatedV1.EVENT_TYPE,
                        ASSIGNMENT_ID,
                        UUID.randomUUID(),
                        PERSON_ID,
                        LOCATION_ID,
                        LocalDate.now(ZoneOffset.UTC).minusDays(30));
    }

    @TestConfiguration
    static class FailingRevocationConfiguration {

        @Bean
        @Primary
        FailingTokenRevocation failingTokenRevocation() {
            return new FailingTokenRevocation();
        }
    }

    /** Mirrors the real revocation service's shape: a {@code @Transactional} service that throws. */
    @Transactional
    static class FailingTokenRevocation implements PersonTokenRevocationService {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        public void reset() {
            sawActiveTransaction.set(false);
        }

        @Override
        public int revokeLiveTokens(@NonNull UUID personId) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("simulated permanent revocation failure");
        }
    }
}
