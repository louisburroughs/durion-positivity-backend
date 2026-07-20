package com.positivity.accounting.internal.handler;

import com.positivity.accounting.internal.dto.PaymentApplicationReversalGLPostingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.JournalEntryNotReversibleException;
import com.positivity.accounting.service.IdempotencyService;
import com.positivity.accounting.service.JournalEntryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event handler for AR payment-application reversal GL posting work items
 * (story C2, issue #958).
 *
 * <p>
 * Consumes {@link PaymentApplicationReversalGLPostingEvent} work items delivered
 * from the transactional outbox (mirroring the C1
 * {@link PaymentApplicationGLPostingEventHandler} async pattern) and posts a
 * <em>reversing</em> journal entry so an apply→reverse cycle nets to zero in the
 * GL while leaving a full audit trail (original entry + reversing entry).
 *
 * <p>
 * The reversing entry is never hand-built. The handler locates the original C1
 * cash-receipt entry by its posting key — {@code applicationRequestId} mapped to
 * the journal entry {@code sourceEventId} via
 * {@link PaymentApplicationGLPostingEventHandler#toSourceEventId(String)} — and
 * delegates to A3's {@link JournalEntryService#reverseJournalEntry} (issue
 * #943). That service inverts every line, assigns the reversal its own posted
 * entry number, flips the original POSTED → REVERSED, and runs the resolved
 * reversal date through the full B2 period gate (issue #944): a reversal whose
 * original period is now CLOSED defaults to the current open date, and only when
 * both the original and current periods are closed does it surface a period-gate
 * exception for retry.
 *
 * <p>
 * Idempotency: the posting key is the application request id (namespaced with
 * {@code PAYMENT_APPLICATION_REVERSAL_GL_POSTING:}). A replayed work item — or a
 * second reversal event for another application of the same multi-invoice
 * request — is a no-op, so exactly one reversing entry is ever posted. The key
 * is registered in the same transaction as the reversing entry. If the original
 * entry is already REVERSED (this path replayed, or a manual A3 reversal ran),
 * the handler records the key and no-ops rather than failing.
 *
 * <p>
 * Failure semantics mirror C1: the C1 cash-receipt entry may not exist yet when
 * this runs (outbox ordering), so a missing original is retryable; period-gate
 * exceptions propagate unwrapped so the retry reason stays visible; any other
 * failure is wrapped so
 * {@link com.positivity.accounting.internal.service.OutboxProcessor} retries with
 * backoff.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/958">Issue
 *      #958 (story C2)</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentApplicationReversalGLPostingEventHandler {

    static final String IDEMPOTENCY_KEY_PREFIX = "PAYMENT_APPLICATION_REVERSAL_GL_POSTING:";

    private final IdempotencyService idempotencyService;
    private final JournalEntryService journalEntryService;

    /**
     * Handle a payment-application reversal GL posting work item → post a
     * reversing journal entry against the original cash-receipt entry.
     *
     * @param event payment-application reversal GL posting work item
     */
    @EventListener
    @Transactional
    public void onPaymentApplicationReversalGLPosting(@NonNull PaymentApplicationReversalGLPostingEvent event) {
        String applicationRequestId = event.getApplicationRequestId();
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + applicationRequestId;

        if (idempotencyService.isKeyProcessed(idempotencyKey)) {
            log.info(
                    "Payment application reversal GL posting already processed, skipping | applicationRequestId={} "
                            + "| eventId={}",
                    applicationRequestId,
                    event.getEventId());
            return;
        }

        log.info(
                "Received PaymentApplicationReversalGLPostingEvent | eventId={} | applicationRequestId={} "
                        + "| reversalId={} | paymentId={}",
                event.getEventId(),
                applicationRequestId,
                event.getReversalId(),
                event.getPaymentId());

        try {
            UUID sourceEventId = PaymentApplicationGLPostingEventHandler.toSourceEventId(applicationRequestId);

            // The original cash-receipt entry is the sole non-reversal entry
            // carrying this sourceEventId. (A reversal entry, once posted, shares
            // the same sourceEventId but has a non-null reversalJournalEntry link,
            // so it is excluded by the service query.) Repository access is kept
            // behind the service layer per the module's architecture rules.
            JournalEntry original =
                    journalEntryService.findOriginalBySourceEvent(sourceEventId).orElse(null);

            if (original == null) {
                // C1 posting has not committed yet (outbox ordering) — retryable.
                throw new IllegalStateException(
                        "Original cash-receipt journal entry not found for reversal; applicationRequestId="
                                + applicationRequestId);
            }

            if (original.getStatus() == JournalEntryStatus.REVERSED) {
                // Already reversed (this work item replayed after commit, or a
                // manual A3 reversal ran). Record the key and no-op so the GL is
                // not double-reversed.
                log.info(
                        "Original cash-receipt entry {} already REVERSED; recording idempotency key without re-posting"
                                + " | applicationRequestId={}",
                        original.getJournalEntryId(),
                        applicationRequestId);
                idempotencyService.registerKey(idempotencyKey, original.getJournalEntryId());
                return;
            }

            // Reversal date resolution + period gate are A3/B2 concerns: pass a
            // null reversalDate (default: original's date if its period is open,
            // else current date) and no override (async worker holds no override
            // authority). Reuses the existing period-enforcement path.
            JournalEntry reversal = journalEntryService.reverseJournalEntry(
                    original.getJournalEntryId(), event.getReason(), null, null);

            idempotencyService.registerKey(idempotencyKey, reversal.getJournalEntryId());

            log.info(
                    "Payment application reversal GL posting completed | applicationRequestId={} "
                            + "| originalJournalEntryId={} | reversingJournalEntryId={}",
                    applicationRequestId,
                    original.getJournalEntryId(),
                    reversal.getJournalEntryId());

        } catch (AccountingPeriodClosedException | AccountingPeriodHardLockedException e) {
            // B2 period gate: both the original and current periods are closed
            // (or the reversal date is hard-locked). Propagate unwrapped so the
            // failure reason stays visible in the outbox retry record; retry
            // succeeds once a period is reopened / the hard lock moves.
            log.warn(
                    "Payment application reversal GL posting blocked by period gate | applicationRequestId={} "
                            + "| error={}",
                    applicationRequestId,
                    e.getMessage());
            throw e;
        } catch (JournalEntryNotReversibleException e) {
            if (e.getCurrentStatus() == JournalEntryStatus.REVERSED) {
                // Lost a concurrent reversal race — the other transaction reversed
                // it. Idempotent success: record the key and no-op.
                log.info(
                        "Original cash-receipt entry {} concurrently REVERSED; recording idempotency key"
                                + " | applicationRequestId={}",
                        e.getJournalEntryId(),
                        applicationRequestId);
                idempotencyService.registerKey(idempotencyKey, e.getJournalEntryId());
                return;
            }
            log.error(
                    "Failed to reverse cash-receipt entry (unexpected status) | applicationRequestId={} | error={}",
                    applicationRequestId,
                    e.getMessage(),
                    e);
            throw new IllegalStateException(
                    "Reversal GL posting failed for payment application request: " + applicationRequestId, e);
        } catch (Exception e) {
            log.error(
                    "Failed to process PaymentApplicationReversalGLPostingEvent | applicationRequestId={} "
                            + "| eventId={} | error={}",
                    applicationRequestId,
                    event.getEventId(),
                    e.getMessage(),
                    e);
            throw new IllegalStateException(
                    "Reversal GL posting failed for payment application request: " + applicationRequestId, e);
        }
    }
}
