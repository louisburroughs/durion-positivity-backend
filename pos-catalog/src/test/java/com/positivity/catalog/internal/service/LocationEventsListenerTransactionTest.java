package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.catalog.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.catalog.internal.repository.ExtLocationReplicaRepository;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
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
 * Pins the listener's transaction shape (#2146) against a real transaction manager, which the
 * mock-based listener tests cannot do.
 *
 * <p>The location apply crosses {@link LocationHierarchyService#recomputeAncestors}, a
 * {@code @Transactional} service. When the listener method itself was {@code @Transactional}, an
 * exception leaving that service marked the shared transaction rollback-only, so a "permanent"
 * failure that was caught and logged still made the commit after the catch throw
 * {@code UnexpectedRollbackException}; the container retried the record to the DLQ and the
 * {@code processed_events} mark was rolled back on every attempt. The same shape — and the same
 * fix — applies to every replica listener in this module, and {@code CatalogCommandListener} is
 * covered separately by {@code CatalogCommandListenerTransactionPropagationTest} (it writes no
 * processed mark), so this is the module's one Spring-backed guard for the event-listener shape.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("LocationEventsListener transaction shape")
class LocationEventsListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Autowired
    private ExtLocationParentReplicaRepository extLocationParentReplicaRepository;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    @Autowired
    private FailingLocationHierarchyService failingHierarchy;

    private LocationEventsListener listener;
    private String eventId;
    private UUID locationId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        locationId = UUID.randomUUID();
        failingHierarchy.reset();
        listener = new LocationEventsListener(
                Clock.systemUTC(),
                objectMapper,
                processedEventRepository,
                extLocationReplicaRepository,
                extLocationParentReplicaRepository,
                failingHierarchy,
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
    @DisplayName("A permanent failure inside a transactional service still records the event and does not throw")
    void permanentFailureInsideTransactionalServiceIsRecordedNotRetried() {
        assertThatCode(() -> listener.onLocationEvent(locationUpdated())).doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    /**
     * The shape the defect had: the whole event inside one enclosing transaction, as a
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

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndEventWasRecorded() {
        assertThat(failingHierarchy.sawActiveTransaction())
                .as("the service must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the apply's rollback")
                .isTrue();
        assertThat(extLocationReplicaRepository.existsById(locationId))
                .as("the failed apply's own replica write must have rolled back")
                .isFalse();
    }

    private String locationUpdated() {
        return """
                {"eventId":"%s","eventType":"location.location.updated","aggregateVersion":1,
                 "payload":{"locationId":"%s","name":"Main","active":true,"parents":[]}}
                """.formatted(eventId, locationId);
    }

    @TestConfiguration
    static class FailingHierarchyConfiguration {

        @Bean
        @Primary
        FailingLocationHierarchyService failingLocationHierarchyService() {
            return new FailingLocationHierarchyService();
        }
    }

    /** Mirrors the real service's shape: a {@code @Transactional} recompute that throws. */
    @Transactional
    static class FailingLocationHierarchyService extends LocationHierarchyService {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        FailingLocationHierarchyService() {
            super(null, null);
        }

        public void reset() {
            sawActiveTransaction.set(false);
        }

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        @Override
        public void recomputeAncestors(@NonNull UUID locationId) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("simulated permanent failure");
        }
    }
}
