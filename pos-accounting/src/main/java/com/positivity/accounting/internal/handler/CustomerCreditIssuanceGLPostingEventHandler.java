package com.positivity.accounting.internal.handler;

import com.positivity.accounting.internal.dto.CustomerCreditIssuanceGLPostingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.service.GLMappingResolver;
import com.positivity.accounting.internal.service.GLPostingService;
import com.positivity.accounting.internal.service.IdempotencyService;
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
 * Event handler for customer-credit issuance GL posting work items (parity-C1,
 * issue #975).
 *
 * <p>
 * Consumes {@link CustomerCreditIssuanceGLPostingEvent} work items delivered
 * from the transactional outbox (mirroring the
 * {@link PaymentApplicationGLPostingEventHandler} async pattern) and posts the
 * overpayment excess to the ledger via
 * {@link GLPostingService#postCustomerCreditIssuance}:
 * <ul>
 * <li>Dr Undeposited Funds (the overpayment cash actually received)</li>
 * <li>Cr Customer Credit Liability (the obligation now owed to the customer)</li>
 * </ul>
 *
 * <p>
 * Accounts are never hardcoded: both sides resolve through the
 * {@code CUSTOMER_CREDIT_ISSUANCE} posting category and its
 * {@code UNDEPOSITED_FUNDS} / {@code CUSTOMER_CREDIT_LIABILITY} mapping keys
 * (seeded by {@code R__seed_reference_accounting.sql}).
 *
 * <p>
 * Idempotency: the posting key is the caller-supplied application request id,
 * namespaced with {@code CUSTOMER_CREDIT_ISSUANCE_GL_POSTING:} — distinct from
 * the AR cash-receipt leg that shares the same request id — so a replayed or
 * duplicate work item is a no-op and an issuance never double-posts. The key is
 * registered in the same transaction as the posted journal entry.
 *
 * <p>
 * Failure semantics mirror the AR cash-receipt flow: any exception propagates
 * to {@link com.positivity.accounting.internal.service.OutboxProcessor}, which
 * marks the work item failed and retries with backoff. Posting into a CLOSED or
 * hard-locked period surfaces the Wave 2 period-gate exceptions unwrapped so the
 * failure reason stays visible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerCreditIssuanceGLPostingEventHandler {

    static final String POSTING_CATEGORY_NAME = "CUSTOMER_CREDIT_ISSUANCE";
    static final String DEBIT_MAPPING_KEY = "UNDEPOSITED_FUNDS";
    static final String CREDIT_MAPPING_KEY = "CUSTOMER_CREDIT_LIABILITY";
    static final String IDEMPOTENCY_KEY_PREFIX = "CUSTOMER_CREDIT_ISSUANCE_GL_POSTING:";
    static final String SOURCE_EVENT_NAMESPACE = "CUSTOMER_CREDIT_ISSUANCE:";

    private final Clock clock;
    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;

    /**
     * Handle customer-credit issuance GL posting work item → post balanced
     * journal entry (Dr Undeposited Funds, Cr Customer Credit Liability).
     *
     * @param event customer-credit issuance GL posting work item
     */
    @EventListener
    @Transactional
    public void onCustomerCreditIssuanceGLPosting(@NonNull CustomerCreditIssuanceGLPostingEvent event) {
        String applicationRequestId = event.getApplicationRequestId();
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + applicationRequestId;

        if (idempotencyService.isKeyProcessed(idempotencyKey)) {
            log.info(
                    "Customer credit issuance GL posting already processed, skipping | applicationRequestId={} "
                            + "| creditId={} | eventId={}",
                    applicationRequestId,
                    event.getCreditId(),
                    event.getEventId());
            return;
        }

        log.info(
                "Received CustomerCreditIssuanceGLPostingEvent | eventId={} | applicationRequestId={} | creditId={} "
                        + "| paymentId={} | creditAmount={}",
                event.getEventId(),
                applicationRequestId,
                event.getCreditId(),
                event.getPaymentId(),
                event.getCreditAmount());

        try {
            // Transaction date is the event's business timestamp (when the
            // overpayment/credit actually occurred), NOT the processing/clock
            // time — matching the AR cash-receipt leg so both entries land in the
            // same accounting period and resolve the same effective-dated GL
            // mapping across replays.
            LocalDateTime transactionDate = LocalDateTime.ofInstant(event.getApplicationTimestamp(), clock.getZone());

            // Account resolution via posting category / mapping key configuration
            // — no hardcoded account ids (issue #975 requirement).
            UUID undepositedFundsAccountId =
                    glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, DEBIT_MAPPING_KEY, transactionDate);
            UUID creditLiabilityAccountId =
                    glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, CREDIT_MAPPING_KEY, transactionDate);

            UUID sourceEventId = toSourceEventId(applicationRequestId);
            String description = "Customer credit issuance for overpayment on application request "
                    + applicationRequestId + " (credit " + event.getCreditId() + ")";

            JournalEntry posted = glPostingService.postCustomerCreditIssuance(
                    sourceEventId,
                    event.getCreditId(),
                    undepositedFundsAccountId,
                    creditLiabilityAccountId,
                    event.getCreditAmount(),
                    transactionDate,
                    description,
                    null);

            idempotencyService.registerKey(idempotencyKey, posted.getJournalEntryId());

            log.info(
                    "Customer credit issuance GL posting completed | applicationRequestId={} | creditId={} "
                            + "| journalEntryId={}",
                    applicationRequestId,
                    event.getCreditId(),
                    posted.getJournalEntryId());

        } catch (AccountingPeriodClosedException | AccountingPeriodHardLockedException e) {
            // Wave 2 period gate: propagate unwrapped so the failure reason stays
            // visible in the outbox retry record; retry succeeds once the period
            // is reopened (or the hard lock moved).
            log.warn(
                    "Customer credit issuance GL posting blocked by period gate | applicationRequestId={} | error={}",
                    applicationRequestId,
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error(
                    "Failed to process CustomerCreditIssuanceGLPostingEvent | applicationRequestId={} | eventId={} "
                            + "| error={}",
                    applicationRequestId,
                    event.getEventId(),
                    e.getMessage(),
                    e);
            throw new IllegalStateException(
                    "GL posting failed for customer credit issuance on application request: " + applicationRequestId,
                    e);
        }
    }

    /**
     * Derive the journal entry {@code sourceEventId} for the issuance leg from
     * the caller-supplied application request id, namespaced so it never collides
     * with the AR cash-receipt entry (which uses the request id verbatim). The
     * mapping is deterministic, so replays of the same request id always produce
     * the same source event id.
     */
    static @NonNull UUID toSourceEventId(@NonNull String applicationRequestId) {
        return UUID.nameUUIDFromBytes((SOURCE_EVENT_NAMESPACE + applicationRequestId).getBytes(StandardCharsets.UTF_8));
    }
}
