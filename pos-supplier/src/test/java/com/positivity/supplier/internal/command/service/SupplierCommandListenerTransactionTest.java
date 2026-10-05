package com.positivity.supplier.internal.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierOrderRequestedV1;
import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.mktcat.service.MktCatRepublisher;
import com.positivity.supplier.internal.order.service.OrderNotDispatchedPublisher;
import com.positivity.supplier.internal.order.service.TransmissionIntentWriter;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogRepublisher;
import com.positivity.supplier.internal.repository.ProcessedEventRepository;
import com.positivity.supplier.internal.repository.SupplierAccountRepository;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepository;
import com.positivity.supplier.internal.repository.SupplierTransmissionLineRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the listener's transaction shape (#2146) against a real transaction manager, which the
 * mock-based {@link SupplierCommandListenerTest} cannot do.
 *
 * <p>An order command naming a vendor this deployment does not have is the documented permanent
 * failure: {@link TransmissionIntentWriter#mint} ({@code @Transactional(MANDATORY)}) throws
 * {@link TransmissionIntentWriter.UnknownSupplierException}, the listener logs it and records the
 * command. When the listener method itself was {@code @Transactional}, that exception marked the
 * shared transaction rollback-only, so the commit after the catch threw
 * {@code UnexpectedRollbackException}; the container retried the record to the DLQ and the
 * {@code processed_events} mark was rolled back on every attempt. The writer here is the real one,
 * wrapped only to observe the transaction it ran in.
 *
 * <p>Runs on the H2 {@code dev} profile rather than the module's Testcontainers {@code pg} profile:
 * the defect is in Spring's transaction propagation, not in Postgres, and this keeps the guard
 * running wherever the unit suite runs.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:supplier_command_listener_tx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
            "spring.cloud.discovery.enabled=false",
            "spring.boot.admin.client.enabled=false",
            "eureka.client.enabled=false"
        })
@ActiveProfiles("dev")
@DisplayName("SupplierCommandListener transaction shape")
class SupplierCommandListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObservedTransmissionIntentWriter intentWriter;

    @Autowired
    private PriceCatalogRepublisher republisher;

    @Autowired
    private MktCatRepublisher mktCatRepublisher;

    @Autowired
    private SupplierOutboxEventRepository outboxRepository;

    @Autowired
    private OrderNotDispatchedPublisher notDispatchedPublisher;

    private SupplierCommandListener listener;
    private String eventId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        orderId = UUID.randomUUID();
        intentWriter.reset();
        listener = new SupplierCommandListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                intentWriter,
                republisher,
                mktCatRepublisher,
                notDispatchedPublisher,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(eventId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("An unknown vendor inside the transactional writer still records the command and does not throw")
    void permanentFailureInsideTransactionalHandlerIsRecordedNotRetried() {
        assertThatCode(() -> listener.onSupplierCommand(orderCommandForUnknownVendor()))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndCommandWasRecorded();
    }

    /**
     * The shape the defect had: the whole command inside one enclosing transaction, as a
     * {@code @Transactional} listener method would run it. The writer's failure must not mark that
     * transaction rollback-only, or its commit throws {@code UnexpectedRollbackException} and the
     * container retries the record.
     */
    @Test
    @DisplayName("Inside an enclosing transaction, the writer's failure does not poison its commit")
    void permanentFailureDoesNotPoisonAnEnclosingTransaction() {
        TransactionTemplate enclosing = new TransactionTemplate(transactionManager);

        assertThatCode(() ->
                        enclosing.executeWithoutResult(_ -> listener.onSupplierCommand(orderCommandForUnknownVendor())))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndCommandWasRecorded();
    }

    @Test
    @DisplayName("An unconfigured vendor commits the not-dispatched outbox row together with the processed mark")
    void unknownVendorWritesNotDispatchedOutboxRowAndProcessedMark() {
        listener.onSupplierCommand(orderCommandForUnknownVendor());

        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        List<SupplierOutboxEventEntity> rows = notDispatchedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getTopic()).isEqualTo("supplier.events.v1");
        assertThat(rows.getFirst().getRecordKey()).isEqualTo(orderId.toString());
        assertThat(rows.getFirst().getPayload())
                .contains(eventId)
                .contains("SUPPLIER_NOT_CONFIGURED")
                .contains("no vendor profile for alias no-such-vendor");
    }

    @Test
    @DisplayName("If the answer cannot be queued, the processed mark rolls back with it")
    void failureWritingTheAnswerLeavesTheCommandUnprocessed() {
        SupplierCommandListener failing = new SupplierCommandListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                intentWriter,
                republisher,
                mktCatRepublisher,
                new OrderNotDispatchedPublisher(
                        new SupplierOutboxEventWriter(null, null, null) {
                            @Override
                            public void publish(@NonNull String topic, @NonNull DomainEventEnvelope<?> envelope) {
                                throw new QueryTimeoutException("outbox down");
                            }
                        },
                        Clock.systemUTC()),
                transactionManager);

        assertThatThrownBy(() -> failing.onSupplierCommand(orderCommandForUnknownVendor()))
                .isInstanceOf(QueryTimeoutException.class);

        assertThat(processedEventRepository.existsById(eventId)).isFalse();
        assertThat(notDispatchedRows()).isEmpty();
    }

    private List<SupplierOutboxEventEntity> notDispatchedRows() {
        return outboxRepository.findAll().stream()
                .filter(row -> "supplier.order.notdispatched".equals(row.getEventType()))
                .filter(row -> row.getPayload().contains(orderId.toString()))
                .toList();
    }

    private void assertHandlerFailedInsideTransactionAndCommandWasRecorded() {
        assertThat(intentWriter.sawActiveTransaction())
                .as("the writer must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the handler's rollback")
                .isTrue();
    }

    private String orderCommandForUnknownVendor() {
        return """
                {"eventId":"%s","eventType":"supplier.order.requested","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,"occurredAtUtc":"2026-08-14T11:59:00Z",
                 "sourceService":"pos-order","source":"pos-order","correlationId":"corr-1",
                 "payload":{"purchaseOrderId":"%s","revision":1,"intentType":"INITIAL",
                   "supplierRef":"no-such-vendor","deliveryLocationId":null,"purchaseOrderNumber":"PO-1",
                   "lines":[{"lineNumber":1,"articleEan":"3528709999083",
                     "supplierArticleCode":"99991","quantity":4,"requestedDeliveryDate":null}]}}
                """.formatted(eventId, orderId, orderId);
    }

    @TestConfiguration
    static class ObservedWriterConfiguration {

        @Bean
        @Primary
        ObservedTransmissionIntentWriter observedTransmissionIntentWriter(
                SupplierProfileRepository profileRepository,
                SupplierAccountRepository accountRepository,
                SupplierTransmissionIntentRepository intentRepository,
                SupplierTransmissionLineRepository lineRepository) {
            return new ObservedTransmissionIntentWriter(
                    profileRepository, accountRepository, intentRepository, lineRepository);
        }
    }

    /** The real writer, recording whether a transaction was active when it was asked to mint. */
    static class ObservedTransmissionIntentWriter extends TransmissionIntentWriter {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        ObservedTransmissionIntentWriter(
                SupplierProfileRepository profileRepository,
                SupplierAccountRepository accountRepository,
                SupplierTransmissionIntentRepository intentRepository,
                SupplierTransmissionLineRepository lineRepository) {
            super(profileRepository, accountRepository, intentRepository, lineRepository);
        }

        public void reset() {
            sawActiveTransaction.set(false);
        }

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public @NonNull Optional<SupplierTransmissionIntentEntity> mint(
                @NonNull SupplierOrderRequestedV1 command, @NonNull String correlationId) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return super.mint(command, correlationId);
        }
    }
}
