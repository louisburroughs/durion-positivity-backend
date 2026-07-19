package com.positivity.accounting.internal.handler;

import com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.service.GLMappingResolver;
import com.positivity.accounting.service.GLPostingService;
import com.positivity.accounting.service.IdempotencyService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event handler for AR payment application GL posting work items (story C1,
 * issue #954).
 *
 * <p>
 * Consumes {@link PaymentApplicationGLPostingEvent} work items delivered from
 * the transactional outbox (mirroring the {@link APPaymentGLPostingEventHandler}
 * async pattern) and posts the cash receipt to the ledger via
 * {@link GLPostingService#postPaymentApplication}:
 * <ul>
 * <li>Dr Undeposited Funds (parity decision D-3 — never straight to Cash)</li>
 * <li>Cr Accounts Receivable</li>
 * </ul>
 *
 * <p>
 * Accounts are never hardcoded: both sides resolve through the
 * {@code PAYMENT_APPLICATION} posting category and its
 * {@code UNDEPOSITED_FUNDS} / {@code ACCOUNTS_RECEIVABLE} mapping keys
 * (seeded by {@code R__seed_reference_accounting.sql}).
 *
 * <p>
 * Idempotency: the posting key is the caller-supplied application request id
 * (namespaced with {@code PAYMENT_APPLICATION_GL_POSTING:}); a replayed or
 * duplicate work item is a no-op, so an application never double-posts. The
 * key is registered in the same transaction as the posted journal entry.
 *
 * <p>
 * Failure semantics mirror the AP payment flow: any exception propagates to
 * {@link com.positivity.accounting.internal.service.OutboxProcessor}, which
 * marks the work item failed and retries with backoff (FAILED after max
 * retries). Posting into a CLOSED or hard-locked period surfaces the Wave 2
 * period-gate exceptions unwrapped so the failure reason stays visible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentApplicationGLPostingEventHandler {

    static final String POSTING_CATEGORY_NAME = "PAYMENT_APPLICATION";
    static final String DEBIT_MAPPING_KEY = "UNDEPOSITED_FUNDS";
    static final String CREDIT_MAPPING_KEY = "ACCOUNTS_RECEIVABLE";
    static final String IDEMPOTENCY_KEY_PREFIX = "PAYMENT_APPLICATION_GL_POSTING:";

    private final Clock clock;
    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;

    /**
     * Handle payment application GL posting work item → post balanced journal
     * entry (Dr Undeposited Funds, Cr AR).
     *
     * @param event payment application GL posting work item
     */
    @EventListener
    @Transactional
    public void onPaymentApplicationGLPosting(@NonNull PaymentApplicationGLPostingEvent event) {
        String applicationRequestId = event.getApplicationRequestId();
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + applicationRequestId;

        if (idempotencyService.isKeyProcessed(idempotencyKey)) {
            log.info(
                    "Payment application GL posting already processed, skipping | applicationRequestId={} "
                            + "| eventId={}",
                    applicationRequestId,
                    event.getEventId());
            return;
        }

        log.info(
                "Received PaymentApplicationGLPostingEvent | eventId={} | applicationRequestId={} | paymentId={} "
                        + "| appliedAmount={}",
                event.getEventId(),
                applicationRequestId,
                event.getPaymentId(),
                event.getAppliedAmount());

        try {
            // Transaction date is the event's business timestamp (when the
            // payment application actually occurred), NOT the processing/clock
            // time. On outbox retries or backlog the clock time drifts into the
            // wrong accounting period and selects the wrong effective-dated GL
            // mapping; deriving from applicationTimestamp keeps both the entry
            // date and the mapping resolution stable across replays. The Instant
            // is converted to LocalDateTime using the injected clock's zone,
            // matching the module's Instant→LocalDateTime convention.
            LocalDateTime transactionDate =
                    LocalDateTime.ofInstant(event.getApplicationTimestamp(), clock.getZone());

            // Account resolution via posting category / mapping key
            // configuration — no hardcoded account ids (story C1 requirement).
            UUID undepositedFundsAccountId =
                    glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, DEBIT_MAPPING_KEY, transactionDate);
            UUID arAccountId =
                    glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, CREDIT_MAPPING_KEY, transactionDate);

            UUID sourceEventId = toSourceEventId(applicationRequestId);
            String description = "AR cash receipt for payment application request " + applicationRequestId;

            JournalEntry posted = glPostingService.postPaymentApplication(
                    sourceEventId,
                    undepositedFundsAccountId,
                    arAccountId,
                    event.getAppliedAmount(),
                    transactionDate,
                    description);

            idempotencyService.registerKey(idempotencyKey, posted.getJournalEntryId());

            log.info(
                    "Payment application GL posting completed | applicationRequestId={} | journalEntryId={}",
                    applicationRequestId,
                    posted.getJournalEntryId());

        } catch (AccountingPeriodClosedException | AccountingPeriodHardLockedException e) {
            // Wave 2 period gate: propagate unwrapped so the failure reason
            // stays visible in the outbox retry record; retry succeeds once the
            // period is reopened (or the hard lock moved).
            log.warn(
                    "Payment application GL posting blocked by period gate | applicationRequestId={} | error={}",
                    applicationRequestId,
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error(
                    "Failed to process PaymentApplicationGLPostingEvent | applicationRequestId={} | eventId={} "
                            + "| error={}",
                    applicationRequestId,
                    event.getEventId(),
                    e.getMessage(),
                    e);
            throw new IllegalStateException(
                    "GL posting failed for payment application request: " + applicationRequestId, e);
        }
    }

    /**
     * Derive the journal entry {@code sourceEventId} from the caller-supplied
     * application request id. UUID-shaped request ids are used verbatim;
     * anything else maps deterministically via a name-based UUID so replays of
     * the same request id always produce the same source event id.
     */
    static @NonNull UUID toSourceEventId(@NonNull String applicationRequestId) {
        try {
            return UUID.fromString(applicationRequestId);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(applicationRequestId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
