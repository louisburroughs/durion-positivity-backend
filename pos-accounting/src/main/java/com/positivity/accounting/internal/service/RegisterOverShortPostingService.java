package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.service.GLMappingResolver;
import com.positivity.accounting.service.GLPostingService;
import com.positivity.accounting.service.IdempotencyService;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts the drawer over/short variance for a closed register session (odoo-parity G3, issue #1083;
 * spec R6.4). The Odoo cash-difference posting analog.
 *
 * <p><b>Direction:</b> a shortage (counted &lt; theoretical, {@code overShort &lt; 0}) posts
 * {@code Dr Cash Short (expense) / Cr Register Cash Clearing}; an overage
 * ({@code overShort &gt; 0}) posts {@code Dr Register Cash Clearing / Cr Cash Over (income)}. A
 * zero-variance close posts nothing (spec AC).
 *
 * <p>Accounts are never hardcoded: all three legs resolve through the {@code REGISTER_OVER_SHORT}
 * posting category and its {@code CASH_SHORT} / {@code CASH_OVER} / {@code CASH_CLEARING} mapping
 * keys (seeded by {@code R__seed_reference_accounting.sql}).
 *
 * <p>Idempotency (mirrors {@link InventoryShrinkagePostingService}): the posting key is the session
 * id, namespaced with {@code REGISTER_OVER_SHORT_GL_POSTING:}, registered in the same transaction as
 * the posted journal entry — so a session variance posts exactly once per sessionId even if the fact
 * is redelivered under a fresh Kafka {@code eventId}.
 *
 * <p>The journal entry's transaction date is the session's {@code closedAt} (business time), never
 * processing/clock time, so redeliveries post into the same accounting period and resolve the same
 * effective-dated GL mapping. The period gate applies inside
 * {@link com.positivity.accounting.service.JournalEntryService#postJournalEntry}; a CLOSED period
 * propagates unwrapped for container retry / DLQ.
 *
 * <p>Per-order revenue postings remain authoritative — this carries only the drawer variance
 * (spec §14), never a consolidated closing entry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegisterOverShortPostingService {

    static final String POSTING_CATEGORY_NAME = "REGISTER_OVER_SHORT";
    static final String CASH_SHORT_KEY = "CASH_SHORT";
    static final String CASH_OVER_KEY = "CASH_OVER";
    static final String CASH_CLEARING_KEY = "CASH_CLEARING";
    static final String IDEMPOTENCY_KEY_PREFIX = "REGISTER_OVER_SHORT_GL_POSTING:";
    static final String SOURCE_EVENT_NAMESPACE = "REGISTER_OVER_SHORT:";

    private final Clock clock;
    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;

    /**
     * Post the drawer over/short variance for a closed session, exactly once per sessionId. A
     * zero-variance close posts nothing.
     *
     * @param fact the consumed session-closed fact
     */
    @Transactional
    public void postOverShort(@NonNull RegisterSessionClosedV1 fact) {
        BigDecimal overShort = fact.overShort();
        if (overShort == null || overShort.signum() == 0) {
            log.debug("Zero-variance register close, nothing to post | sessionId={}", fact.sessionId());
            return;
        }

        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + fact.sessionId();
        if (idempotencyService.isKeyProcessed(idempotencyKey)) {
            log.info("Register over/short GL posting already processed, skipping | sessionId={}", fact.sessionId());
            return;
        }

        // Business time, not processing time: redeliveries land in the same period.
        LocalDateTime transactionDate = LocalDateTime.ofInstant(fact.closedAt(), clock.getZone());
        BigDecimal amount = overShort.abs();

        boolean shortage = overShort.signum() < 0;
        UUID debitAccountId;
        UUID creditAccountId;
        if (shortage) {
            // Dr Cash Short (expense) / Cr Register Cash Clearing
            debitAccountId = glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, CASH_SHORT_KEY, transactionDate);
            creditAccountId =
                    glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, CASH_CLEARING_KEY, transactionDate);
        } else {
            // Dr Register Cash Clearing / Cr Cash Over (income)
            debitAccountId =
                    glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, CASH_CLEARING_KEY, transactionDate);
            creditAccountId = glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, CASH_OVER_KEY, transactionDate);
        }

        String description = "Register drawer " + (shortage ? "shortage" : "overage") + " for session "
                + fact.sessionId() + " (terminal " + fact.terminalId() + ", counted " + fact.countedCash()
                + " vs theoretical " + fact.theoreticalCash() + ")";

        JournalEntry posted = glPostingService.postRegisterOverShort(
                toSourceEventId(fact.sessionId()),
                fact.sessionId(),
                debitAccountId,
                creditAccountId,
                amount,
                transactionDate,
                description,
                null);

        idempotencyService.registerKey(idempotencyKey, posted.getJournalEntryId());

        log.info(
                "Register over/short GL posting completed | sessionId={} | terminalId={} | direction={} | amount={} "
                        + "| journalEntryId={}",
                fact.sessionId(),
                fact.terminalId(),
                shortage ? "SHORTAGE" : "OVERAGE",
                amount,
                posted.getJournalEntryId());
    }

    /**
     * Derive the journal entry {@code sourceEventId} deterministically from the session id,
     * namespaced so it never collides with another entry deriving from the same id.
     */
    static @NonNull UUID toSourceEventId(@NonNull UUID sessionId) {
        return UUID.nameUUIDFromBytes((SOURCE_EVENT_NAMESPACE + sessionId).getBytes(StandardCharsets.UTF_8));
    }
}
