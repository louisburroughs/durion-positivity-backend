package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Reprocesses an {@code invoice.invoice.updated} row held {@code SUSPENDED / TAX_TYPE_MISSING} (CAP:550 S32d item 11,
 * AW50) through invoice revenue recognition's own path, never the posting engine: no posting rule set exists for an
 * invoice fact, and the engine would post neither by tax type nor under the invoice's posting record.
 *
 * <p>The invoice is read from the {@code ext_invoice} replica first: when it is no longer in a posting status, or
 * was finalized again since, the held fact is superseded and resolves {@code SKIPPED / NOT_POSTABLE} with nothing
 * posted (#2664 review A4). Otherwise the replica is posted with {@link InvoiceRevenuePostingService#postRevenue},
 * which re-reads the
 * invoice's tax rows ({@code ext_invoice_tax}, which a later typed fact may have replaced) and the tenant's typed keys
 * (which a person may have mapped):
 *
 * <ul>
 *   <li>still untyped, or a type still without a key: held again {@code SUSPENDED / TAX_TYPE_MISSING} with the new
 *       detail;
 *   <li>posted by type: {@code PROCESSED / NEW}, linked to the entry;
 *   <li>the cycle already posted (a later fact posted it first): {@code PROCESSED / DUPLICATE_IGNORED}, linked to that
 *       entry; nothing posts twice, since the invoice's posting record guards the cycle;
 *   <li>a zero total: {@code PROCESSED / NEW}, no entry; not postable: {@code SKIPPED / NOT_POSTABLE}.
 * </ul>
 *
 * The posting runs in a transaction of its own, so a refusal is labelled the way {@link GoodsReceiptReprocessor}
 * labels one without poisoning the caller's transaction: a closed or hard-locked period {@code SUSPENDED /
 * PERIOD_CLOSED}, an unset accounting time zone {@code SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET}, a missing mapping
 * {@code SUSPENDED / UNMAPPED_EVENT_TYPE}. Anything unexpected propagates and the row is left as it was.
 */
@Slf4j
@Component
public class InvoiceRevenueReprocessor {

    private final InvoiceRevenuePostingService postingService;
    private final ExtInvoiceRepository invoices;
    private final JournalEntryRepository journalEntryRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate postingTransaction;

    public InvoiceRevenueReprocessor(
            InvoiceRevenuePostingService postingService,
            ExtInvoiceRepository invoices,
            JournalEntryRepository journalEntryRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.postingService = postingService;
        this.invoices = invoices;
        this.journalEntryRepository = journalEntryRepository;
        this.objectMapper = objectMapper;
        this.postingTransaction = new TransactionTemplate(transactionManager);
        this.postingTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Whether a held row of {@code eventType} with {@code failureReasonCode} is reprocessed here. */
    public static boolean handles(@Nullable String eventType, @Nullable String failureReasonCode) {
        return InvoiceUpdatedV1.EVENT_TYPE.equals(eventType)
                && PostingFailureReason.TAX_TYPE_MISSING.name().equals(failureReasonCode);
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

    /** Post the stored invoice fact again. */
    public @NonNull Result reprocess(@Nullable Map<String, Object> storedFact) {
        InvoiceUpdatedV1 fact;
        try {
            fact = objectMapper.convertValue(storedFact, InvoiceUpdatedV1.class);
        } catch (RuntimeException unreadable) {
            return held(
                    PostingFailureReason.VALIDATION_ERROR,
                    "Stored invoice payload is unreadable: "
                            + unreadable.getClass().getSimpleName());
        }
        if (fact == null || fact.invoiceId() == null) {
            return held(PostingFailureReason.VALIDATION_ERROR, "Stored invoice payload is empty");
        }
        if (!InvoiceRevenuePostingService.POSTING_STATUSES.contains(fact.status())) {
            return new Result(
                    AccountingEventStatus.SKIPPED,
                    PostingFailureReason.NOT_POSTABLE.name(),
                    "Invoice status " + fact.status() + " does not recognize revenue; nothing posted",
                    null,
                    IdempotencyOutcome.NEW);
        }
        // #2664 review A4: the held fact may be stale. The invoice as the replica holds it now decides: cancelled,
        // reverted or finalized again since, the held fact is superseded and posts nothing; otherwise the replica is
        // posted, the way the reconciliation does.
        Optional<ExtInvoice> current = invoices.findById(fact.invoiceId());
        if (current.isEmpty()) {
            // Absence in a replica proves nothing (ADR-0017): the row stays held under its own reason, so it comes
            // back here once the invoice has replicated (#2664 re-review N2).
            return held(
                    PostingFailureReason.TAX_TYPE_MISSING,
                    "Invoice replica not found; reprocess once it has replicated");
        }
        ExtInvoice invoice = current.get();
        boolean postable = InvoiceRevenuePostingService.POSTING_STATUSES.contains(invoice.getStatus());
        boolean sameCycle = sameInstant(invoice.getFinalizedAt(), fact.finalizedAt());
        if (!postable || !sameCycle) {
            return new Result(
                    AccountingEventStatus.SKIPPED,
                    PostingFailureReason.NOT_POSTABLE.name(),
                    "The held invoice fact is superseded: the invoice is now " + invoice.getStatus()
                            + (sameCycle ? "" : ", finalized again at " + invoice.getFinalizedAt())
                            + "; nothing posted",
                    null,
                    IdempotencyOutcome.NEW);
        }
        InvoiceUpdatedV1 replica = InvoiceRevenueReconciliationService.toPayload(invoice);
        FactPostingOutcome outcome;
        try {
            outcome = postingTransaction.execute(_ -> postingService.postRevenue(replica));
        } catch (AccountingPeriodClosedException e) {
            return held(
                    PostingFailureReason.PERIOD_CLOSED,
                    "Posting blocked by the period gate: " + e.getMessage()
                            + "; event suspended — reprocess after the period is reopened");
        } catch (AccountingPeriodHardLockedException e) {
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
            return held(PostingFailureReason.UNMAPPED_EVENT_TYPE, e.getMessage());
        }
        if (outcome == null) {
            throw new IllegalStateException("Invoice revenue posting returned no outcome for " + fact.invoiceId());
        }
        return switch (outcome) {
            case FactPostingOutcome.Posted posted -> {
                log.info("Held invoice {} posted on reprocess: entry {}", fact.invoiceId(), posted.journalEntryId());
                yield new Result(
                        AccountingEventStatus.PROCESSED,
                        null,
                        "Invoice " + fact.invoiceId() + " revenue posted by tax type",
                        posted.journalEntryId(),
                        IdempotencyOutcome.NEW);
            }
            case FactPostingOutcome.AlreadyPosted already ->
                new Result(
                        AccountingEventStatus.PROCESSED,
                        null,
                        "Invoice " + fact.invoiceId() + " revenue cycle was already posted; nothing posted again",
                        already.journalEntryId() != null
                                ? already.journalEntryId()
                                : earlierEntry(already.sourceEventId()),
                        IdempotencyOutcome.DUPLICATE_IGNORED);
            case FactPostingOutcome.NothingToPost _ ->
                new Result(
                        AccountingEventStatus.PROCESSED,
                        null,
                        "Invoice " + fact.invoiceId() + " has a zero total; no entry",
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
                held(PostingFailureReason.CURRENCY_NOT_SUPPORTED, "Invoice held for its currency");
        };
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

    /**
     * The tolerance within which the replica's {@code finalizedAt} is the held fact's (#2664 re-review N1): pos-invoice
     * publishes nanoseconds and {@code ext_invoice.finalized_at} keeps microseconds, as pos-invoice's own {@code
     * FINALIZED_AT_TOLERANCE} allows for. A re-finalization is a later business action, never within it.
     */
    static final Duration FINALIZED_AT_TOLERANCE = Duration.ofMillis(1);

    /** Whether two finalization instants are the same cycle: both null, or within {@link #FINALIZED_AT_TOLERANCE}. */
    static boolean sameInstant(@Nullable Instant replica, @Nullable Instant fact) {
        if (replica == null || fact == null) {
            return replica == fact;
        }
        return Duration.between(replica, fact).abs().compareTo(FINALIZED_AT_TOLERANCE) <= 0;
    }

    private static Result held(PostingFailureReason reason, String detail) {
        return new Result(AccountingEventStatus.SUSPENDED, reason.name(), detail, null, null);
    }
}
