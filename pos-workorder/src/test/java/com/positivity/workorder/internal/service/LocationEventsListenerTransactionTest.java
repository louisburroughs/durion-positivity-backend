package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.workorder.internal.repository.ExtLocationReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Pins {@link LocationEventsListener}'s transaction shape against a real transaction manager, which
 * the mock-based listener tests cannot do (#2146).
 *
 * <p>The location apply ends in {@link LocationHierarchyService#recomputeAncestors}, a
 * {@code @Transactional} service. An exception leaving it marks the transaction it joined
 * rollback-only, so when the listener method was itself {@code @Transactional}, a "permanent"
 * failure that was caught and logged still made the commit after the catch throw
 * {@code UnexpectedRollbackException}: the container retried the record through its whole back-off
 * ladder, dead-lettered it, and the {@code processed_events} mark was rolled back every time.
 */
@SpringBootTest
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
    private ExtBayReplicaRepository extBayReplicaRepository;

    @Autowired
    private ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;

    @Autowired
    private FailingLocationHierarchyService failingHierarchyService;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    private LocationEventsListener listener;
    private String eventId;
    private UUID locationId;

    @BeforeEach
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
                extBayReplicaRepository,
                extMobileUnitReplicaRepository,
                meterRegistry,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(eventId);
        extLocationReplicaRepository.deleteById(locationId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent failure inside a transactional service still records the fact and does not throw")
    void permanentFailureInsideTransactionalServiceIsRecordedNotRetried() {
        assertThatCode(() -> listener.onLocationEvent(locationUpdated())).doesNotThrowAnyException();

        assertServiceFailedInsideTransactionAndFactWasRecorded();
    }

    /**
     * The shape the defect had: the whole record inside one enclosing transaction, as a
     * {@code @Transactional} listener method would run it. The service's failure must not mark that
     * transaction rollback-only, or its commit throws {@code UnexpectedRollbackException} and the
     * container retries the record.
     */
    @Test
    @DisplayName("Inside an enclosing transaction, the service's failure does not poison its commit")
    void permanentFailureDoesNotPoisonAnEnclosingTransaction() {
        TransactionTemplate enclosing = new TransactionTemplate(transactionManager);

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onLocationEvent(locationUpdated())))
                .doesNotThrowAnyException();

        assertServiceFailedInsideTransactionAndFactWasRecorded();
    }

    private void assertServiceFailedInsideTransactionAndFactWasRecorded() {
        assertThat(failingHierarchyService.sawActiveTransaction())
                .as("the service must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the apply's rollback")
                .isTrue();
        assertThat(extLocationReplicaRepository.existsById(locationId))
                .as("the failed apply's own replica write must roll back with it")
                .isFalse();
    }

    private String locationUpdated() {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":1,
                 "payload":{"locationId":"%s","name":"Shop","active":true,"parents":[]}}
                """.formatted(eventId, LocationUpdatedV1.EVENT_TYPE, locationId);
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
