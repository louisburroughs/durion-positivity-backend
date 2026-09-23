package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.people.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.people.internal.repository.ExtLocationReplicaRepository;
import com.positivity.people.internal.repository.ProcessedEventRepository;
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
 * Pins {@link LocationEventsListener}'s transaction shape (#2146) against a real transaction
 * manager, which the mock-based listener tests cannot do.
 *
 * <p>The handler ends in {@link LocationHierarchyService#recomputeAncestors}, a
 * {@code @Transactional} service. An exception leaving it marks the transaction it joined
 * rollback-only, so when the listener ran every event inside its own {@code @Transactional}, a
 * "permanent" failure that was caught and logged still made the commit after the catch throw
 * {@code UnexpectedRollbackException}; the container retried the record and dead-lettered it, and
 * the {@code processed_events} mark was rolled back on every attempt.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:people-listener-tx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
            "eureka.client.enabled=false",
            "pos.security.permission-registration.enabled=false"
        })
@ActiveProfiles("test")
@DisplayName("LocationEventsListener transaction shape")
class LocationEventsListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Autowired
    private ExtLocationParentReplicaRepository extLocationParentReplicaRepository;

    @Autowired
    private FailingLocationHierarchyService failingHierarchyService;

    private LocationEventsListener listener;
    private String eventId;
    private UUID locationId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        locationId = UUID.randomUUID();
        failingHierarchyService.reset();
        listener = new LocationEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                extLocationReplicaRepository,
                extLocationParentReplicaRepository,
                failingHierarchyService,
                Mockito.mock(ObjectProvider.class),
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
        assertThatCode(() -> listener.onLocationEvent(locationUpdated())).doesNotThrowAnyException();

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

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onLocationEvent(locationUpdated())))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndEventWasRecorded() {
        assertThat(failingHierarchyService.sawActiveTransaction())
                .as("the handler must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the handler's rollback")
                .isTrue();
        assertThat(extLocationReplicaRepository.existsById(locationId))
                .as("the failed handler's own writes must roll back")
                .isFalse();
    }

    private String locationUpdated() {
        return """
                {"eventId":"%s","eventType":"location.location.updated","aggregateVersion":1,
                 "payload":{"locationId":"%s","name":"Shop","status":"ACTIVE","active":true,"parents":[]}}
                """.formatted(eventId, locationId);
    }

    @TestConfiguration
    static class FailingHierarchyConfiguration {

        @Bean
        @Primary
        FailingLocationHierarchyService failingLocationHierarchyService(
                ExtLocationReplicaRepository extLocationReplicaRepository,
                ExtLocationParentReplicaRepository extLocationParentReplicaRepository) {
            return new FailingLocationHierarchyService(
                    extLocationReplicaRepository, extLocationParentReplicaRepository);
        }
    }

    /** Mirrors the real service's shape: a {@code @Transactional} recompute that throws. */
    static class FailingLocationHierarchyService extends LocationHierarchyService {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        FailingLocationHierarchyService(
                ExtLocationReplicaRepository extLocationReplicaRepository,
                ExtLocationParentReplicaRepository extLocationParentReplicaRepository) {
            super(extLocationReplicaRepository, extLocationParentReplicaRepository);
        }

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        public void reset() {
            sawActiveTransaction.set(false);
        }

        @Override
        @Transactional
        public void recomputeAncestors(@NonNull UUID locationId) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("simulated permanent failure");
        }
    }
}
