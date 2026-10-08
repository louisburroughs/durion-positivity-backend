package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import java.util.Map;
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
 * Reprocesses a held {@code goodsreceipt.recorded} row through S41's own path, never the posting engine (CAP:550 S41,
 * #2602; Accounting ruling 4 on #2602). No posting rule set exists for a receipt, so the engine would re-hold it under
 * a generic reason, or worse, post it without the currency check, the line rules and the receipt key.
 *
 * <p>The stored payload is assessed again with {@link GoodsReceiptAccrualPostingService#assess}, currency first:
 *
 * <ul>
 *   <li>no currency, or a foreign one: still held {@code SUSPENDED / CURRENCY_NOT_SUPPORTED} (a null code is "no
 *       currency", never "not foreign");
 *   <li>malformed: still held {@code SUSPENDED / VALIDATION_ERROR}, with its detail;
 *   <li>uncosted: {@code SKIPPED / UNCOSTED_FACT}; nothing to post: {@code PROCESSED / NEW}, no entry;
 *   <li>postable: posted under {@code GOODS_RECEIPT_ACCRUAL:<receiptId>}, {@code PROCESSED / NEW}; when that key has
 *       already posted (a corrected re-emit posted first), nothing is posted and the row closes {@code PROCESSED /
 *       DUPLICATE_IGNORED}, linked to the earlier entry.
 * </ul>
 *
 * The payload never changes, so in v1 a held fact stays held until Inventory sends a corrected fact or the ledger's
 * currency changes. The posting runs in a transaction of its own, so a refusal is labelled the way the engine labels a
 * reprocess refusal, without poisoning the caller's transaction: a closed or hard-locked period {@code SUSPENDED /
 * PERIOD_CLOSED}, an unset accounting time zone {@code SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET}, a missing mapping
 * {@code SUSPENDED / UNMAPPED_EVENT_TYPE}. Anything unexpected propagates and the row is left as it was.
 */
@Slf4j
@Component
public class GoodsReceiptReprocessor {

    private final GoodsReceiptAccrualPostingService postingService;
    private final JournalEntryRepository journalEntryRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate postingTransaction;

    public GoodsReceiptReprocessor(
            GoodsReceiptAccrualPostingService postingService,
            JournalEntryRepository journalEntryRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.postingService = postingService;
        this.journalEntryRepository = journalEntryRepository;
        this.objectMapper = objectMapper;
        this.postingTransaction = new TransactionTemplate(transactionManager);
        this.postingTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    /** Re-assess and, when it now passes, post the stored receipt fact. */
    public @NonNull Result reprocess(@Nullable Map<String, Object> storedFact) {
        GoodsReceiptRecordedV1 fact;
        try {
            fact = objectMapper.convertValue(storedFact, GoodsReceiptRecordedV1.class);
        } catch (RuntimeException unreadable) {
            return held(
                    PostingFailureReason.VALIDATION_ERROR,
                    "Stored goods receipt payload is unreadable: " + unreadable.getMessage());
        }
        if (fact == null) {
            return held(PostingFailureReason.VALIDATION_ERROR, "Stored goods receipt payload is empty");
        }
        return switch (postingService.assess(fact)) {
            case GoodsReceiptAccrualPostingService.Assessment.CurrencyNotSupported currency ->
                held(PostingFailureReason.CURRENCY_NOT_SUPPORTED, currency.detail());
            case GoodsReceiptAccrualPostingService.Assessment.Malformed malformed ->
                held(PostingFailureReason.VALIDATION_ERROR, malformed.detail());
            case GoodsReceiptAccrualPostingService.Assessment.Uncosted uncosted ->
                new Result(
                        AccountingEventStatus.SKIPPED,
                        PostingFailureReason.UNCOSTED_FACT.name(),
                        uncosted.detail(),
                        null,
                        IdempotencyOutcome.NEW);
            case GoodsReceiptAccrualPostingService.Assessment.NothingToPost _ ->
                new Result(
                        AccountingEventStatus.PROCESSED,
                        null,
                        "Goods receipt " + fact.receiptId() + " accrues and values nothing; no entry",
                        null,
                        IdempotencyOutcome.NEW);
            case GoodsReceiptAccrualPostingService.Assessment.Postable _ -> post(fact);
        };
    }

    private Result post(GoodsReceiptRecordedV1 fact) {
        UUID posted;
        try {
            posted = postingTransaction.execute(_ -> postingService.postAccrual(fact));
        } catch (AccountingPeriodClosedException | AccountingPeriodHardLockedException e) {
            return held(
                    PostingFailureReason.PERIOD_CLOSED,
                    "Posting blocked by the period gate: " + e.getMessage() + "; reprocess after the period is open");
        } catch (AccountingTimeZoneUnsetException e) {
            return held(
                    PostingFailureReason.ACCOUNTING_TIME_ZONE_UNSET,
                    "The tenant's accounting time zone is not set; set the accounting time zone"
                            + " (PUT /v1/accounting/configuration/time-zone), then reprocess");
        } catch (GLMappingNotConfiguredException e) {
            return held(PostingFailureReason.UNMAPPED_EVENT_TYPE, e.getMessage());
        }
        if (posted != null) {
            log.info("Held goods receipt {} posted on reprocess: entry {}", fact.receiptId(), posted);
            return new Result(
                    AccountingEventStatus.PROCESSED,
                    null,
                    "Goods receipt " + fact.receiptId() + " posted under "
                            + GoodsReceiptAccrualPostingService.postingKey(fact.receiptId()),
                    posted,
                    IdempotencyOutcome.NEW);
        }
        UUID earlier =
                journalEntryRepository
                        .findBySourceEvent(GoodsReceiptAccrualPostingService.toSourceEventId(fact.receiptId()))
                        .stream()
                        .map(JournalEntry::getJournalEntryId)
                        .findFirst()
                        .orElse(null);
        log.info("Held goods receipt {} was already posted ({}); nothing posted", fact.receiptId(), earlier);
        return new Result(
                AccountingEventStatus.PROCESSED,
                null,
                "Goods receipt " + fact.receiptId() + " was already posted under "
                        + GoodsReceiptAccrualPostingService.postingKey(fact.receiptId()) + "; nothing posted",
                earlier,
                IdempotencyOutcome.DUPLICATE_IGNORED);
    }

    private static Result held(PostingFailureReason reason, String detail) {
        return new Result(AccountingEventStatus.SUSPENDED, reason.name(), detail, null, null);
    }
}
