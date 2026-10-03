package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the AD-007 ingestion record for a Kafka-consumed posting fact (issues #2191, #2433, spec
 * §4.9, #2186 decision D5): one terminal {@link AccountingEvent} row per consumed fact, in the
 * listener's handler transaction, so what accounting received and what it did with it (the posted
 * journal entry, a deliberate skip, nothing to post) is visible through {@code
 * listAccountingEvents?eventType=…&domainKeyId=…} without log access. Domain-neutral: every
 * posting listener (inventory, invoice revenue, register over/short, supplier invoice, warranty
 * reimbursement, settlement currency holds) passes its producing module as {@code sourceSystem}.
 *
 * <p><b>Terminal states only.</b> A row is {@link AccountingEventStatus#PROCESSED} (posted, a fact
 * that legitimately posts nothing, or a fact whose posting key was already registered) or {@link
 * AccountingEventStatus#SKIPPED} (deliberately not posted, with a {@code failureReasonCode}). Never
 * {@code FAILED}, and {@code SUSPENDED} only for a currency hold (below): the REST retry scheduler
 * and {@code retryAccountingEvent} select those and would run the fact through posting rule sets
 * that do not exist. Failures that propagate (closed period, missing mapping, transient) roll this
 * row back with the handler and are visible on the DLQ instead. A redelivery of the same Kafka
 * envelope is short-circuited by {@code processed_events} before any posting and writes no row.
 *
 * <p>{@code eventReference} is the module's display reference {@code AE-{YYYYMM}-{seq}} (a
 * 20-character column, unique per tenant), assigned from the same per-month {@code
 * accounting_sequence} counter as {@code EventIngestionServiceImpl#assignEventReference}. The fact's
 * domain id (scrap id, invoice id, session id, vendor bill id, reimbursement id) goes in {@code
 * domainKeyId}, which is neither unique nor short: every fact about the same document (an invoice
 * finalized, then posted, then cancelled) writes its own row under the same key.
 *
 * <p>It also holds a fact whose amount is in a currency other than the ledger's ({@link
 * #recordCurrencyHeld}, ADR-0067 PC-9, issues #2312, #2334): {@code SUSPENDED} with {@code
 * failureReasonCode = CURRENCY_NOT_SUPPORTED}. SUSPENDED, not SKIPPED, so the hold is releasable
 * through the audited reprocess path; the scheduled auto-retry loop skips it ({@link
 * PostingFailureReason#isExcludedFromAutoRetry()}) and the posting engine re-suspends it while its
 * currency is still not the ledger's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaFactIngestionRecorder {

    private static final String EVENT_REFERENCE_SCOPE_PREFIX = "AE-";
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AccountingEventRepository accountingEventRepository;
    private final AccountingSequenceLocker sequenceLocker;
    private final JournalEntryRepository journalEntryRepository;

    /**
     * Record a consumed fact by what its posting path reported (issue #2433). {@link
     * FactPostingOutcome.CurrencyHeld} writes nothing: the posting path recorded the hold itself.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            @NonNull String sourceSystem,
            @NonNull String eventType,
            @NonNull String envelopeEventId,
            @NonNull UUID domainKeyId,
            @NonNull LocalDateTime transactionDate,
            @NonNull Object fact,
            @NonNull FactPostingOutcome outcome) {
        switch (outcome) {
            case FactPostingOutcome.Posted posted ->
                recordPosted(
                        sourceSystem,
                        eventType,
                        envelopeEventId,
                        domainKeyId,
                        transactionDate,
                        fact,
                        posted.journalEntryId(),
                        null);
            case FactPostingOutcome.AlreadyPosted already -> {
                AccountingEvent event =
                        newEvent(sourceSystem, eventType, envelopeEventId, domainKeyId, transactionDate, fact);
                event.setStatus(AccountingEventStatus.PROCESSED);
                event.setIdempotencyOutcome(IdempotencyOutcome.DUPLICATE_IGNORED.name());
                event.setJournalEntryId(
                        already.journalEntryId() != null
                                ? already.journalEntryId()
                                : earlierEntry(already.sourceEventId()));
                save(event);
            }
            case FactPostingOutcome.NothingToPost _ ->
                recordNothingToPost(sourceSystem, eventType, envelopeEventId, domainKeyId, transactionDate, fact);
            case FactPostingOutcome.Skipped skipped ->
                recordSkipped(
                        sourceSystem,
                        eventType,
                        envelopeEventId,
                        domainKeyId,
                        transactionDate,
                        fact,
                        skipped.reason(),
                        skipped.detail());
            case FactPostingOutcome.CurrencyHeld _ ->
                log.debug(
                        "Fact held for its currency by its posting path, no further record | eventType={} | domainKeyId={}",
                        eventType,
                        domainKeyId);
        }
    }

    /**
     * Record a fact that reached posting: {@code PROCESSED}, outcome {@code NEW} with the posted
     * entry, or {@code DUPLICATE_IGNORED} with the entry an earlier delivery posted (looked up by
     * its deterministic {@code sourceEventId}) when the posting key was already registered.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPosted(
            @NonNull String sourceSystem,
            @NonNull String eventType,
            @NonNull String envelopeEventId,
            @NonNull UUID domainKeyId,
            @NonNull LocalDateTime transactionDate,
            @NonNull Object fact,
            @Nullable UUID postedJournalEntryId,
            @Nullable UUID sourceEventId) {
        AccountingEvent event = newEvent(sourceSystem, eventType, envelopeEventId, domainKeyId, transactionDate, fact);
        event.setStatus(AccountingEventStatus.PROCESSED);
        if (postedJournalEntryId != null) {
            event.setIdempotencyOutcome(IdempotencyOutcome.NEW.name());
            event.setJournalEntryId(postedJournalEntryId);
        } else {
            event.setIdempotencyOutcome(IdempotencyOutcome.DUPLICATE_IGNORED.name());
            event.setJournalEntryId(earlierEntry(sourceEventId));
        }
        save(event);
    }

    /**
     * A newly consumed fact that legitimately posts no journal entry (a zero-delta revaluation,
     * #2193; a vendor bill or warranty expectation, #2433): {@code PROCESSED}, outcome {@code NEW},
     * no journal entry. Distinct from a duplicate, which {@link #recordPosted} records when the
     * handler returns no new entry.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordNothingToPost(
            @NonNull String sourceSystem,
            @NonNull String eventType,
            @NonNull String envelopeEventId,
            @NonNull UUID domainKeyId,
            @NonNull LocalDateTime transactionDate,
            @NonNull Object fact) {
        AccountingEvent event = newEvent(sourceSystem, eventType, envelopeEventId, domainKeyId, transactionDate, fact);
        event.setStatus(AccountingEventStatus.PROCESSED);
        event.setIdempotencyOutcome(IdempotencyOutcome.NEW.name());
        save(event);
    }

    /** Record an uncosted fact that was deliberately not posted: {@code SKIPPED / UNCOSTED_FACT}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUncostedSkip(
            @NonNull String sourceSystem,
            @NonNull String eventType,
            @NonNull String envelopeEventId,
            @NonNull UUID domainKeyId,
            @NonNull LocalDateTime transactionDate,
            @NonNull Object fact,
            @NonNull String detail) {
        recordSkipped(
                sourceSystem,
                eventType,
                envelopeEventId,
                domainKeyId,
                transactionDate,
                fact,
                PostingFailureReason.UNCOSTED_FACT,
                detail);
    }

    /** Record a fact deliberately not posted: terminal {@code SKIPPED} with its reason code. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSkipped(
            @NonNull String sourceSystem,
            @NonNull String eventType,
            @NonNull String envelopeEventId,
            @NonNull UUID domainKeyId,
            @NonNull LocalDateTime transactionDate,
            @NonNull Object fact,
            @NonNull PostingFailureReason reason,
            @NonNull String detail) {
        AccountingEvent event = newEvent(sourceSystem, eventType, envelopeEventId, domainKeyId, transactionDate, fact);
        event.setStatus(AccountingEventStatus.SKIPPED);
        event.setIdempotencyOutcome(IdempotencyOutcome.NEW.name());
        event.setFailureReasonCode(reason.name());
        event.setErrorMessage(detail);
        save(event);
    }

    /**
     * Hold a consumed fact whose amount is in a currency other than the ledger's (ADR-0067 PC-9,
     * E-5, issues #2312, #2334): {@code SUSPENDED / CURRENCY_NOT_SUPPORTED}, with the currency in
     * the error message, findable through {@code listAccountingEvents?eventType=…&domainKeyId=…} and
     * releasable through {@code reprocessSuspendedEvent}. Nothing is posted. A redelivered fact
     * already held is not recorded twice.
     *
     * @param sourceSystem the producing module, e.g. {@code pos-order}
     * @param envelopeEventId the consumed envelope's event id, kept as the record's {@code
     *     ingestionId} like every other consumed fact's record
     * @return whether a new held record was written
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordCurrencyHeld(
            @NonNull String sourceSystem,
            @NonNull String eventType,
            @NonNull String envelopeEventId,
            @NonNull UUID domainKeyId,
            @NonNull LocalDateTime transactionDate,
            @NonNull Object fact,
            @NonNull String detail) {
        String reason = PostingFailureReason.CURRENCY_NOT_SUPPORTED.name();
        if (accountingEventRepository.existsByEventTypeAndDomainKeyIdAndFailureReasonCode(
                eventType, domainKeyId.toString(), reason)) {
            log.info(
                    "Fact already held for its currency, not recorded again | eventType={} | domainKeyId={}",
                    eventType,
                    domainKeyId);
            return false;
        }
        AccountingEvent event = newEvent(sourceSystem, eventType, envelopeEventId, domainKeyId, transactionDate, fact);
        event.setStatus(AccountingEventStatus.SUSPENDED);
        event.setIdempotencyOutcome(IdempotencyOutcome.NEW.name());
        event.setFailureReasonCode(reason);
        event.setFailureDetails(detail);
        event.setErrorMessage(detail);
        save(event);
        return true;
    }

    private @Nullable UUID earlierEntry(@Nullable UUID sourceEventId) {
        if (sourceEventId == null) {
            return null;
        }
        return journalEntryRepository.findBySourceEvent(sourceEventId).stream()
                .map(JournalEntry::getJournalEntryId)
                .findFirst()
                .orElse(null);
    }

    private AccountingEvent newEvent(
            String sourceSystem,
            String eventType,
            String envelopeEventId,
            UUID domainKeyId,
            LocalDateTime transactionDate,
            Object fact) {
        AccountingEvent event = new AccountingEvent();
        event.setEventType(eventType);
        event.setSourceSystem(sourceSystem);
        event.setDomainKeyId(domainKeyId.toString());
        event.setTransactionDate(transactionDate);
        event.setPayload(objectMapper.convertValue(fact, PAYLOAD_TYPE));
        event.setIngestionId(parseUuid(envelopeEventId));
        event.setProcessedAt(Instant.now(clock));
        return event;
    }

    private void save(AccountingEvent event) {
        AccountingEvent saved = accountingEventRepository.save(event);
        assignEventReference(saved);
        log.debug(
                "Recorded Kafka fact ingestion | sourceSystem={} | eventType={} | domainKeyId={} | status={} | outcome={}",
                saved.getSourceSystem(),
                saved.getEventType(),
                saved.getDomainKeyId(),
                saved.getStatus(),
                saved.getIdempotencyOutcome());
    }

    /** Same numbering as {@code EventIngestionServiceImpl#assignEventReference} (issue #1680). */
    private void assignEventReference(AccountingEvent event) {
        ZonedDateTime received = event.getReceivedAt().atZone(ZoneOffset.UTC);
        String scopeKey =
                String.format("%s%04d%02d", EVENT_REFERENCE_SCOPE_PREFIX, received.getYear(), received.getMonthValue());
        AccountingSequence sequence = sequenceLocker.lockOrProvision(scopeKey);
        long assigned = sequence.getNextValue();
        sequence.setNextValue(assigned + 1);
        event.setEventReference(scopeKey + "-" + assigned);
    }

    private static @Nullable UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
