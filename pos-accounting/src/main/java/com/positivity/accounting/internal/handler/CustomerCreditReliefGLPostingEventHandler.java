package com.positivity.accounting.internal.handler;

import com.positivity.accounting.internal.dto.CustomerCreditReliefGLPostingEvent;
import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.service.CustomerCreditPostingLifecycleService;
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
 * Event handler for customer-credit <em>relief</em> GL posting work items (parity-C1
 * follow-on, issue #992).
 *
 * <p>Consumes {@link CustomerCreditReliefGLPostingEvent} work items delivered from the
 * transactional outbox — mirroring {@link CustomerCreditIssuanceGLPostingEventHandler} —
 * and posts the discharge of the Customer Credit Liability recognized at issuance:
 *
 * <ul>
 *   <li>{@code APPLICATION} → Dr Customer Credit Liability / Cr Accounts Receivable</li>
 *   <li>{@code REFUND} → Dr Customer Credit Liability / Cr Undeposited Funds</li>
 * </ul>
 *
 * <p>Accounts are never hardcoded: both sides resolve through the relief's posting category
 * ({@code CUSTOMER_CREDIT_APPLICATION} / {@code CUSTOMER_CREDIT_REFUND}) and its mapping
 * keys, seeded by {@code R__seed_reference_accounting.sql}.
 *
 * <p>Idempotency: the posting key is the caller-supplied request id, namespaced per relief
 * type, so a replayed or duplicate work item is a no-op and a relief never double-posts.
 * The key is registered in the same transaction as the posted journal entry, alongside the
 * write-back of {@code gl_journal_entry_id} / {@code gl_posted_at} onto the draw-down row —
 * so the subledger always points at the entry that discharged it.
 *
 * <p>Failure semantics mirror the issuance leg: any exception propagates to
 * {@link com.positivity.accounting.internal.service.OutboxProcessor}, which marks the work
 * item failed and retries with backoff. Posting into a CLOSED or hard-locked period surfaces
 * the Wave 2 period-gate exceptions unwrapped so the failure reason stays visible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerCreditReliefGLPostingEventHandler {

    static final String APPLICATION_POSTING_CATEGORY_NAME = "CUSTOMER_CREDIT_APPLICATION";
    static final String REFUND_POSTING_CATEGORY_NAME = "CUSTOMER_CREDIT_REFUND";
    static final String DEBIT_MAPPING_KEY = "CUSTOMER_CREDIT_LIABILITY";
    static final String APPLICATION_CREDIT_MAPPING_KEY = "ACCOUNTS_RECEIVABLE";
    static final String REFUND_CREDIT_MAPPING_KEY = "UNDEPOSITED_FUNDS";
    static final String IDEMPOTENCY_KEY_PREFIX = "CUSTOMER_CREDIT_RELIEF_GL_POSTING:";
    static final String SOURCE_EVENT_NAMESPACE = "CUSTOMER_CREDIT_RELIEF:";

    private final Clock clock;
    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;
    private final CustomerCreditPostingLifecycleService postingLifecycleService;

    /**
     * Handle a customer-credit relief GL posting work item → post the balanced journal entry
     * that discharges the liability, then link it back to the draw-down row.
     *
     * @param event customer-credit relief GL posting work item
     */
    @EventListener
    @Transactional
    public void onCustomerCreditReliefGLPosting(@NonNull CustomerCreditReliefGLPostingEvent event) {
        String requestId = event.getRequestId();
        CustomerCreditTransactionType type = event.getTransactionType();
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + type + ":" + requestId;

        if (idempotencyService.isKeyProcessed(idempotencyKey)) {
            log.info(
                    "Customer credit relief GL posting already processed, skipping | requestId={} | type={} "
                            + "| creditId={} | eventId={}",
                    requestId,
                    type,
                    event.getCreditId(),
                    event.getEventId());
            return;
        }

        log.info(
                "Received CustomerCreditReliefGLPostingEvent | eventId={} | requestId={} | type={} | creditId={} "
                        + "| invoiceId={} | amount={}",
                event.getEventId(),
                requestId,
                type,
                event.getCreditId(),
                event.getInvoiceId(),
                event.getAmount());

        try {
            // Transaction date is the event's business timestamp (when the draw-down actually
            // happened), NOT the processing/clock time — so the relief lands in the period it
            // occurred in and resolves the same effective-dated GL mapping across replays.
            LocalDateTime transactionDate = LocalDateTime.ofInstant(event.getReliefTimestamp(), clock.getZone());

            boolean application = type == CustomerCreditTransactionType.APPLICATION;
            String postingCategory = application ? APPLICATION_POSTING_CATEGORY_NAME : REFUND_POSTING_CATEGORY_NAME;
            String contraMappingKey = application ? APPLICATION_CREDIT_MAPPING_KEY : REFUND_CREDIT_MAPPING_KEY;
            String contraLineLabel = application ? "AR Reduction" : "Customer Credit Refund";

            UUID creditLiabilityAccountId =
                    glMappingResolver.resolveGLAccount(postingCategory, DEBIT_MAPPING_KEY, transactionDate);
            UUID contraAccountId =
                    glMappingResolver.resolveGLAccount(postingCategory, contraMappingKey, transactionDate);

            UUID sourceEventId = toSourceEventId(type, requestId);
            String description = "Customer credit " + (application ? "application" : "refund") + " for request "
                    + requestId + " (credit " + event.getCreditId()
                    + (application ? ", invoice " + event.getInvoiceId() : "") + ")";

            var posted = glPostingService.postCustomerCreditRelief(
                    sourceEventId,
                    event.getCreditId(),
                    creditLiabilityAccountId,
                    contraAccountId,
                    event.getAmount(),
                    transactionDate,
                    description,
                    contraLineLabel,
                    null);

            idempotencyService.registerKey(idempotencyKey, posted);
            postingLifecycleService.recordReliefPosting(event.getCreditTransactionId(), posted);

            log.info(
                    "Customer credit relief GL posting completed | requestId={} | type={} | creditId={} "
                            + "| journalEntryId={}",
                    requestId,
                    type,
                    event.getCreditId(),
                    posted);

        } catch (AccountingPeriodClosedException | AccountingPeriodHardLockedException e) {
            // Wave 2 period gate: propagate unwrapped so the failure reason stays visible in the
            // outbox retry record; retry succeeds once the period is reopened (or the hard lock
            // moved).
            log.warn(
                    "Customer credit relief GL posting blocked by period gate | requestId={} | error={}",
                    requestId,
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error(
                    "Failed to process CustomerCreditReliefGLPostingEvent | requestId={} | eventId={} | error={}",
                    requestId,
                    event.getEventId(),
                    e.getMessage(),
                    e);
            throw new IllegalStateException("GL posting failed for customer credit relief on request: " + requestId, e);
        }
    }

    /**
     * Derive the journal entry {@code sourceEventId} from the relief type and the
     * caller-supplied request id, namespaced so it never collides with any other entry derived
     * from the same request id. The mapping is deterministic, so replays of the same request id
     * always produce the same source event id.
     *
     * @param type      relief type (part of the namespace)
     * @param requestId caller-supplied request id
     * @return deterministic journal entry source id
     */
    static @NonNull UUID toSourceEventId(@NonNull CustomerCreditTransactionType type, @NonNull String requestId) {
        return UUID.nameUUIDFromBytes(
                (SOURCE_EVENT_NAMESPACE + type + ":" + requestId).getBytes(StandardCharsets.UTF_8));
    }
}
