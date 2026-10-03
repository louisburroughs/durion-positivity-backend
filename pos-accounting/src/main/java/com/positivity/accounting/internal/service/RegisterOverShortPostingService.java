package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
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
 * {@link com.positivity.accounting.internal.service.JournalEntryService#postJournalEntry}; a CLOSED period
 * propagates unwrapped for container retry / DLQ.
 *
 * <p><b>Currency (ADR-0067 PC-9, E-5; #2312):</b> a session closed in a currency other than the
 * ledger's ({@link LedgerCurrency}) is never posted at par. It is held as a {@code SUSPENDED}
 * ingestion record with {@code failureReasonCode = CURRENCY_NOT_SUPPORTED} (#2334), once per
 * session, and nothing is posted; the auto-retry loop skips it and the audited reprocess path
 * releases it.
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
    static final String SOURCE_SYSTEM = "pos-order";

    private final Clock clock;
    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;
    private final LedgerCurrency ledgerCurrency;
    private final KafkaFactIngestionRecorder ingestionRecorder;

    /**
     * Post the drawer over/short variance for a closed session, exactly once per sessionId. A
     * zero-variance close posts nothing.
     *
     * @param fact the consumed session-closed fact
     * @param envelopeEventId the consumed envelope's event id, kept on a currency-held record
     * @return what the fact did, for the listener's ingestion record (#2433)
     */
    @Transactional
    public @NonNull FactPostingOutcome postOverShort(
            @NonNull RegisterSessionClosedV1 fact, @NonNull String envelopeEventId) {
        BigDecimal overShort = fact.overShort();
        if (overShort == null || overShort.signum() == 0) {
            log.debug("Zero-variance register close, nothing to post | sessionId={}", fact.sessionId());
            return FactPostingOutcome.nothingToPost();
        }

        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + fact.sessionId();
        if (idempotencyService.isKeyProcessed(idempotencyKey)) {
            log.info("Register over/short GL posting already processed, skipping | sessionId={}", fact.sessionId());
            return new FactPostingOutcome.AlreadyPosted(null, toSourceEventId(fact.sessionId()));
        }

        // Business time, not processing time: redeliveries land in the same period.
        LocalDateTime transactionDate = LocalDateTime.ofInstant(fact.closedAt(), clock.getZone());

        // Never at par (ADR-0067 PC-9, E-5; #2312): a variance counted in another currency is held
        // visibly with its currency reason, not posted into the ledger's currency.
        if (ledgerCurrency.isForeign(fact.currencyCode())) {
            holdForeignCurrency(fact, envelopeEventId, transactionDate);
            return new FactPostingOutcome.CurrencyHeld();
        }
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

        UUID posted = glPostingService.postRegisterOverShort(
                toSourceEventId(fact.sessionId()),
                fact.sessionId(),
                debitAccountId,
                creditAccountId,
                amount,
                transactionDate,
                description,
                null);

        idempotencyService.registerKey(idempotencyKey, posted);

        log.info(
                "Register over/short GL posting completed | sessionId={} | terminalId={} | direction={} | amount={} "
                        + "| journalEntryId={}",
                fact.sessionId(),
                fact.terminalId(),
                shortage ? "SHORTAGE" : "OVERAGE",
                amount,
                posted);
        return FactPostingOutcome.posted(posted);
    }

    private void holdForeignCurrency(
            RegisterSessionClosedV1 fact, String envelopeEventId, LocalDateTime transactionDate) {
        String detail = "Register over/short of " + fact.overShort() + " " + fact.currencyCode()
                + " not posted: the ledger books " + ledgerCurrency.code()
                + " only and a variance in another currency is never booked at par (ADR-0067 PC-9)";
        boolean recorded = ingestionRecorder.recordCurrencyHeld(
                SOURCE_SYSTEM,
                RegisterSessionClosedV1.EVENT_TYPE,
                envelopeEventId,
                fact.sessionId(),
                transactionDate,
                fact,
                detail);
        log.warn(
                "Register over/short held for its currency, not posted | sessionId={} | currency={} "
                        + "| ledgerCurrency={} | newRecord={}",
                fact.sessionId(),
                fact.currencyCode(),
                ledgerCurrency.code(),
                recorded);
    }

    /**
     * Derive the journal entry {@code sourceEventId} deterministically from the session id,
     * namespaced so it never collides with another entry deriving from the same id.
     */
    static @NonNull UUID toSourceEventId(@NonNull UUID sessionId) {
        return UUID.nameUUIDFromBytes((SOURCE_EVENT_NAMESPACE + sessionId).getBytes(StandardCharsets.UTF_8));
    }
}
