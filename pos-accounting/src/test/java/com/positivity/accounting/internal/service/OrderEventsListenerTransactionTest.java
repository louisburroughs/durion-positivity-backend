package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins {@link OrderEventsListener}'s transaction shape (ADR-0044 as amended by #2146, issue #2191)
 * against a real transaction manager: a {@code @Transactional} posting service that throws, bare
 * and inside an enclosing transaction. A permanent failure is recorded without poisoning a commit;
 * a propagating failure (closed period, transient) surfaces as itself and leaves no mark.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("OrderEventsListener transaction shape")
class OrderEventsListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private FailingOverShortPostingService failingPostingService;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    private OrderEventsListener listener;
    private String eventId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        failingPostingService.reset();
        listener = new OrderEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                failingPostingService,
                meterRegistry,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(eventId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent failure inside the transactional posting service is recorded and does not throw")
    void permanentFailureIsRecordedNotRetried() {
        failingPostingService.failWith(DatabindException.from((JsonParser) null, "simulated permanent failure"));

        assertThatCode(() -> listener.onOrderEvent(sessionClosed())).doesNotThrowAnyException();

        assertThat(failingPostingService.sawActiveTransaction()).isTrue();
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    @Test
    @DisplayName("Inside an enclosing transaction, the permanent failure does not poison its commit")
    void permanentFailureDoesNotPoisonAnEnclosingTransaction() {
        failingPostingService.failWith(DatabindException.from((JsonParser) null, "simulated permanent failure"));

        assertThatCode(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(_ -> listener.onOrderEvent(sessionClosed())))
                .doesNotThrowAnyException();

        assertThat(failingPostingService.sawActiveTransaction()).isTrue();
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    @Test
    @DisplayName("A propagating failure (e.g. PERIOD_CLOSED) surfaces as itself and leaves no mark")
    void propagatingFailureSurfacesUnmarked() {
        failingPostingService.failWith(new IllegalStateException("simulated PERIOD_CLOSED"));

        assertThatThrownBy(() -> listener.onOrderEvent(sessionClosed()))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThat(failingPostingService.sawActiveTransaction()).isTrue();
        assertThat(processedEventRepository.existsById(eventId)).isFalse();
    }

    @Test
    @DisplayName("Inside an enclosing transaction, a propagating failure still surfaces as itself, unmarked")
    void propagatingFailureInsideEnclosingTransactionSurfacesUnmarked() {
        failingPostingService.failWith(new IllegalStateException("simulated PERIOD_CLOSED"));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(_ -> listener.onOrderEvent(sessionClosed())))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThat(processedEventRepository.existsById(eventId)).isFalse();
    }

    private String sessionClosed() {
        UUID sessionId = UUID.randomUUID();
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s",
                 "payload":{"sessionId":"%s","terminalId":"terminal-1","locationId":null,
                            "openedByClerkId":"clerk-1","closedByClerkId":"clerk-2",
                            "openingFloat":100.00,"countedCash":140.00,"theoreticalCash":150.00,
                            "overShort":-10.00,"varianceApproved":false,"currencyCode":"USD",
                            "tenderTotals":[{"methodType":"CASH","amount":50.00}],"cashMovementTotal":0.00,
                            "openedAt":"2026-07-23T08:00:00Z","closedAt":"2026-07-23T18:30:00Z"}}
                """.formatted(eventId, RegisterSessionClosedV1.EVENT_TYPE, sessionId, sessionId);
    }

    @TestConfiguration
    static class FailingServiceConfiguration {

        @Bean
        @Primary
        FailingOverShortPostingService failingOverShortPostingService() {
            return new FailingOverShortPostingService();
        }
    }

    /** Mirrors the real service's shape: a {@code @Transactional} posting that throws. */
    static class FailingOverShortPostingService extends RegisterOverShortPostingService {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        FailingOverShortPostingService() {
            super(null, null, null, null);
        }

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        public void failWith(RuntimeException exception) {
            failure.set(exception);
        }

        public void reset() {
            sawActiveTransaction.set(false);
            failure.set(new IllegalStateException("simulated failure"));
        }

        @Override
        @Transactional
        public void postOverShort(@NonNull RegisterSessionClosedV1 fact) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw failure.get();
        }
    }
}
