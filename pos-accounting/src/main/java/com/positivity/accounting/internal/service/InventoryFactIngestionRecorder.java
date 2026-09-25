package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the AD-007 ingestion record for a Kafka-consumed inventory posting fact (issue #2191,
 * spec §4.9, #2186 decision D5): one terminal {@link AccountingEvent} row per consumed fact, in the
 * listener's handler transaction, so the uncosted skip and the posted journal entry are visible
 * through {@code listAccountingEvents?eventType=…&domainKeyId=…} without log access.
 *
 * <p><b>Terminal states only.</b> A row is {@link AccountingEventStatus#PROCESSED} (posted, or a
 * re-emitted fact whose posting key was already registered) or {@link AccountingEventStatus#SKIPPED}
 * (uncosted, {@code failureReasonCode = UNCOSTED_FACT}). Never {@code FAILED} or {@code SUSPENDED}:
 * the REST retry scheduler and {@code retryAccountingEvent} select those and would run the fact
 * through posting rule sets that do not exist. Failures that propagate (closed period, missing
 * mapping, transient) roll this row back with the handler and are visible on the DLQ instead.
 *
 * <p>{@code eventReference} is the module's display reference {@code AE-{YYYYMM}-{seq}}, assigned
 * from the same per-month {@code accounting_sequence} counter as {@code
 * EventIngestionServiceImpl#assignEventReference}. The fact's own id goes in {@code domainKeyId}:
 * the column is 20 characters and unique per tenant, so it cannot hold a UUID, and a re-emitted
 * fact writes a second row for the same id.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryFactIngestionRecorder {

    static final String SOURCE_SYSTEM = "pos-inventory";
    static final String OUTCOME_NEW = "NEW";
    static final String OUTCOME_DUPLICATE_IGNORED = "DUPLICATE_IGNORED";
    private static final String EVENT_REFERENCE_SCOPE_PREFIX = "AE-";
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AccountingEventRepository accountingEventRepository;
    private final AccountingSequenceRepository sequenceRepository;
    private final AccountingSequenceProvisioner sequenceProvisioner;
    private final JournalEntryRepository journalEntryRepository;

    /**
     * Record a fact that reached posting: {@code PROCESSED}, outcome {@code NEW} with the posted
     * entry, or {@code DUPLICATE_IGNORED} with the entry an earlier delivery posted (looked up by
     * its deterministic {@code sourceEventId}) when the posting key was already registered.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPosted(
            @NonNull String eventType,
            @NonNull String envelopeEventId,
            @NonNull UUID domainKeyId,
            @NonNull LocalDateTime transactionDate,
            @NonNull Object fact,
            @Nullable UUID postedJournalEntryId,
            @NonNull UUID sourceEventId) {
        AccountingEvent event = newEvent(eventType, envelopeEventId, domainKeyId, transactionDate, fact);
        event.setStatus(AccountingEventStatus.PROCESSED);
        if (postedJournalEntryId != null) {
            event.setIdempotencyOutcome(OUTCOME_NEW);
            event.setJournalEntryId(postedJournalEntryId);
        } else {
            event.setIdempotencyOutcome(OUTCOME_DUPLICATE_IGNORED);
            event.setJournalEntryId(journalEntryRepository.findBySourceEvent(sourceEventId).stream()
                    .map(JournalEntry::getJournalEntryId)
                    .findFirst()
                    .orElse(null));
        }
        save(event);
    }

    /** Record an uncosted fact that was deliberately not posted: {@code SKIPPED / UNCOSTED_FACT}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUncostedSkip(
            @NonNull String eventType,
            @NonNull String envelopeEventId,
            @NonNull UUID domainKeyId,
            @NonNull LocalDateTime transactionDate,
            @NonNull Object fact,
            @NonNull String detail) {
        AccountingEvent event = newEvent(eventType, envelopeEventId, domainKeyId, transactionDate, fact);
        event.setStatus(AccountingEventStatus.SKIPPED);
        event.setIdempotencyOutcome(OUTCOME_NEW);
        event.setFailureReasonCode(PostingFailureReason.UNCOSTED_FACT.name());
        event.setErrorMessage(detail);
        save(event);
    }

    private AccountingEvent newEvent(
            String eventType, String envelopeEventId, UUID domainKeyId, LocalDateTime transactionDate, Object fact) {
        AccountingEvent event = new AccountingEvent();
        event.setEventType(eventType);
        event.setSourceSystem(SOURCE_SYSTEM);
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
                "Recorded inventory fact ingestion | eventType={} | domainKeyId={} | status={} | outcome={}",
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
        AccountingSequence sequence =
                sequenceRepository.findByScopeKey(scopeKey).orElseGet(() -> provisionAndRelock(scopeKey));
        long assigned = sequence.getNextValue();
        sequence.setNextValue(assigned + 1);
        event.setEventReference(scopeKey + "-" + assigned);
    }

    private AccountingSequence provisionAndRelock(String scopeKey) {
        try {
            sequenceProvisioner.provision(scopeKey);
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Lost accounting_sequence bootstrap race for scope {}; re-reading winner's row", scopeKey);
        }
        return sequenceRepository
                .findByScopeKey(scopeKey)
                .orElseThrow(() ->
                        new IllegalStateException("accounting_sequence row missing after bootstrap: " + scopeKey));
    }

    private static @Nullable UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
