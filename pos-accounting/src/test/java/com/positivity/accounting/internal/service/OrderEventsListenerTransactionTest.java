package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ExtOrderRegisterSession;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.RegisterSessionStatus;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.RegisterSessionContractViolationException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ExtOrderRegisterSessionRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionOpenedV1;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    @Autowired
    private KafkaFactIngestionRecorder ingestionRecorder;

    @Autowired
    private RegisterCashMovementPostingService movementPostingService;

    @Autowired
    private RegisterSessionReplica sessionReplica;

    @Autowired
    private UndepositedSessionProjection undepositedSessions;

    @Autowired
    private ExtOrderRegisterSessionRepository sessionRows;

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    private OrderEventsListener listener;
    private String eventId;
    private final List<UUID> heldSessions = new ArrayList<>();

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
                movementPostingService,
                ingestionRecorder,
                meterRegistry,
                transactionManager,
                com.positivity.accounting.internal.service.TestZoneResolvers.utc(java.time.Clock.systemUTC()),
                sessionReplica,
                undepositedSessions);
    }

    @AfterEach
    void tearDown() {
        // Held records carry no reprocessing_attempt_history rows, so they can be deleted directly.
        heldSessions.forEach(session -> accountingEventRepository.deleteAll(records(session)));
        processedEventRepository.deleteById(eventId);
        sessionRows.deleteAll();
        TenantContext.clear();
    }

    @Test
    @DisplayName("#2571: a close whose posting fails still commits the replica close, unmarked; the redelivery"
            + " re-applies it harmlessly")
    void closeWhosePostingFailsStillClosesTheReplica() {
        UUID sessionId = UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ -> sessionReplica.opened(
                        new RegisterSessionOpenedV1(
                                sessionId, "terminal-1", null, Instant.parse("2026-07-23T08:00:00Z")),
                        1));
        failingPostingService.failWith(new IllegalStateException("simulated PERIOD_CLOSED"));

        assertThatThrownBy(() -> listener.onOrderEvent(sessionClosed(sessionId, 2)))
                .isExactlyInstanceOf(IllegalStateException.class);

        ExtOrderRegisterSession closed = sessionRows.findById(sessionId).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(RegisterSessionStatus.CLOSED);
        assertThat(closed.getAggregateVersion()).isEqualTo(2);
        assertThat(processedEventRepository.existsById(eventId)).isFalse();
        assertThat(sessionReplica.openSessionOf("terminal-1")).isEmpty();

        // The redelivery: the replica write is state-based and version-guarded, so applying it again changes nothing.
        assertThatThrownBy(() -> listener.onOrderEvent(sessionClosed(sessionId, 2)))
                .isExactlyInstanceOf(IllegalStateException.class);
        assertThat(sessionRows.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(RegisterSessionStatus.CLOSED);
            assertThat(row.getAggregateVersion()).isEqualTo(2);
            assertThat(row.getClosedAt()).isEqualTo(Instant.parse("2026-07-23T18:30:00Z"));
        });
    }

    @Test
    @DisplayName("#2571: an opened fact commits the replica row with its processed mark in its own transaction, which"
            + " an enclosing rollback does not undo")
    void sessionOpenedCommitsWithItsMark() {
        UUID sessionId = UUID.randomUUID();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(_ -> {
                    listener.onOrderEvent(sessionOpened(sessionId));
                    throw new IllegalStateException("the enclosing transaction rolls back");
                }))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        assertThat(sessionReplica.openSessionOf("terminal-1"))
                .map(ExtOrderRegisterSession::getSessionId)
                .contains(sessionId);
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

    @Test
    @DisplayName("#2594: a missing mapping thrown through the transactional posting service rolls the session back and"
            + " commits the SUSPENDED / GL_MAPPING_NOT_CONFIGURED record with the processed mark, in its own"
            + " transaction, which an enclosing rollback does not undo")
    void missingMappingIsHeldInItsOwnTransaction() {
        UUID sessionId = heldSession();
        failingPostingService.failWith(
                new GLMappingNotConfiguredException("Mapping key not configured: REGISTER_OVER_SHORT/CASH_SHORT"));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(_ -> {
                    listener.onOrderEvent(sessionClosed(sessionId, 0));
                    throw new IllegalStateException("the enclosing transaction rolls back");
                }))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("the enclosing transaction rolls back");

        assertThat(failingPostingService.sawActiveTransaction()).isTrue();
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        assertThat(records(sessionId)).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
            assertThat(row.getFailureReasonCode()).isEqualTo("GL_MAPPING_NOT_CONFIGURED");
            assertThat(row.getErrorMessage()).contains("REGISTER_OVER_SHORT/CASH_SHORT");
            assertThat(row.getSourceSystem()).isEqualTo("pos-order");
            assertThat(row.getIngestionId()).isEqualTo(UUID.fromString(eventId));
            assertThat(row.getPayload()).containsEntry("sessionId", sessionId.toString());
        });
    }

    @Test
    @DisplayName("#2594: a broken contract is recorded FAILED / VALIDATION_ERROR with its processed mark and does not"
            + " throw; a re-emit under a new envelope id writes no second row")
    void contractViolationIsRecordedOnce() {
        UUID sessionId = heldSession();
        failingPostingService.failWith(new RegisterSessionContractViolationException(
                "Petty-expense movement null of session " + sessionId + " cannot post: no movementId"));

        assertThatCode(() -> listener.onOrderEvent(sessionClosed(sessionId, 0))).doesNotThrowAnyException();
        String first = eventId;
        eventId = UUID.randomUUID().toString();
        assertThatCode(() -> listener.onOrderEvent(sessionClosed(sessionId, 0))).doesNotThrowAnyException();

        assertThat(processedEventRepository.existsById(first)).isTrue();
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        processedEventRepository.deleteById(first);
        assertThat(records(sessionId)).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(AccountingEventStatus.FAILED);
            assertThat(row.getFailureReasonCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(row.getErrorMessage()).contains("cannot post: no movementId");
        });
    }

    private UUID heldSession() {
        UUID sessionId = UUID.randomUUID();
        heldSessions.add(sessionId);
        return sessionId;
    }

    private List<AccountingEvent> records(UUID sessionId) {
        return accountingEventRepository.findAll().stream()
                .filter(e -> RegisterSessionClosedV1.EVENT_TYPE.equals(e.getEventType()))
                .filter(e -> sessionId.toString().equals(e.getDomainKeyId()))
                .toList();
    }

    private String sessionOpened(UUID sessionId) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s","aggregateVersion":1,
                 "payload":{"sessionId":"%s","terminalId":"terminal-1","locationId":null,
                            "openedAt":"2026-07-23T08:00:00Z"}}
                """.formatted(eventId, RegisterSessionOpenedV1.EVENT_TYPE, sessionId, sessionId);
    }

    private String sessionClosed() {
        return sessionClosed(UUID.randomUUID(), 0);
    }

    private String sessionClosed(UUID sessionId, long version) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"sessionId":"%s","terminalId":"terminal-1","locationId":null,
                            "openedByClerkId":"clerk-1","closedByClerkId":"clerk-2",
                            "openingFloat":100.00,"countedCash":140.00,"theoreticalCash":150.00,
                            "overShort":-10.00,"varianceApproved":false,"currencyCode":"USD",
                            "tenderTotals":[{"methodType":"CASH","amount":50.00}],"cashMovementTotal":0.00,
                            "openedAt":"2026-07-23T08:00:00Z","closedAt":"2026-07-23T18:30:00Z"}}
                """.formatted(eventId, RegisterSessionClosedV1.EVENT_TYPE, sessionId, version, sessionId);
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
            super(null, null, null, null, null, null);
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
        public @NonNull FactPostingOutcome postOverShort(
                @NonNull RegisterSessionClosedV1 fact, @NonNull String envelopeEventId) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw failure.get();
        }
    }
}
