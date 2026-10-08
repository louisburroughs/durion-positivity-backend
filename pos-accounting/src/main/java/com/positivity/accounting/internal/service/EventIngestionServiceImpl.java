package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.AccountingEventTypeRegistry;
import com.positivity.accounting.internal.dto.AccountingEventFilter;
import com.positivity.accounting.internal.dto.AccountingEventMapper;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.AccountingEventTypeResponse;
import com.positivity.accounting.internal.dto.ContractField;
import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.dto.EventEnvelopeContract;
import com.positivity.accounting.internal.dto.EventProcessingLogEntry;
import com.positivity.accounting.internal.dto.FactConsumptionIdempotency;
import com.positivity.accounting.internal.dto.FactPostingKeyDescriptor;
import com.positivity.accounting.internal.dto.IdempotencyOutcomeDescriptor;
import com.positivity.accounting.internal.dto.IdempotencyOutcomesContract;
import com.positivity.accounting.internal.dto.IdentifierStrategy;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.dto.ProcessingStatusDescriptor;
import com.positivity.accounting.internal.dto.ProcessingStatusesContract;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryMapper;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryResponse;
import com.positivity.accounting.internal.dto.RestSubmissionIdempotency;
import com.positivity.accounting.internal.dto.TraceabilityIdDescriptor;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import com.positivity.accounting.internal.exception.EventNotFoundException;
import com.positivity.accounting.internal.exception.EventNotRetryableException;
import com.positivity.accounting.internal.exception.EventValidationException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.domainevents.payment.PaymentSettledV1;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for accounting event ingestion and processing.
 * Converts domain events (order placed, invoice received, payment processed)
 * into
 * journal entries using active posting rule sets.
 *
 * Event Processing Workflow:
 * 1. Event received via REST or message queue
 * 2. Organization and source system extracted from event metadata
 * 3. Active posting rule set loaded for organization + source system +
 * transaction date
 * 4. Rules evaluated against event payload to determine GL account mappings
 * 5. Journal entry lines created for each mapping
 * 6. Entry balanced and posted to GL if rule set is marked auto-post
 * 7. Processing result logged for audit and retry
 *
 * Key Business Rules:
 * - Only PUBLISHED rule sets are used for event processing
 * - Rule sets must be effective on event transaction date
 * - Failed processing creates retry records with detailed error messages
 * - All event-to-entry conversions are audit-logged
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EventIngestionServiceImpl implements EventIngestionService {
    /** Triggering user recorded for an unaudited retry (no user is supplied on that path). */
    static final String RETRY_TRIGGER = "ACCOUNTING_EVENT_RETRY";

    private static final String EVENT_NOT_FOUND_PREFIX = "Event not found: ";

    private final Clock clock;
    private final AccountingCalendarZoneResolver zoneResolver;

    private static final String EVENT_SPACE = "Event ";
    private static final String EVENT_TYPE = "eventType";
    private static final String TRANSACTION_DATE = "transactionDate";
    private static final String ORGANIZATION_ID = "organizationId";
    private static final String SOURCE_SYSTEM = "sourceSystem";
    private static final String PAYLOAD = "payload";
    private static final String STRING_TYPE = "string";
    private static final String UUID_TYPE = "uuid";
    private static final String DATE_TYPE = "date";
    private static final String OBJECT_TYPE = "object";
    private final AccountingEventRepository accountingEventRepository;
    private final com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository
            reprocessingAttemptHistoryRepository;
    private final IdempotencyService idempotencyService;
    private final com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository
            auditTrailEntryRepository;
    private final PostingEngineOrchestrator postingEngineOrchestrator;
    private final AccountingSequenceLocker sequenceLocker;
    private final EventPayloadReferenceProjector eventPayloadReferenceProjector;
    private final AutomaticPaymentApplicationService automaticPaymentApplicationService;
    private final GoodsReceiptReprocessor goodsReceiptReprocessor;

    /** Scope-key prefix for the per-month {@code accounting_event.eventReference} counter. */
    private static final String EVENT_REFERENCE_SCOPE_PREFIX = "AE-";

    /**
     * Submits a business event for accounting processing.
     * Event is validated against schema and queued for rule-set matching.
     * Uses idempotency key to prevent duplicate processing.
     *
     * Event payload structure:
     * {
     * "eventType": "INVOICE_RECEIVED",
     * "organizationId": "550e8400-e29b-41d4-a716-446655440000",
     * "sourceSystem": "MYOB",
     * "transactionDate": "2024-01-15T10:30:00Z",
     * "dimensions": {
     * "cost_center": "CC001",
     * "location_id": "LOC_USA",
     * "business_unit_id": "BU_RETAIL"
     * },
     * "payload": {
     * "invoiceId": "INV-2024-001",
     * "vendorId": "VENDOR123",
     * "amount": "1500.00",
     * "description": "Office supplies"
     * }
     * }
     *
     * @param event map containing event details
     * @return generated journal entry
     * @throws EventValidationException if event is invalid or rule set not found
     */
    @Override
    public AccountingEventResponse submitEvent(Map<String, Object> event) {
        Map<String, Object> mutableEvent = new HashMap<>(event);
        UUID organizationId = parseUuid(mutableEvent.get(ORGANIZATION_ID));
        String sourceSystem = (String) mutableEvent.get(SOURCE_SYSTEM);
        LocalDateTime transactionDate = parseLocalDateTime(mutableEvent.get(TRANSACTION_DATE));
        String eventType = (String) mutableEvent.get(EVENT_TYPE);

        // Keep backward compatibility for older clients that omit these fields.
        if (sourceSystem == null || sourceSystem.isBlank()) {
            sourceSystem = "POS_ACCOUNTING_API";
            mutableEvent.put(SOURCE_SYSTEM, sourceSystem);
        }
        if (transactionDate == null) {
            // Now in the tenant's accounting calendar (#2558), not the UTC clock's.
            transactionDate = zoneResolver.postingDateTime(clock.instant());
            mutableEvent.put(TRANSACTION_DATE, transactionDate);
        }

        log.info(
                "Processing event type {} for org {} from {} on {}",
                eventType,
                organizationId,
                sourceSystem,
                transactionDate);

        // Validate event structure
        List<String> errors = validateEvent(mutableEvent);
        if (!errors.isEmpty()) {
            String msg = "Event validation failed: " + String.join("; ", errors);
            log.warn(msg);
            throw new EventValidationException(msg);
        }

        // Idempotency check — reject duplicate events based on content hash
        String contentHash = computeEventHash(mutableEvent);
        if (idempotencyService.isKeyProcessed(contentHash)) {
            throw new DuplicateEventException(
                    "Duplicate event detected for org " + organizationId + " from " + sourceSystem);
        }

        // Accept event with RECEIVED status and persist to database
        // Let @PrePersist generate UUIDv7 for time-ordered indexing unless provided
        UUID eventId = (UUID) mutableEvent.get("eventId");

        AccountingEvent accountingEvent = new AccountingEvent();
        if (eventId != null) {
            accountingEvent.setEventId(eventId);
        }
        accountingEvent.setOrganizationId(organizationId);
        accountingEvent.setSourceSystem(sourceSystem);
        accountingEvent.setEventType(eventType);
        accountingEvent.setTransactionDate(transactionDate);
        accountingEvent.setPayload(mutableEvent);
        accountingEvent.setStatus(AccountingEventStatus.RECEIVED);

        accountingEvent = accountingEventRepository.save(accountingEvent);
        log.info("Persisted accounting event {} with status RECEIVED", accountingEvent.getEventId());

        // Assign the display reference now that receivedAt is known: AccountingEvent#onPrePersist
        // sets receivedAt synchronously during the persist() call above (JPA @PrePersist fires at
        // persist time, not at flush), so the saved instance already carries it. Deriving the scope
        // month from this field (rather than setting both before save) guarantees the reference
        // month can never disagree with the receivedAt actually persisted. The reference is set on
        // the still-managed entity and picked up by dirty checking; no second explicit save is
        // needed because this method runs inside the class-level @Transactional and flushes on
        // commit. Placed after the idempotency-check/persist above, so a duplicate or a validation
        // failure (both of which throw earlier) never reaches here and never consumes a number.
        assignEventReference(accountingEvent);

        // Register idempotency key for 24-hour deduplication window with the persisted
        // event ID
        idempotencyService.registerKey(contentHash, accountingEvent.getEventId());

        AccountingEventResponse response = AccountingEventMapper.toEventResponse(accountingEvent);

        log.info("Accepted accounting event {} with status RECEIVED", accountingEvent.getEventId());
        return response;
    }

    /**
     * Retrieves an event and its processing status.
     */
    @Override
    public Map<String, Object> getEvent(@NonNull UUID eventId) {
        return accountingEventRepository
                .findById(eventId)
                .map(AccountingEvent::getPayload)
                .orElse(Map.of());
    }

    /**
     * Retrieves an event by ID and returns a response DTO.
     *
     * @param eventId the event identifier
     * @return the accounting event response
     * @throws EventNotFoundException if event not found
     */
    @Override
    public AccountingEventResponse getEventById(@NonNull UUID eventId) {
        AccountingEvent event = accountingEventRepository
                .findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(EVENT_NOT_FOUND_PREFIX + eventId));
        AccountingEventResponse response = AccountingEventMapper.toEventResponse(event);
        // Detail-only display projection (issues #1778, #1797): the raw payload above is returned
        // unchanged for audit and diagnostics; this adds the human-readable identity of the
        // reference values inside it — UUID-backed ids and code-keyed location codes — so screens
        // need not show raw identifiers or reach across a domain boundary to label them. List
        // responses stay lean and omit it.
        response.setPayloadReferences(eventPayloadReferenceProjector.project(event.getPayload()));
        return response;
    }

    /**
     * Retries processing of a failed event: re-runs its posting through the posting engine with the
     * current rules and returns the event in whatever status that ends in ({@code PROCESSED},
     * {@code FAILED} or {@code SUSPENDED}). Only a {@code FAILED} event is retryable (#2411); any other
     * status is rejected with {@link EventNotRetryableException} and left untouched. The previous
     * failure detail stays on the event until the new outcome replaces it. It shares its posting path
     * with {@link #reprocessEvent}, minus the mapping-version override.
     */
    @Override
    public AccountingEventResponse retryEventProcessing(UUID eventId) {
        AccountingEvent accountingEvent = accountingEventRepository
                .findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(EVENT_NOT_FOUND_PREFIX + eventId));
        if (accountingEvent.getStatus() != AccountingEventStatus.FAILED) {
            String msg = EVENT_SPACE + eventId + " has status " + accountingEvent.getStatus()
                    + " and cannot be retried. Only FAILED events can be retried.";
            log.warn(msg);
            throw new EventNotRetryableException(msg);
        }
        log.info("Retrying event {}", eventId);
        return rerunPosting(accountingEvent, null, RETRY_TRIGGER);
    }

    /**
     * Retries processing of a failed event (alias for retryEventProcessing).
     */
    @Override
    public AccountingEventResponse retryEvent(UUID eventId) {
        return retryEventProcessing(eventId);
    }

    /**
     * Reprocesses a suspended accounting event.
     * Distinct from retry: reprocess is for business-rule mapping failures that
     * require manual intervention.
     *
     * Business Rules (BR-3: Idempotency):
     * - Reprocessing MUST be idempotent
     * - A single suspense entry can produce at most one successful downstream
     * posting
     * - Returns 409 Conflict if entry is already PROCESSED
     *
     * @param eventId the event identifier
     * @param request reprocess request (mapping version pin, notes)
     * @param triggeredByUserId the authenticated caller (or system job) recorded in the audit trail
     * @return updated accounting event response
     * @throws EventNotFoundException if event not found
     * @throws IllegalStateException  if event is not SUSPENDED or already
     *                                PROCESSED
     */
    @Override
    public AccountingEventResponse reprocessEvent(
            @NonNull UUID eventId, @NonNull ReprocessEventRequest request, @NonNull String triggeredByUserId) {
        log.info("Reprocessing suspended event {} triggered by user {}", eventId, triggeredByUserId);

        // Load the accounting event
        AccountingEvent event = accountingEventRepository
                .findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(EVENT_NOT_FOUND_PREFIX + eventId));

        // BR-3: Idempotency check - reject if already PROCESSED
        if (event.getStatus() == AccountingEventStatus.PROCESSED) {
            String msg = EVENT_SPACE + eventId + " is already PROCESSED. Reprocessing would create duplicate posting.";
            log.warn(msg);
            throw new IllegalStateException(msg);
        }

        // Verify event is SUSPENDED or FAILED (eligible for reprocessing)
        if (event.getStatus() != AccountingEventStatus.SUSPENDED && event.getStatus() != AccountingEventStatus.FAILED) {
            String msg = EVENT_SPACE + eventId + " has status " + event.getStatus()
                    + " and cannot be reprocessed. Only SUSPENDED or FAILED events can be reprocessed.";
            log.warn(msg);
            throw new IllegalStateException(msg);
        }

        return rerunPosting(event, request.getMappingVersionToUse(), triggeredByUserId);
    }

    /**
     * Shared posting path of {@link #reprocessEvent} and {@link #retryEventProcessing}: the caller has
     * already checked the event is eligible. Re-runs the event through the posting engine (or hands an
     * {@code INVOICE_PAYMENT} back to the drainer) and returns the reloaded event.
     */
    private AccountingEventResponse rerunPosting(
            @NonNull AccountingEvent event, @Nullable String mappingVersionToUse, @NonNull String triggeredByUserId) {
        UUID eventId = event.getEventId();
        // INVOICE_PAYMENT is recorded in the AR subledger, never posted by the engine (#2435): hand
        // it back to the received-event drainer, which runs InvoicePaymentEventProcessor on its next
        // poll, instead of evaluating posting rules that must not exist for it.
        if (InvoicePaymentEventProcessor.EVENT_TYPE.equals(event.getEventType())) {
            log.info("Event {} is {}: returned to RECEIVED for the drainer", eventId, event.getEventType());
            int attempts = (event.getAttemptCount() == null ? 0 : event.getAttemptCount()) + 1;
            event.setAttemptCount(attempts);
            event.setStatus(AccountingEventStatus.RECEIVED);
            event.setResolvedByUserId(triggeredByUserId);
            // The attempt history is immutable and complete: the posting engine writes it for every other
            // type, so the handoff records its own row (the drainer's later outcome lands on the event).
            ReprocessingAttemptHistory handoff = new ReprocessingAttemptHistory();
            handoff.setAccountingEvent(event);
            handoff.setTriggeredByUserId(triggeredByUserId);
            handoff.setAttemptedAt(Instant.now(clock));
            handoff.setOutcome(ReprocessingOutcome.FAILURE);
            handoff.setOutcomeDetails(
                    "Returned to RECEIVED for the received-event drainer (not posted by this attempt)");
            reprocessingAttemptHistoryRepository.save(handoff);
            return AccountingEventMapper.toEventResponse(accountingEventRepository.save(event));
        }

        // A settled payment held by its automatic application (#2503) re-runs that decision from the
        // stored fact, never the posting engine; a currency hold keeps the engine path below.
        if (PaymentSettledV1.EVENT_TYPE.equals(event.getEventType())
                && AutomaticPaymentApplicationService.REPROCESSABLE_REASONS.contains(event.getFailureReasonCode())) {
            return reapplySettledPayment(event, triggeredByUserId);
        }

        // A held goods receipt (CAP:550 S41, #2602) re-runs its own assessment and posting from the stored fact,
        // never the posting engine: no rule set exists for it, and the engine would neither check its currency first
        // nor post under the receipt's key.
        if (GoodsReceiptRecordedV1.EVENT_TYPE.equals(event.getEventType())) {
            try {
                return reprocessGoodsReceipt(event, triggeredByUserId);
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
                // Two reprocesses of the same receipt at once: the loser trips the posting key's unique constraint or
                // the row's version. The same deterministic 409 as the engine branch below (ADR-0017).
                String msg = "Concurrent reprocessing detected for event " + eventId
                        + ". Another transaction has modified this event. Please retry.";
                log.warn(msg, e);
                throw new IllegalStateException(msg, e);
            }
        }

        // Increment attempt count
        Integer currentAttemptCount = event.getAttemptCount();
        int nextAttemptCount = (currentAttemptCount == null ? 0 : currentAttemptCount) + 1;
        event.setAttemptCount(nextAttemptCount);

        // Attempt history is persisted inside PostingEngineOrchestrator.
        try {
            // Transition to PROCESSING before delegating to PostingEngineOrchestrator
            event.setStatus(AccountingEventStatus.PROCESSING);
            accountingEventRepository.save(event);

            log.info("Reprocessing event {} via PostingEngineOrchestrator", eventId);

            // Parse mappingVersionToUse from String to UUID if present
            UUID mappingVersion = null;
            if (mappingVersionToUse != null && !mappingVersionToUse.isBlank()) {
                try {
                    mappingVersion = UUID.fromString(mappingVersionToUse);
                } catch (IllegalArgumentException e) {
                    String msg = "Invalid UUID format for mappingVersionToUse: '" + mappingVersionToUse
                            + "'. Value must be a valid UUID or left empty to use the default active version.";
                    log.warn(msg);
                    throw new EventValidationException(msg, e);
                }
            }

            PostingResult postingResult = postingEngineOrchestrator.processEvent(
                    event, mappingVersion, triggeredByUserId, true // autoPost=true for reprocessing flow
                    );

            boolean reprocessingSucceeded = postingResult.isSuccess();

            if (reprocessingSucceeded) {
                // Orchestrator already updated event status to PROCESSED,
                // set finalPostingReferenceId, processedAt, and resolvedByUserId.
                String finalPostingRef =
                        (String) postingResult.getEvaluationDetails().get("postingReference");
                log.info("Reprocessing succeeded for event {}: posted with reference {}", eventId, finalPostingRef);
            } else {
                // Orchestrator already updated event status to SUSPENDED/FAILED
                // with failureReasonCode and failureDetails.
                log.warn(
                        "Reprocessing failed for event {}: {} - {}",
                        eventId,
                        postingResult.getFailureReason(),
                        postingResult.getFailureDetails());
            }

        } catch (OptimisticLockingFailureException e) {
            // BR-3: Optimistic locking prevents concurrent reprocessing from creating
            // duplicate postings
            String msg = "Concurrent reprocessing detected for event " + eventId
                    + ". Another transaction has modified this event. Please retry.";
            log.warn(msg, e);
            throw new IllegalStateException(msg, e);
        } catch (Exception e) {
            log.error("Exception during reprocessing for event {}", eventId, e);
            throw new IllegalStateException("Failed to reprocess event " + eventId + ": " + e.getMessage(), e);
        }

        // Reload event to get updates from orchestrator
        event = accountingEventRepository
                .findById(eventId)
                .orElseThrow(() -> new EventNotFoundException("Event not found after reprocessing: " + eventId));

        return AccountingEventMapper.toEventResponse(event);
    }

    /**
     * CAP:550 S41 (#2602; Accounting ruling 4): re-assess a held {@code goodsreceipt.recorded} row from its stored
     * payload through {@link GoodsReceiptReprocessor}, in this transaction. Still invalid keeps the hold and its
     * reason ({@code CURRENCY_NOT_SUPPORTED} or {@code VALIDATION_ERROR}); a fact that now passes posts once under its
     * receipt key ({@code PROCESSED / NEW}), or closes {@code PROCESSED / DUPLICATE_IGNORED} when the key already
     * posted. Never FAILED, never a posting-engine reason. Every attempt counts and writes its history row.
     */
    private AccountingEventResponse reprocessGoodsReceipt(
            @NonNull AccountingEvent event, @NonNull String triggeredByUserId) {
        GoodsReceiptReprocessor.Result result = goodsReceiptReprocessor.reprocess(event.getPayload());
        event.setResolvedByUserId(triggeredByUserId);
        event.setAttemptCount((event.getAttemptCount() == null ? 0 : event.getAttemptCount()) + 1);
        event.setStatus(result.status());
        event.setFailureReasonCode(result.reason());
        if (result.status() == AccountingEventStatus.PROCESSED) {
            event.setFailureDetails(null);
            event.setErrorMessage(null);
        } else {
            event.setFailureDetails(result.detail());
            event.setErrorMessage(result.detail());
        }
        if (result.resolved()) {
            event.setProcessedAt(Instant.now(clock));
            event.setIdempotencyOutcome(result.idempotencyOutcome().name());
            event.setJournalEntryId(result.journalEntryId());
        }

        ReprocessingAttemptHistory attempt = new ReprocessingAttemptHistory();
        attempt.setAccountingEvent(event);
        attempt.setTriggeredByUserId(triggeredByUserId);
        attempt.setAttemptedAt(Instant.now(clock));
        attempt.setOutcome(
                result.status() == AccountingEventStatus.PROCESSED
                        ? ReprocessingOutcome.SUCCESS
                        : ReprocessingOutcome.FAILURE);
        attempt.setOutcomeDetails("Goods receipt reassessed: " + result.status()
                + (result.reason() == null ? "" : " / " + result.reason()) + " (" + result.detail() + ")");
        reprocessingAttemptHistoryRepository.save(attempt);
        log.info(
                "Reprocessed goods receipt event {} through its own path: {} {}",
                event.getEventId(),
                result.status(),
                result.reason() == null ? "" : result.reason());
        return AccountingEventMapper.toEventResponse(accountingEventRepository.save(event));
    }

    /**
     * Item 6 of #2503: re-run the automatic application of a held {@code payment.payment.settled} fact
     * from its stored payload, in this transaction. The payment is never recorded again. An
     * application, or a payment another path already settled, makes the row {@code PROCESSED / NEW};
     * a party mismatch or a paid invoice makes it {@code SKIPPED / NOT_POSTABLE}; another hold keeps it
     * held with the new reason. Every attempt counts toward the retry cap and writes its history row.
     */
    private AccountingEventResponse reapplySettledPayment(
            @NonNull AccountingEvent event, @NonNull String triggeredByUserId) {
        event.setResolvedByUserId(triggeredByUserId);

        AutomaticPaymentApplicationService.Result result =
                automaticPaymentApplicationService.reapply(event.getPayload());
        AutomaticPaymentApplicationService.Outcome outcome = result.outcome();
        // Every attempt counts, case b included: an uncapped hold would stay the oldest retry candidate
        // forever and starve the retry job's batch (review #2550). After max-retries a person reprocesses.
        event.setAttemptCount((event.getAttemptCount() == null ? 0 : event.getAttemptCount()) + 1);
        event.setStatus(outcome.status());
        if (outcome.isSettled()) {
            event.setIdempotencyOutcome(IdempotencyOutcome.NEW.name());
            event.setProcessedAt(Instant.now(clock));
            event.setFailureReasonCode(null);
            event.setFailureDetails(null);
            event.setErrorMessage(null);
        } else {
            event.setFailureReasonCode(outcome.reason());
            event.setFailureDetails(result.detail());
            event.setErrorMessage(result.detail());
            if (outcome.status() == AccountingEventStatus.SKIPPED) {
                event.setProcessedAt(Instant.now(clock));
            }
        }

        ReprocessingAttemptHistory attempt = new ReprocessingAttemptHistory();
        attempt.setAccountingEvent(event);
        attempt.setTriggeredByUserId(triggeredByUserId);
        attempt.setAttemptedAt(Instant.now(clock));
        attempt.setOutcome(outcome.isSettled() ? ReprocessingOutcome.SUCCESS : ReprocessingOutcome.FAILURE);
        attempt.setOutcomeDetails(
                "Automatic application of the settled payment: " + outcome.tag() + " (" + result.detail() + ")");
        reprocessingAttemptHistoryRepository.save(attempt);
        log.info(
                "Reprocessed settled payment event {} by its automatic application: {} -> {}",
                event.getEventId(),
                outcome.tag(),
                outcome.status());
        return AccountingEventMapper.toEventResponse(accountingEventRepository.save(event));
    }

    /**
     * Retrieves all reprocessing attempt history for an accounting event.
     * Used for audit trail and diagnostics.
     *
     * @param eventId the event identifier
     * @return list of reprocessing attempts, most recent first
     */
    @Override
    public List<ReprocessingAttemptHistoryResponse> getReprocessingHistory(@NonNull UUID eventId) {
        log.debug("Retrieving reprocessing history for event {}", eventId);

        List<ReprocessingAttemptHistory> history =
                reprocessingAttemptHistoryRepository.findByAccountingEvent_EventIdOrderByAttemptedAtDesc(eventId);

        return history.stream()
                .map(ReprocessingAttemptHistoryMapper::toResponse)
                .toList();
    }

    /**
     * Retrieves the processing log for an event.
     * Contains matched rules, generated journal entries, any errors.
     */
    @Override
    @Transactional(readOnly = true)
    public List<EventProcessingLogEntry> getEventProcessingLog(@NonNull UUID eventId) {
        // Query audit trail entries linked to this accounting event
        List<com.positivity.accounting.internal.audit.entity.AuditTrailEntry> auditEntries =
                auditTrailEntryRepository.findBySourceEventId(eventId);

        if (auditEntries.isEmpty()) {
            log.debug("No audit trail entries found for event {}", eventId);
            return List.of();
        }

        String severity = accountingEventRepository
                .findById(eventId)
                .map(AccountingEvent::getStatus)
                .filter(status -> status == AccountingEventStatus.FAILED || status == AccountingEventStatus.SUSPENDED)
                .map(status -> "ERROR")
                .orElse("INFO");

        return auditEntries.stream()
                .map(entry -> EventProcessingLogEntry.builder()
                        .entryId(entry.getAuditId())
                        .occurredAt(entry.getTimestamp())
                        .severity(severity)
                        .message(entry.getReason() != null ? entry.getReason() : "No reason provided")
                        .contextJson(entry.getLinkedSourceIds())
                        .build())
                .toList();
    }

    /**
     * Lists all events with filtering, returning a page of response DTOs.
     *
     * @param filter   filtering parameters
     * @param pageable pagination parameters
     * @return paginated accounting event responses
     */
    @Override
    @Transactional(readOnly = true)
    public Page<AccountingEventResponse> listEvents(@NonNull AccountingEventFilter filter, @NonNull Pageable pageable) {
        log.debug("Listing events with filter [status={}, eventType={}]", filter.getStatus(), filter.getEventType());

        Specification<AccountingEvent> specs = (root, query, cb) -> cb.conjunction();

        if (filter.getEventType() != null) {
            specs = specs.and((root, query, cb) -> cb.equal(root.get(EVENT_TYPE), filter.getEventType()));
        }
        if (filter.getStatus() != null) {
            specs = specs.and((root, query, cb) -> cb.equal(root.get("status"), filter.getStatus()));
        }
        if (filter.getIdempotencyOutcome() != null) {
            // idempotency_outcome is a plain String column (not @Enumerated); compare against the
            // enum's name() rather than the enum instance itself.
            specs = specs.and((root, query, cb) -> cb.equal(
                    root.get("idempotencyOutcome"),
                    filter.getIdempotencyOutcome().name()));
        }
        if (filter.getReceivedAtFrom() != null) {
            specs = specs.and(
                    (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("receivedAt"), filter.getReceivedAtFrom()));
        }
        if (filter.getReceivedAtTo() != null) {
            specs = specs.and(
                    (root, query, cb) -> cb.lessThanOrEqualTo(root.get("receivedAt"), filter.getReceivedAtTo()));
        }
        if (filter.getEventId() != null) {
            specs = specs.and((root, query, cb) -> cb.equal(root.get("eventId"), filter.getEventId()));
        }
        if (filter.getIngestionId() != null) {
            specs = specs.and((root, query, cb) -> cb.equal(root.get("ingestionId"), filter.getIngestionId()));
        }
        if (filter.getDomainKeyId() != null) {
            specs = specs.and((root, query, cb) -> cb.equal(root.get("domainKeyId"), filter.getDomainKeyId()));
        }
        if (filter.getInvoiceId() != null) {
            specs = specs.and((root, query, cb) -> cb.equal(root.get("invoiceId"), filter.getInvoiceId()));
        }

        return accountingEventRepository.findAll(specs, pageable).map(AccountingEventMapper::toEventResponse);
    }

    @Override
    public @NonNull List<AccountingEventTypeResponse> listEventTypes() {
        return AccountingEventTypeRegistry.entries().stream()
                .map(AccountingEventTypeResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull EventEnvelopeContract getEventContract() {
        List<ContractField> fields = List.of(
                ContractField.builder()
                        .name(EVENT_TYPE)
                        .jsonPath("$." + EVENT_TYPE)
                        .type(STRING_TYPE)
                        .required(true)
                        .description("The type of accounting event (e.g., INVOICE_POSTED, PAYMENT_RECEIVED)")
                        .build(),
                ContractField.builder()
                        .name(ORGANIZATION_ID)
                        .jsonPath("$." + ORGANIZATION_ID)
                        .type(UUID_TYPE)
                        .required(false)
                        .description("Deprecated and ignored: vestigial multi-tenancy scope key, resolved "
                                + "and displayed by nothing. Omit it.")
                        .build(),
                ContractField.builder()
                        .name(SOURCE_SYSTEM)
                        .jsonPath("$." + SOURCE_SYSTEM)
                        .type(STRING_TYPE)
                        .required(true)
                        .description("Originating system identifier")
                        .build(),
                ContractField.builder()
                        .name(TRANSACTION_DATE)
                        .jsonPath("$." + TRANSACTION_DATE)
                        .type(DATE_TYPE)
                        .required(true)
                        .description("Business date for journal entry posting (ISO-8601)")
                        .build(),
                ContractField.builder()
                        .name(PAYLOAD)
                        .jsonPath("$." + PAYLOAD)
                        .type(OBJECT_TYPE)
                        .required(true)
                        .description("Event-type-specific data payload")
                        .build(),
                ContractField.builder()
                        .name("idempotencyKey")
                        .jsonPath("$.idempotencyKey")
                        .type(STRING_TYPE)
                        .required(false)
                        .description("Client-supplied deduplication key")
                        .build());

        return EventEnvelopeContract.builder()
                .version("1.0")
                .fields(fields)
                .examples(List.of())
                .identifierStrategy(buildIdentifierStrategy())
                .traceabilityIds(buildTraceabilityIds())
                .processingStatuses(buildProcessingStatuses())
                .idempotencyOutcomes(buildIdempotencyOutcomes())
                .build();
    }

    /** AD-006 / ADR-0013 identifier conventions (issue #2207). */
    private IdentifierStrategy buildIdentifierStrategy() {
        return IdentifierStrategy.builder()
                .idFormat("UUIDv7")
                .eventIdMintedBy("SERVER_UNLESS_SUPPLIED")
                .domainKeyIdFormat("OPAQUE_STRING")
                .notes(List.of(
                        "AccountingEvent.eventId is minted by @UUIDv7Id unless the caller supplies eventId "
                                + "in the payload, which is then accepted verbatim.",
                        "domainKeyId is the upstream domain's own key; it is a string and is never required "
                                + "to be a UUID."))
                .build();
    }

    /** Traceability ids confirmed on AccountingEventResponse / the request pipeline (issue #2207). */
    private List<TraceabilityIdDescriptor> buildTraceabilityIds() {
        return List.of(
                TraceabilityIdDescriptor.builder()
                        .name("traceparent")
                        .description("W3C Trace Context header (AD-008), propagated automatically by the "
                                + "platform's distributed tracing instrumentation")
                        .location("Request/response header")
                        .build(),
                TraceabilityIdDescriptor.builder()
                        .name("X-Correlation-Id")
                        .description("Correlation id echoed from the request header if present, otherwise "
                                + "minted; returned on error responses (ADR-0017 §4)")
                        .location("Request/response header")
                        .build(),
                TraceabilityIdDescriptor.builder()
                        .name("eventId")
                        .description("Canonical identifier of the accounting event")
                        .location("AccountingEventResponse.eventId")
                        .build(),
                TraceabilityIdDescriptor.builder()
                        .name("eventReference")
                        .description("Short human-readable display reference, format AE-{YYYYMM}-{seq}")
                        .location("AccountingEventResponse.eventReference")
                        .build(),
                TraceabilityIdDescriptor.builder()
                        .name("ingestionId")
                        .description("Identifier of the ingestion batch or, for a Kafka-consumed fact, the "
                                + "envelope eventId that delivered it")
                        .location("AccountingEventResponse.ingestionId")
                        .build(),
                TraceabilityIdDescriptor.builder()
                        .name("journalEntryId")
                        .description(
                                "Canonical posting reference: the journal entry produced from this " + "event, if any")
                        .location("AccountingEventResponse.journalEntryId")
                        .build(),
                TraceabilityIdDescriptor.builder()
                        .name("domainKeyId")
                        .description("Domain key associated with the event, as supplied by the upstream " + "domain")
                        .location("AccountingEventResponse.domainKeyId")
                        .build(),
                TraceabilityIdDescriptor.builder()
                        .name("invoiceId")
                        .description("Identifier of the related invoice, if any")
                        .location("AccountingEventResponse.invoiceId")
                        .build());
    }

    /**
     * Every {@link AccountingEventStatus} constant with its meaning, plus the two distinct
     * lifecycles (issue #2207). Derived from {@code AccountingEventStatus.values()} — never
     * hand-typed — so a new status constant is published automatically.
     */
    private ProcessingStatusesContract buildProcessingStatuses() {
        List<ProcessingStatusDescriptor> statuses = Arrays.stream(AccountingEventStatus.values())
                .map(status -> ProcessingStatusDescriptor.builder()
                        .status(status)
                        .meaning(status.meaning())
                        .build())
                .toList();
        return ProcessingStatusesContract.builder()
                .statuses(statuses)
                .restSubmissionLifecycle(List.of("RECEIVED", "PROCESSING", "PROCESSED|FAILED|SUSPENDED"))
                .kafkaFactLifecycle(List.of("PROCESSED|SKIPPED|SUSPENDED"))
                .build();
    }

    /**
     * The two idempotency mechanisms (issue #2207): REST submission (content-hash dedup, 24h
     * window, rejects a replay with 409 DUPLICATE_EVENT) and Kafka fact consumption (every
     * consumed fact writes one row, terminal except a currency hold; outcomes derived from {@code
     * IdempotencyOutcome.values()} — never hand-typed).
     */
    private IdempotencyOutcomesContract buildIdempotencyOutcomes() {
        RestSubmissionIdempotency restSubmission = RestSubmissionIdempotency.builder()
                .mechanism("CONTENT_HASH")
                .window("24h")
                .onDuplicateHttpStatus(HttpStatus.CONFLICT.value())
                .onDuplicateErrorCode("DUPLICATE_EVENT")
                .onDuplicateBehavior("A replay persists nothing; idempotencyOutcome is not applicable to this path "
                        + "and stays null.")
                .build();
        List<IdempotencyOutcomeDescriptor> factOutcomes = Arrays.stream(IdempotencyOutcome.values())
                .map(outcome -> IdempotencyOutcomeDescriptor.builder()
                        .outcome(outcome)
                        .description(outcome.description())
                        .build())
                .toList();
        FactConsumptionIdempotency factConsumption = FactConsumptionIdempotency.builder()
                .mechanism("DETERMINISTIC_SOURCE_EVENT_ID")
                .envelopeDeduplication("PROCESSED_EVENTS_BY_EVENT_ID")
                .postingDeduplication(buildFactPostingKeys())
                .outcomes(factOutcomes)
                .build();
        return IdempotencyOutcomesContract.builder()
                .restSubmission(restSubmission)
                .factConsumption(factConsumption)
                .build();
    }

    /**
     * Each consuming listener's posting-deduplication key (#2433): how a re-emitted fact (new
     * envelope eventId, same business fact) is matched, and what its row records. Source systems
     * and event types come from the listeners' own constants.
     */
    private static List<FactPostingKeyDescriptor> buildFactPostingKeys() {
        String jeDuplicate = "Matched to the earlier posting: no new journal entry; the row is PROCESSED / "
                + "DUPLICATE_IGNORED and references the earlier entry.";
        return List.of(
                FactPostingKeyDescriptor.builder()
                        .sourceSystem(InventoryEventsListener.SOURCE_SYSTEM)
                        .eventTypes(InventoryEventsListener.RECORDED_EVENT_TYPES)
                        .postingKey("Deterministic sourceEventId derived from the scrapId, the adjustmentKind + "
                                + "adjustmentId, the revaluationId, or the receiptId (goodsreceipt.recorded, posting"
                                + " key GOODS_RECEIPT_ACCRUAL:<receiptId>). A goods receipt that is not posted writes"
                                + " SUSPENDED / CURRENCY_NOT_SUPPORTED (no currency, or not the ledger's), SUSPENDED /"
                                + " VALIDATION_ERROR (malformed) or SKIPPED / UNCOSTED_FACT; a held receipt is recorded"
                                + " once per reason, and reprocessing it re-runs the receipt's own assessment and"
                                + " posting instead of the posting engine")
                        .postsJournalEntry(true)
                        .duplicateOutcome(IdempotencyOutcome.DUPLICATE_IGNORED)
                        .onDuplicate(jeDuplicate)
                        .build(),
                FactPostingKeyDescriptor.builder()
                        .sourceSystem(InvoiceEventsListener.SOURCE_SYSTEM)
                        .eventTypes(InvoiceEventsListener.RECORDED_EVENT_TYPES)
                        .postingKey("Deterministic sourceEventId derived from the invoiceId + finalizedAt; an open "
                                + "revenue recognition for the invoice also matches")
                        .postsJournalEntry(true)
                        .duplicateOutcome(IdempotencyOutcome.DUPLICATE_IGNORED)
                        .onDuplicate(jeDuplicate)
                        .build(),
                FactPostingKeyDescriptor.builder()
                        .sourceSystem(RegisterOverShortPostingService.SOURCE_SYSTEM)
                        .eventTypes(OrderEventsListener.RECORDED_EVENT_TYPES)
                        .postingKey("Deterministic sourceEventId derived from the register sessionId")
                        .postsJournalEntry(true)
                        .duplicateOutcome(IdempotencyOutcome.DUPLICATE_IGNORED)
                        .onDuplicate(jeDuplicate)
                        .build(),
                FactPostingKeyDescriptor.builder()
                        .sourceSystem(SupplierInvoiceEventsListener.SOURCE_SYSTEM)
                        .eventTypes(SupplierInvoiceEventsListener.RECORDED_EVENT_TYPES)
                        .postingKey("Vendor + vendor invoice number (the vendor bill's business key)")
                        .postsJournalEntry(false)
                        .duplicateOutcome(IdempotencyOutcome.DUPLICATE_IGNORED)
                        .onDuplicate("The held bill stands and nothing is posted: an identical re-issue records "
                                + "PROCESSED / DUPLICATE_IGNORED; a re-issue with a different amount or currency "
                                + "flags the bill for review and records PROCESSED / NEW.")
                        .build(),
                FactPostingKeyDescriptor.builder()
                        .sourceSystem(WarrantyEventsListener.SOURCE_SYSTEM)
                        .eventTypes(WarrantyEventsListener.RECORDED_EVENT_TYPES)
                        .postingKey("Reimbursement id, ordered by aggregateVersion")
                        .postsJournalEntry(false)
                        .onDuplicate("Never recorded DUPLICATE_IGNORED: the expectation-row upsert is idempotent, so "
                                + "a re-emitted fact at an equal or newer aggregateVersion is re-applied and records "
                                + "PROCESSED / NEW, and one at an older version records SKIPPED / NOT_POSTABLE.")
                        .build(),
                FactPostingKeyDescriptor.builder()
                        .sourceSystem(SettlementEventsListener.PAYMENT_SETTLED_SOURCE_SYSTEM)
                        .eventTypes(List.of(PaymentSettledV1.EVENT_TYPE))
                        .postingKey("Application request id PAYMENT_SETTLED:<paymentIntentId> (the application,"
                                + " any excess credit and their journal entries); event type + paymentIntentId +"
                                + " reason of a held fact")
                        .postsJournalEntry(false)
                        .onDuplicate("An automatic application writes no row: the application record (source"
                                + " PAYMENT_SETTLED) is the evidence, and a re-emitted fact finds it and applies"
                                + " nothing again, and nothing is applied again after an undo of either automatic path. A fact not applied writes one row: SKIPPED /"
                                + " NOT_POSTABLE (method not CASH or CARD, customer differs from the invoice, or the"
                                + " invoice has no open balance), SUSPENDED / INVOICE_NOT_FOUND, SUSPENDED /"
                                + " PERIOD_CLOSED, FAILED / INVOICE_NOT_ELIGIBLE, or SUSPENDED /"
                                + " CURRENCY_NOT_SUPPORTED; a re-emitted fact already skipped for the same cause,"
                                + " or held for the same reason, writes no second row, and reprocessing a held row re-runs the automatic"
                                + " application instead of the posting engine.")
                        .build());
    }

    /**
     * Find all events from a specific source system.
     * Uses indexed sourceSystem column for efficient querying.
     *
     * @param sourceSystem the source system identifier
     * @param pageable     pagination parameters
     * @return paginated accounting event responses
     */
    @Override
    public Page<AccountingEventResponse> findBySourceSystem(@NonNull String sourceSystem, @NonNull Pageable pageable) {
        log.debug("Finding events from source system: {}", sourceSystem);

        Page<AccountingEvent> eventPage = accountingEventRepository.findBySourceSystem(sourceSystem, pageable);
        return eventPage.map(AccountingEventMapper::toEventResponse);
    }

    /**
     * Validate that an event has required fields and can be processed.
     *
     * @param event event to validate
     * @return list of validation errors (empty if valid)
     */
    @Override
    public List<String> validateEvent(Map<String, Object> event) {
        List<String> errors = new java.util.ArrayList<>();

        Object eventType = event.get(EVENT_TYPE);
        if (eventType == null) {
            errors.add("eventType is required");
        } else if (AccountingEventTypeRegistry.isRetired(String.valueOf(eventType))) {
            // #2509 review: a retired type is posted by something else now; recording it would post twice.
            errors.add("eventType " + eventType + " is retired; a vendor bill posts at its approval"
                    + " (POST /v1/accounting/vendor-bills/{billId}/approve)");
        }
        if (event.get(PAYLOAD) == null) {
            errors.add("payload is required");
        }

        return errors;
    }

    // ===== EVENT REFERENCE ASSIGNMENT (issue #1680) =====

    /**
     * Assigns the display reference {@code AE-{YYYYMM}-{seq}} from the
     * per-month {@code accounting_sequence} counter, reusing the same
     * {@code accounting_sequence} table and {@link AccountingSequenceLocker}
     * bootstrap machinery as {@code JournalEntryServiceImpl.assignEntryNumber}
     * (story A2, issue #942).
     *
     * <p>Scope key is {@code AE-{YYYYMM}} derived from the event's {@code
     * receivedAt}, so the reference always matches the received month shown
     * in the UI. The counter row is read under {@code FOR UPDATE} and
     * incremented inside the caller's transaction (this method is only ever
     * called from {@link #submitEvent}, which is itself transactional), so a
     * rollback of the submission rolls the increment back with it and the
     * number is never consumed.
     *
     * @param accountingEvent the just-persisted event; its {@code
     *                        eventReference} is set as a side effect
     */
    private void assignEventReference(AccountingEvent accountingEvent) {
        String scopeKey = eventReferenceScopeKey(accountingEvent.getReceivedAt());
        AccountingSequence sequence = sequenceLocker.lockOrProvision(scopeKey);
        long assigned = sequence.getNextValue();
        sequence.setNextValue(assigned + 1);
        accountingEvent.setEventReference(scopeKey + "-" + assigned);
    }

    /**
     * Sequence scope key {@code AE-{YYYYMM}} for a received-at instant,
     * interpreted in UTC (this module's house convention for Instant &lt;-&gt;
     * calendar conversions; see e.g. {@code FinancialReportingServiceImpl}).
     */
    private static String eventReferenceScopeKey(Instant receivedAt) {
        ZonedDateTime received = receivedAt.atZone(ZoneOffset.UTC);
        return String.format("%s%04d%02d", EVENT_REFERENCE_SCOPE_PREFIX, received.getYear(), received.getMonthValue());
    }

    /**
     * Computes a SHA-256 hash of the event content for idempotency detection.
     * Uses organizationId, sourceSystem, eventType, transactionDate, and payload.
     */
    private String computeEventHash(Map<String, Object> event) {
        String content = String.valueOf(event.get(ORGANIZATION_ID))
                + "|" + event.get(SOURCE_SYSTEM)
                + "|" + event.get(EVENT_TYPE)
                + "|" + event.get(TRANSACTION_DATE)
                + "|" + event.get(PAYLOAD);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in standard JDKs
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuidValue) {
            return uuidValue;
        }
        // Client-supplied event field (organizationId): wrap UUID.fromString's raw
        // IllegalArgumentException so this doesn't depend on catching a JDK exception type
        // that Hibernate/JPA and other unrelated code also throw.
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new EventValidationException("organizationId must be a valid UUID: '" + value + "'", e);
        }
    }

    private LocalDateTime parseLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTimeValue) {
            return dateTimeValue;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (DateTimeParseException ex) {
            throw new EventValidationException("transactionDate must be a valid ISO-8601 datetime", ex);
        }
    }
}
