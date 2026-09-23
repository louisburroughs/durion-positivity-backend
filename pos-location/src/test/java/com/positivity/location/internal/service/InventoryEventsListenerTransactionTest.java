package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.domainevents.inventory.StorageLocationOnHandUpdatedV1;
import com.positivity.location.internal.repository.ExtStorageLocationOnHandReplicaRepository;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.intercept.MethodInterceptor;
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
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins {@link InventoryEventsListener}'s transaction shape (#2146) against a real transaction
 * manager, which the mock-based listener tests cannot do.
 *
 * <p>The listener's apply goes through Spring Data repositories, which are transactional. An
 * exception leaving one of them marks the transaction it joined rollback-only, so when the listener
 * method was {@code @Transactional}, a "permanent" failure that was caught and logged still made the
 * commit after the catch throw {@code UnexpectedRollbackException}; the container then retried the
 * record through its back-off ladder to the DLQ, and the {@code processed_events} mark was rolled
 * back every time. The failing repository here reproduces that boundary exactly: a transaction
 * interceptor around a method that throws.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("InventoryEventsListener transaction shape (#2146)")
class InventoryEventsListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ExtStorageLocationOnHandReplicaRepository onHandRepository;

    private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

    private InventoryEventsListener listener;
    private String eventId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        listener = new InventoryEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                failingRepository(),
                org.mockito.Mockito.mock(ObjectProvider.class),
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(eventId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent failure inside a transactional repository still records the event and does not throw")
    void permanentFailureInsideTransactionalRepositoryIsRecordedNotRetried() {
        assertThatCode(() -> listener.onInventoryEvent(onHandUpdated(eventId))).doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    /**
     * The shape the defect had: the whole event inside one enclosing transaction, as a
     * {@code @Transactional} listener method would run it. The apply's failure must not mark that
     * transaction rollback-only, or its commit throws {@code UnexpectedRollbackException} and the
     * container retries the record.
     */
    @Test
    @DisplayName("Inside an enclosing transaction, the apply's failure does not poison its commit")
    void permanentFailureDoesNotPoisonAnEnclosingTransaction() {
        TransactionTemplate enclosing = new TransactionTemplate(transactionManager);

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onInventoryEvent(onHandUpdated(eventId))))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndEventWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndEventWasRecorded() {
        assertThat(sawActiveTransaction.get())
                .as("the apply must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the apply's rollback")
                .isTrue();
    }

    /**
     * The real repository behind a transaction interceptor — the boundary a Spring Data repository
     * carries — whose {@code findById} fails with a non-transient exception.
     */
    private ExtStorageLocationOnHandReplicaRepository failingRepository() {
        ProxyFactory factory = new ProxyFactory(onHandRepository);
        factory.addInterface(ExtStorageLocationOnHandReplicaRepository.class);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new MatchAlwaysTransactionAttributeSource()));
        factory.addAdvice((MethodInterceptor) invocation -> {
            if ("findById".equals(invocation.getMethod().getName())) {
                sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                throw new IllegalStateException("simulated permanent failure");
            }
            return invocation.proceed();
        });
        return (ExtStorageLocationOnHandReplicaRepository) factory.getProxy();
    }

    private static String onHandUpdated(String eventId) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":1,
                 "payload":{"storageLocationId":"%s","onHandQuantity":3}}
                """.formatted(eventId, StorageLocationOnHandUpdatedV1.EVENT_TYPE, UUID.randomUUID());
    }
}
