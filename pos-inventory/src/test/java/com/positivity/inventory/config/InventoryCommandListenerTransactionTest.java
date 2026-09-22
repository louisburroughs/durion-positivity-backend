package com.positivity.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.inventory.internal.config.ConsumptionService;
import com.positivity.inventory.internal.config.InventoryCommandListener;
import com.positivity.inventory.internal.config.OutboxReplayService;
import com.positivity.inventory.internal.config.PickListGenerationService;
import com.positivity.inventory.internal.config.PickListService;
import com.positivity.inventory.internal.config.ReservationRequestService;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Pins the listener's transaction shape against a real transaction manager, which the mock-based
 * {@link InventoryCommandListenerTest} cannot do.
 *
 * <p>The pick-command handlers are {@code @Transactional} services. An exception leaving one of
 * them marks the transaction it joined rollback-only, so when the listener ran every command
 * inside its own {@code @Transactional}, a "permanent" failure that was caught and logged still
 * made the commit after the catch throw {@code UnexpectedRollbackException}; the container then
 * retried the record through its whole back-off ladder before dead-lettering it, and the
 * {@code processed_events} mark was rolled back on every attempt. On alpha that cost the single
 * commands partition 31 s per failed reservation request, which is what starved the pick-list
 * generate commands queued behind them.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("InventoryCommandListener transaction shape")
class InventoryCommandListenerTransactionTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private OutboxReplayService outboxReplayService;

    @Autowired
    private PickListService pickListService;

    @Autowired
    private PickListGenerationService pickListGenerationService;

    @Autowired
    private ConsumptionService consumptionService;

    @Autowired
    private FailingReservationRequestHandler failingHandler;

    private InventoryCommandListener listener;
    private String commandId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TENANT);
        commandId = UUID.randomUUID().toString();
        listener = new InventoryCommandListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                outboxReplayService,
                pickListService,
                pickListGenerationService,
                consumptionService,
                failingHandler,
                processedEventRepository,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(commandId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent failure inside a transactional handler still records the command and does not throw")
    void permanentFailureInsideTransactionalHandlerIsRecordedNotRetried() {
        assertThatCode(() -> listener.onCommand(reservationCommand(commandId))).doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndCommandWasRecorded();
    }

    /**
     * The shape the defect had: the whole command inside one enclosing transaction, as a
     * {@code @Transactional} listener method would run it. The handler's failure must not mark that
     * transaction rollback-only, or its commit throws {@code UnexpectedRollbackException} and the
     * container retries the record.
     */
    @Test
    @DisplayName("Inside an enclosing transaction, the handler's failure does not poison its commit")
    void permanentFailureDoesNotPoisonAnEnclosingTransaction() {
        TransactionTemplate enclosing = new TransactionTemplate(transactionManager);

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onCommand(reservationCommand(commandId))))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndCommandWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndCommandWasRecorded() {
        assertThat(failingHandler.sawActiveTransaction())
                .as("the handler must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(commandId))
                .as("the processed mark must survive the handler's rollback")
                .isTrue();
    }

    private static String reservationCommand(String commandId) {
        return """
                {"commandType":"inventory.reservation.request-requested","commandId":"%s",
                 "payload":{"workorderLineId":"%s","stockItemId":"%s","requiredQuantity":2,"locationId":"%s"}}
                """.formatted(commandId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @TestConfiguration
    static class FailingHandlerConfiguration {

        @Bean
        @Primary
        FailingReservationRequestHandler failingReservationRequestHandler() {
            return new FailingReservationRequestHandler();
        }
    }

    /** Mirrors the real handler's shape: a {@code @Transactional} service that throws. */
    @Transactional
    static class FailingReservationRequestHandler implements ReservationRequestService {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        @Override
        public void handle(
                @Nullable UUID workorderLineId,
                @Nullable UUID salesOrderLineId,
                @NonNull UUID stockItemId,
                @NonNull BigDecimal requiredQuantity,
                @NonNull UUID locationId,
                @Nullable String uomCode) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("simulated permanent business failure");
        }
    }
}
