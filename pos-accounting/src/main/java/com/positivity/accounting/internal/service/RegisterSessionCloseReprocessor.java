package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.RegisterSessionContractViolationException;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Reprocesses a held {@code order.session.closed} row through the session's own posting, never the posting engine
 * (#2594, CAP:550 S17 PROPOSED 6). No posting rule set exists for a session fact: the engine would neither post the
 * over/short nor the drawer movements, nor check their currency, nor write the undeposited session.
 *
 * <p>Every session row is routed here ({@code EventIngestionServiceImpl#rerunPosting}), whatever its reason: a missing
 * mapping ({@code SUSPENDED / GL_MAPPING_NOT_CONFIGURED}) and a broken contract ({@code FAILED / VALIDATION_ERROR}),
 * both written by {@link OrderEventsListener}; a currency hold ({@code SUSPENDED / CURRENCY_NOT_SUPPORTED}) written by
 * the posting path; and any hold a reprocess left. The stored payload is posted again with {@link
 * RegisterSessionClosePosting}, the listener's own sequence (drawer movements, over/short, undeposited session), in a
 * transaction of its own:
 *
 * <ul>
 *   <li>posted: {@code PROCESSED / NEW}, linked to the over/short's entry, else the first movement's;
 *   <li>nothing to post (no variance, no petty expense): {@code PROCESSED / NEW}, no entry;
 *   <li>every posting key of the session already registered (a re-emit posted it first), or the over/short's entry
 *       already in the ledger: nothing posts and the row closes {@code PROCESSED / DUPLICATE_IGNORED}, linked to the
 *       earlier entry. The entry check comes first because the over/short's posting key expires after 24 hours (#2595)
 *       and a reprocess may come later; a session posts all or nothing, so its over/short entry proves the movements
 *       posted too;
 *   <li>a currency still not the ledger's: held again {@code SUSPENDED / CURRENCY_NOT_SUPPORTED}; the posting
 *       transaction is rolled back, so the posting path's own hold record is not written beside the row reprocessed.
 * </ul>
 *
 * The undeposited session is written exactly when the session posts, in the same transaction, and never twice. A
 * refusal is labelled as {@link GoodsReceiptReprocessor} labels one, without poisoning the caller's transaction: a
 * closed period {@code SUSPENDED / PERIOD_CLOSED} (a hard-locked one the same, worded as permanently blocked), an unset
 * accounting time zone {@code SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET}, a missing mapping {@code SUSPENDED /
 * GL_MAPPING_NOT_CONFIGURED}, a broken contract {@code FAILED / VALIDATION_ERROR}. Anything unexpected propagates and the
 * row is left as it was; a unique-key conflict from a concurrent reprocess propagates to the caller's 409.
 *
 * <p><b>Schema version.</b> The stored payload is the fact, not its envelope. Version 2 always carries {@code
 * movements} (empty when nothing moved) and version 1 never does, so a stored list is version 2 and none is version 1
 * — except on a row failed as a contract violation: a version-1 fact breaks no contract check, so a failed fact without
 * a list is a version-2 fact that lost it, and it stays failed until its payload carries one.
 */
@Slf4j
@Component
public class RegisterSessionCloseReprocessor {

    private final RegisterSessionClosePosting sessionPosting;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerCurrency ledgerCurrency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate postingTransaction;

    public RegisterSessionCloseReprocessor(
            RegisterCashMovementPostingService movementPostingService,
            RegisterOverShortPostingService overShortPostingService,
            UndepositedSessionProjection undepositedSessions,
            JournalEntryRepository journalEntryRepository,
            LedgerCurrency ledgerCurrency,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.sessionPosting =
                new RegisterSessionClosePosting(movementPostingService, overShortPostingService, undepositedSessions);
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerCurrency = ledgerCurrency;
        this.objectMapper = objectMapper;
        this.postingTransaction = new TransactionTemplate(transactionManager);
        this.postingTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Whether a held row of {@code eventType} is reprocessed here: every {@code order.session.closed} row. */
    public static boolean handles(@Nullable String eventType) {
        return RegisterSessionClosedV1.EVENT_TYPE.equals(eventType);
    }

    /**
     * What a reprocess makes of the held row.
     *
     * @param status the row's new status
     * @param reason the failure reason code it keeps or gets; null once resolved
     * @param detail what happened, for the row and its attempt history
     * @param journalEntryId the entry posted, or the earlier one a duplicate matched; null otherwise
     * @param idempotencyOutcome {@code NEW} or {@code DUPLICATE_IGNORED} once resolved; null while held
     */
    public record Result(
            @NonNull AccountingEventStatus status,
            @Nullable String reason,
            @NonNull String detail,
            @Nullable UUID journalEntryId,
            @Nullable IdempotencyOutcome idempotencyOutcome) {

        /** Whether the row is now terminal ({@code PROCESSED} or {@code SKIPPED}). */
        public boolean resolved() {
            return status == AccountingEventStatus.PROCESSED || status == AccountingEventStatus.SKIPPED;
        }
    }

    /**
     * Post the stored session fact again.
     *
     * @param storedFact the row's payload, the consumed fact
     * @param heldReason the row's current {@code failureReasonCode}
     * @param envelopeEventId the row's ingestion id, handed to the posting path as the consumed envelope's
     */
    public @NonNull Result reprocess(
            @Nullable Map<String, Object> storedFact, @Nullable String heldReason, @NonNull String envelopeEventId) {
        RegisterSessionClosedV1 fact;
        try {
            fact = objectMapper.convertValue(storedFact, RegisterSessionClosedV1.class);
        } catch (RuntimeException unreadable) {
            return failed("Stored register session payload is unreadable: "
                    + unreadable.getClass().getSimpleName());
        }
        if (fact == null || fact.sessionId() == null || fact.closedAt() == null) {
            return failed("Stored register session payload has no sessionId or closedAt");
        }
        int schemaVersion;
        if (fact.movements() != null) {
            schemaVersion = RegisterSessionClosedV1.SCHEMA_VERSION;
        } else if (PostingFailureReason.VALIDATION_ERROR.name().equals(heldReason)) {
            return failed("Close fact of session " + fact.sessionId() + " carries no movements list and was failed as"
                    + " a contract violation: a schema-1 fact breaks no contract check, so it is a schema-2 fact without"
                    + " its list and its bank drops cannot be read");
        } else {
            schemaVersion = 1;
        }

        UUID overShortEntry = earlierEntry(RegisterOverShortPostingService.toSourceEventId(fact.sessionId()));
        if (overShortEntry != null) {
            log.info(
                    "Held register session {} was already posted ({}); nothing posted",
                    fact.sessionId(),
                    overShortEntry);
            return duplicate(fact, overShortEntry);
        }

        FactPostingOutcome outcome;
        try {
            outcome = postingTransaction.execute(status -> {
                FactPostingOutcome posted = sessionPosting.post(fact, envelopeEventId, schemaVersion);
                if (posted instanceof FactPostingOutcome.CurrencyHeld) {
                    // The row reprocessed is the hold: nothing posted, and no second hold record beside it.
                    status.setRollbackOnly();
                }
                return posted;
            });
        } catch (AccountingPeriodClosedException e) {
            return held(
                    PostingFailureReason.PERIOD_CLOSED,
                    "Posting blocked by the period gate: " + e.getMessage()
                            + "; event suspended — reprocess after the period is reopened");
        } catch (AccountingPeriodHardLockedException e) {
            // The engine's wording: a hard lock only moves forward and is never reopened.
            return held(
                    PostingFailureReason.PERIOD_CLOSED,
                    "Posting blocked by the period gate: " + e.getMessage()
                            + "; event suspended — posting is permanently blocked and cannot be"
                            + " reprocessed (the hard lock is never reopened)");
        } catch (AccountingTimeZoneUnsetException e) {
            return held(
                    PostingFailureReason.ACCOUNTING_TIME_ZONE_UNSET,
                    "The tenant's accounting time zone is not set; set the accounting time zone"
                            + " (PUT /v1/accounting/configuration/time-zone), then reprocess");
        } catch (GLMappingNotConfiguredException e) {
            return held(PostingFailureReason.GL_MAPPING_NOT_CONFIGURED, RegisterSessionClosePosting.mappingDetail(e));
        } catch (RegisterSessionContractViolationException e) {
            return failed(String.valueOf(e.getMessage()));
        }
        if (outcome == null) {
            throw new IllegalStateException("Register session posting returned no outcome for " + fact.sessionId());
        }
        return switch (outcome) {
            case FactPostingOutcome.Posted posted -> {
                log.info(
                        "Held register session {} posted on reprocess: entry {}",
                        fact.sessionId(),
                        posted.journalEntryId());
                yield new Result(
                        AccountingEventStatus.PROCESSED,
                        null,
                        "Register session " + fact.sessionId() + " posted (drawer movements and over/short)",
                        posted.journalEntryId(),
                        IdempotencyOutcome.NEW);
            }
            case FactPostingOutcome.AlreadyPosted already ->
                duplicate(
                        fact,
                        already.journalEntryId() != null
                                ? already.journalEntryId()
                                : earlierEntry(already.sourceEventId()));
            case FactPostingOutcome.NothingToPost _ ->
                new Result(
                        AccountingEventStatus.PROCESSED,
                        null,
                        "Register session " + fact.sessionId() + " has no variance and no drawer movement to post;"
                                + " no entry",
                        null,
                        IdempotencyOutcome.NEW);
            case FactPostingOutcome.Skipped skipped ->
                new Result(
                        AccountingEventStatus.SKIPPED,
                        skipped.reason().name(),
                        skipped.detail(),
                        null,
                        IdempotencyOutcome.NEW);
            case FactPostingOutcome.Held held -> held(held.reason(), held.detail());
            case FactPostingOutcome.CurrencyHeld _ ->
                held(PostingFailureReason.CURRENCY_NOT_SUPPORTED, currencyDetail(fact));
        };
    }

    private Result duplicate(RegisterSessionClosedV1 fact, @Nullable UUID earlier) {
        return new Result(
                AccountingEventStatus.PROCESSED,
                null,
                "Register session " + fact.sessionId() + " was already posted; nothing posted again",
                earlier,
                IdempotencyOutcome.DUPLICATE_IGNORED);
    }

    /** The hold's detail, worded as the posting path words its own (ADR-0067 PC-9). */
    private String currencyDetail(RegisterSessionClosedV1 fact) {
        Stream<String> movementCurrencies = fact.movements() == null
                ? Stream.empty()
                : fact.movements().stream().map(RegisterSessionClosedV1.Movement::currencyCode);
        String currencies = Stream.concat(Stream.of(fact.currencyCode()), movementCurrencies)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("/"));
        return "Register session closed in " + currencies + " not posted: the ledger books " + ledgerCurrency.code()
                + " only and another currency is never booked at par (ADR-0067 PC-9)";
    }

    private @Nullable UUID earlierEntry(@Nullable UUID sourceEventId) {
        if (sourceEventId == null) {
            return null;
        }
        return journalEntryRepository.findBySourceEvent(sourceEventId).stream()
                .findFirst()
                .map(JournalEntry::getJournalEntryId)
                .orElse(null);
    }

    private static Result held(PostingFailureReason reason, String detail) {
        return new Result(AccountingEventStatus.SUSPENDED, reason.name(), detail, null, null);
    }

    private static Result failed(String detail) {
        return new Result(
                AccountingEventStatus.FAILED, PostingFailureReason.VALIDATION_ERROR.name(), detail, null, null);
    }
}
