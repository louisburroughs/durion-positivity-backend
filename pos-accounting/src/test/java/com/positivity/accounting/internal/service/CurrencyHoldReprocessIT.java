package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real-Postgres IT for a fact held for its currency (ADR-0067 PC-9, issue #2334): a register
 * over/short closed in another currency is recorded {@code SUSPENDED / CURRENCY_NOT_SUPPORTED},
 * once, is left alone by the scheduled auto-retry loop, and goes through the audited reprocess path
 * — which, while the ledger still does not book that currency, re-suspends it with the same reason
 * and posts nothing.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Currency hold is SUSPENDED and releasable (#2334, real Postgres)")
class CurrencyHoldReprocessIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "currency-hold-reprocess");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    private static final Instant CLOSED_AT = Instant.parse("2026-07-23T18:30:00Z");

    @Autowired
    private RegisterOverShortPostingService overShortPostingService;

    @Autowired
    private EventIngestionService eventIngestionService;

    @Autowired
    private FailedAccountingEventRetryJob retryJob;

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    @Autowired
    private ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private UUID sessionId;

    @BeforeEach
    void setUp() {
        sessionId = UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        // accounting_event rows are referenced by reprocessing_attempt_history: clear it first.
        reprocessingAttemptHistoryRepository.deleteAll();
        journalEntryRepository.deleteAll();
        accountingEventRepository.deleteAll();
        sequenceRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
    }

    @Test
    @DisplayName("A foreign-currency over/short is held SUSPENDED / CURRENCY_NOT_SUPPORTED, once per session")
    void heldSuspendedOnce() {
        String firstEnvelope = UUID.randomUUID().toString();
        overShortPostingService.postOverShort(eurShortage(), firstEnvelope);
        overShortPostingService.postOverShort(eurShortage(), UUID.randomUUID().toString());

        AccountingEvent held = onlyHeldRecord();
        assertThat(held.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(held.getFailureReasonCode()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(held.getSourceSystem()).isEqualTo("pos-order");
        assertThat(held.getIngestionId()).isEqualTo(UUID.fromString(firstEnvelope));
        assertThat(held.getErrorMessage()).contains("EUR");
        assertThat(journalEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("The auto-retry loop leaves a currency hold alone")
    void autoRetrySkipsTheHold() {
        overShortPostingService.postOverShort(eurShortage(), UUID.randomUUID().toString());

        retryJob.retryBoundTenant();

        AccountingEvent held = onlyHeldRecord();
        assertThat(held.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(held.getAttemptCount()).isIn(null, 0);
        assertThat(reprocessingAttemptHistoryRepository.findByAccountingEvent_EventIdOrderByAttemptedAtDesc(
                        held.getEventId()))
                .isEmpty();
    }

    @Test
    @DisplayName("Reprocessing while the currency is still unsupported re-suspends it, audited, and posts nothing")
    void reprocessWhileStillForeignReSuspends() {
        overShortPostingService.postOverShort(eurShortage(), UUID.randomUUID().toString());
        UUID eventId = onlyHeldRecord().getEventId();

        ReprocessEventRequest request = new ReprocessEventRequest();
        request.setTriggeredByUserId("ops-user-2334");
        AccountingEventResponse response = eventIngestionService.reprocessEvent(eventId, request);

        assertThat(response.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        AccountingEvent after = onlyHeldRecord();
        assertThat(after.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(after.getFailureReasonCode()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(after.getFailureDetails()).contains("EUR");
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(after.getFinalPostingReferenceId()).isNull();
        assertThat(journalEntryRepository.count()).isZero();

        List<ReprocessingAttemptHistory> history =
                reprocessingAttemptHistoryRepository.findByAccountingEvent_EventIdOrderByAttemptedAtDesc(eventId);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getTriggeredByUserId()).isEqualTo("ops-user-2334");
        assertThat(history.getFirst().getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);

        // A redelivery after the reprocess still writes no second row.
        overShortPostingService.postOverShort(eurShortage(), UUID.randomUUID().toString());
        assertThat(heldRecords()).hasSize(1);
    }

    private AccountingEvent onlyHeldRecord() {
        List<AccountingEvent> held = heldRecords();
        assertThat(held).hasSize(1);
        return held.getFirst();
    }

    private List<AccountingEvent> heldRecords() {
        return accountingEventRepository.findAll().stream()
                .filter(e -> RegisterSessionClosedV1.EVENT_TYPE.equals(e.getEventType()))
                .filter(e -> sessionId.toString().equals(e.getDomainKeyId()))
                .toList();
    }

    private RegisterSessionClosedV1 eurShortage() {
        return new RegisterSessionClosedV1(
                sessionId,
                "terminal-1",
                null,
                "clerk-1",
                "clerk-2",
                new BigDecimal("100.00"),
                new BigDecimal("95.00"),
                new BigDecimal("100.00"),
                new BigDecimal("-5.00"),
                false,
                "EUR",
                List.of(new RegisterSessionClosedV1.TenderTotal("CASH", new BigDecimal("95.00"))),
                BigDecimal.ZERO,
                CLOSED_AT.minusSeconds(28_800),
                CLOSED_AT);
    }
}
