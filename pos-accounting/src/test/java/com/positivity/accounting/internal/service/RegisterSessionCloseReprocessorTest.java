package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.RegisterSessionContractViolationException;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1.Movement;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reprocessing a held {@code order.session.closed} row re-runs the session's own posting from the stored fact (#2594,
 * CAP:550 S17 PROPOSED 6): drawer movements, over/short and undeposited session, in a transaction of its own, never
 * the posting engine; refusals are labelled holds, a broken contract stays failed.
 */
@DisplayName("RegisterSessionCloseReprocessor (#2594)")
class RegisterSessionCloseReprocessorTest {

    private static final UUID SESSION = UUID.fromString("019a0000-0000-7000-8000-00000000c001");
    private static final UUID MOVEMENT = UUID.fromString("019a0000-0000-7000-8000-00000000c002");
    private static final Instant CLOSED_AT = Instant.parse("2026-10-07T22:00:00Z");
    private static final String ENVELOPE = "019a0000-0000-7000-8000-00000000c0e1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RegisterCashMovementPostingService movements = mock(RegisterCashMovementPostingService.class);
    private final RegisterOverShortPostingService overShort = mock(RegisterOverShortPostingService.class);
    private final UndepositedSessionProjection undeposited = mock(UndepositedSessionProjection.class);
    private final JournalEntryRepository entries = mock(JournalEntryRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private SimpleTransactionStatus transaction;
    private RegisterSessionCloseReprocessor reprocessor;

    @BeforeEach
    void setUp() {
        transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        when(movements.postMovements(any(), any())).thenReturn(FactPostingOutcome.nothingToPost());
        when(overShort.postOverShort(any(), any())).thenReturn(FactPostingOutcome.nothingToPost());
        when(entries.findBySourceEvent(any())).thenReturn(List.of());
        reprocessor = new RegisterSessionCloseReprocessor(
                movements,
                overShort,
                undeposited,
                entries,
                new LedgerCurrency("USD"),
                objectMapper,
                transactionManager);
    }

    @Test
    @DisplayName("routes every order.session.closed row, whatever its reason, and nothing else")
    void handlesEverySessionRow() {
        assertThat(RegisterSessionCloseReprocessor.handles("order.session.closed"))
                .isTrue();
        assertThat(RegisterSessionCloseReprocessor.handles("order.session.opened"))
                .isFalse();
        assertThat(RegisterSessionCloseReprocessor.handles(null)).isFalse();
    }

    @Test
    @DisplayName("a session that now posts is PROCESSED / NEW, linked to the over/short's entry, in its own"
            + " transaction, with its undeposited session written once at schema 2")
    void postsOnceAndWritesTheUndepositedSession() {
        UUID movementEntry = UUID.randomUUID();
        UUID overShortEntry = UUID.randomUUID();
        when(movements.postMovements(any(), eq(ENVELOPE))).thenReturn(FactPostingOutcome.posted(movementEntry));
        when(overShort.postOverShort(any(), eq(ENVELOPE))).thenReturn(FactPostingOutcome.posted(overShortEntry));

        RegisterSessionCloseReprocessor.Result result = reprocessor.reprocess(
                stored(fact("USD", "-3.00", List.of(petty("USD")))), "GL_MAPPING_NOT_CONFIGURED", ENVELOPE);

        assertThat(result.status()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(result.reason()).isNull();
        assertThat(result.idempotencyOutcome()).isEqualTo(IdempotencyOutcome.NEW);
        assertThat(result.journalEntryId()).isEqualTo(overShortEntry);
        assertThat(result.resolved()).isTrue();
        verify(undeposited).record(any(RegisterSessionClosedV1.class), eq(2));
        verify(transactionManager).commit(transaction);
        assertThat(transaction.isRollbackOnly()).isFalse();
    }

    @Test
    @DisplayName("a stored fact without movements is schema 1: it posts its over/short and writes no undeposited"
            + " session (the projection is asked with schema 1)")
    void schemaOneFact() {
        UUID entry = UUID.randomUUID();
        when(overShort.postOverShort(any(), any())).thenReturn(FactPostingOutcome.posted(entry));

        RegisterSessionCloseReprocessor.Result result =
                reprocessor.reprocess(stored(fact("USD", "-3.00", null)), "GL_MAPPING_NOT_CONFIGURED", ENVELOPE);

        assertThat(result.status()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(result.journalEntryId()).isEqualTo(entry);
        verify(undeposited).record(any(RegisterSessionClosedV1.class), eq(1));
    }

    @Test
    @DisplayName("every posting key already registered: PROCESSED / DUPLICATE_IGNORED, linked to the earlier entry")
    void keysAlreadyRegisteredIsDuplicate() {
        JournalEntry earlier = entry();
        UUID movementSource = RegisterCashMovementPostingService.toSourceEventId(MOVEMENT);
        when(movements.postMovements(any(), any()))
                .thenReturn(new FactPostingOutcome.AlreadyPosted(null, movementSource));
        when(entries.findBySourceEvent(movementSource)).thenReturn(List.of(earlier));

        RegisterSessionCloseReprocessor.Result result = reprocessor.reprocess(
                stored(fact("USD", "0.00", List.of(petty("USD")))), "GL_MAPPING_NOT_CONFIGURED", ENVELOPE);

        assertThat(result.status()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(result.idempotencyOutcome()).isEqualTo(IdempotencyOutcome.DUPLICATE_IGNORED);
        assertThat(result.journalEntryId()).isEqualTo(earlier.getJournalEntryId());
    }

    @Test
    @DisplayName("the over/short's entry already in the ledger (its posting key may have expired, #2595): nothing runs"
            + " and the row closes PROCESSED / DUPLICATE_IGNORED, linked to that entry")
    void overShortEntryInTheLedgerIsDuplicate() {
        JournalEntry earlier = entry();
        when(entries.findBySourceEvent(RegisterOverShortPostingService.toSourceEventId(SESSION)))
                .thenReturn(List.of(earlier));

        RegisterSessionCloseReprocessor.Result result = reprocessor.reprocess(
                stored(fact("USD", "-3.00", List.of(petty("USD")))), "CURRENCY_NOT_SUPPORTED", ENVELOPE);

        assertThat(result.status()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(result.idempotencyOutcome()).isEqualTo(IdempotencyOutcome.DUPLICATE_IGNORED);
        assertThat(result.journalEntryId()).isEqualTo(earlier.getJournalEntryId());
        verifyNoInteractions(movements, overShort, undeposited, transactionManager);
    }

    @Test
    @DisplayName("no variance and nothing to post: PROCESSED / NEW with no entry; the undeposited session is still"
            + " written")
    void nothingToPost() {
        RegisterSessionCloseReprocessor.Result result =
                reprocessor.reprocess(stored(fact("USD", "0.00", List.of())), "GL_MAPPING_NOT_CONFIGURED", ENVELOPE);

        assertThat(result.status()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(result.idempotencyOutcome()).isEqualTo(IdempotencyOutcome.NEW);
        assertThat(result.journalEntryId()).isNull();
        verify(undeposited).record(any(RegisterSessionClosedV1.class), eq(2));
    }

    @Test
    @DisplayName("a currency still not the ledger's stays SUSPENDED / CURRENCY_NOT_SUPPORTED naming it; the posting"
            + " transaction rolls back, so the posting path's own hold record is not written beside the row")
    void currencyStillForeignStaysHeld() {
        when(movements.postMovements(any(), any())).thenReturn(new FactPostingOutcome.CurrencyHeld());

        RegisterSessionCloseReprocessor.Result result = reprocessor.reprocess(
                stored(fact("USD", "-3.00", List.of(petty("CAD")))), "CURRENCY_NOT_SUPPORTED", ENVELOPE);

        assertThat(result.status()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(result.reason()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(result.detail()).contains("USD/CAD").contains("ledger books USD");
        assertThat(result.idempotencyOutcome()).isNull();
        assertThat(result.resolved()).isFalse();
        assertThat(transaction.isRollbackOnly()).isTrue();
        verify(overShort, never()).postOverShort(any(), any());
        verify(undeposited, never()).record(any(), anyInt());
    }

    @Test
    @DisplayName("a mapping still missing stays SUSPENDED / GL_MAPPING_NOT_CONFIGURED, naming the category and key")
    void mappingStillMissingStaysHeld() {
        when(movements.postMovements(any(), any()))
                .thenThrow(new GLMappingNotConfiguredException(
                        "Mapping key not configured: REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_SHOP_SUPPLIES"));

        RegisterSessionCloseReprocessor.Result result = reprocessor.reprocess(
                stored(fact("USD", "-3.00", List.of(petty("USD")))), "GL_MAPPING_NOT_CONFIGURED", ENVELOPE);

        assertThat(result.status()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(result.reason()).isEqualTo("GL_MAPPING_NOT_CONFIGURED");
        assertThat(result.detail())
                .contains("REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_SHOP_SUPPLIES")
                .contains("configure the mapping, then reprocess");
        verify(undeposited, never()).record(any(), anyInt());
    }

    @Test
    @DisplayName("a closed period, a hard lock and an unset time zone are labelled holds, as the engine labels them")
    void refusalsAreLabelled() {
        RegisterSessionClosedV1 fact = fact("USD", "-3.00", List.of(petty("USD")));
        when(movements.postMovements(any(), any()))
                .thenThrow(new AccountingPeriodClosedException("2026-10", "period 2026-10 is CLOSED"));
        RegisterSessionCloseReprocessor.Result closed =
                reprocessor.reprocess(stored(fact), "GL_MAPPING_NOT_CONFIGURED", ENVELOPE);
        assertThat(closed.status()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(closed.reason()).isEqualTo("PERIOD_CLOSED");
        assertThat(closed.detail()).contains("reprocess after the period is reopened");

        org.mockito.Mockito.doThrow(new AccountingPeriodHardLockedException(
                        LocalDate.of(2026, 10, 31), "2026-10-07 is on or before the hard lock 2026-10-31"))
                .when(movements)
                .postMovements(any(), any());
        RegisterSessionCloseReprocessor.Result locked = reprocessor.reprocess(stored(fact), "PERIOD_CLOSED", ENVELOPE);
        assertThat(locked.reason()).isEqualTo("PERIOD_CLOSED");
        assertThat(locked.detail()).contains("permanently blocked").doesNotContain("reprocess after");

        org.mockito.Mockito.doThrow(new AccountingTimeZoneUnsetException())
                .when(movements)
                .postMovements(any(), any());
        RegisterSessionCloseReprocessor.Result zone = reprocessor.reprocess(stored(fact), "PERIOD_CLOSED", ENVELOPE);
        assertThat(zone.status()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(zone.reason()).isEqualTo("ACCOUNTING_TIME_ZONE_UNSET");
    }

    @Test
    @DisplayName("a fact that still breaks the contract stays FAILED / VALIDATION_ERROR with the violation")
    void contractViolationStaysFailed() {
        org.mockito.Mockito.doThrow(new RegisterSessionContractViolationException(
                        "Bank drop " + MOVEMENT + " of session " + SESSION + " cannot be deposited: no movementId"))
                .when(undeposited)
                .record(any(), anyInt());

        RegisterSessionCloseReprocessor.Result result =
                reprocessor.reprocess(stored(fact("USD", "0.00", List.of())), "VALIDATION_ERROR", ENVELOPE);

        assertThat(result.status()).isEqualTo(AccountingEventStatus.FAILED);
        assertThat(result.reason()).isEqualTo("VALIDATION_ERROR");
        assertThat(result.detail()).contains("cannot be deposited: no movementId");
    }

    @Test
    @DisplayName("a fact failed as a contract violation without a movements list is a schema-2 fact that lost it: it"
            + " stays FAILED and nothing runs; the same payload held for a mapping is a schema-1 fact and posts")
    void failedFactWithoutMovementsStaysFailed() {
        Map<String, Object> schemaTwoWithoutList = stored(fact("USD", "-3.00", null));

        RegisterSessionCloseReprocessor.Result failed =
                reprocessor.reprocess(schemaTwoWithoutList, "VALIDATION_ERROR", ENVELOPE);

        assertThat(failed.status()).isEqualTo(AccountingEventStatus.FAILED);
        assertThat(failed.reason()).isEqualTo("VALIDATION_ERROR");
        assertThat(failed.detail()).contains("carries no movements list");
        verifyNoInteractions(movements, overShort, undeposited);

        assertThat(reprocessor
                        .reprocess(schemaTwoWithoutList, "GL_MAPPING_NOT_CONFIGURED", ENVELOPE)
                        .status())
                .isEqualTo(AccountingEventStatus.PROCESSED);
        verify(undeposited).record(any(RegisterSessionClosedV1.class), eq(1));
    }

    @Test
    @DisplayName("an unreadable or empty payload is FAILED / VALIDATION_ERROR and posts nothing")
    void unreadablePayload() {
        assertThat(reprocessor
                        .reprocess(Map.of("sessionId", "not-a-uuid"), "GL_MAPPING_NOT_CONFIGURED", ENVELOPE)
                        .reason())
                .isEqualTo("VALIDATION_ERROR");
        assertThat(reprocessor
                        .reprocess(null, "GL_MAPPING_NOT_CONFIGURED", ENVELOPE)
                        .status())
                .isEqualTo(AccountingEventStatus.FAILED);
        verifyNoInteractions(movements, overShort, undeposited);
    }

    @Test
    @DisplayName("a concurrent reprocess's unique-key conflict propagates for the caller's deterministic 409")
    void uniqueKeyConflictPropagates() {
        when(movements.postMovements(any(), any()))
                .thenThrow(new DataIntegrityViolationException("idempotency_keys_key_value_key"));

        assertThatThrownBy(() -> reprocessor.reprocess(
                        stored(fact("USD", "-3.00", List.of(petty("USD")))), "GL_MAPPING_NOT_CONFIGURED", ENVELOPE))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(transactionManager).rollback(transaction);
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private Map<String, Object> stored(RegisterSessionClosedV1 fact) {
        return objectMapper.convertValue(fact, new TypeReference<Map<String, Object>>() {});
    }

    private static JournalEntry entry() {
        JournalEntry entry = new JournalEntry();
        entry.setJournalEntryId(UUID.randomUUID());
        return entry;
    }

    private static Movement petty(String currency) {
        return new Movement(
                MOVEMENT,
                "PETTY_EXPENSE",
                "OUT",
                new BigDecimal("18.40"),
                currency,
                "SHOP_SUPPLIES",
                null,
                null,
                "R-1",
                "clerk-1",
                null,
                null,
                CLOSED_AT.minusSeconds(3600),
                null,
                List.of(),
                null,
                null,
                null);
    }

    private static RegisterSessionClosedV1 fact(String currency, String overShort, List<Movement> movements) {
        return new RegisterSessionClosedV1(
                SESSION,
                "T-7",
                null,
                "clerk-1",
                "clerk-2",
                new BigDecimal("200.00"),
                new BigDecimal("200.00").add(new BigDecimal(overShort)),
                new BigDecimal("200.00"),
                new BigDecimal(overShort),
                false,
                currency,
                List.of(new RegisterSessionClosedV1.TenderTotal("CASH", new BigDecimal("100.00"))),
                BigDecimal.ZERO,
                CLOSED_AT.minusSeconds(28_800),
                CLOSED_AT,
                movements);
    }
}
