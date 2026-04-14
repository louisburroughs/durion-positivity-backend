package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.AccountingEventMapper;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryMapper;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryResponse;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.exception.EventNotFoundException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.service.EventIngestionService;
import com.positivity.accounting.service.IdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final Clock clock;

    private static final String EVENT_SPACE = "Event ";
    private static final String EVENT_TYPE = "eventType";
    private static final String TRANSACTION_DATE = "transactionDate";
    private static final String ORGANIZATION_ID = "organizationId";
    private static final String SOURCE_SYSTEM = "sourceSystem";
    private final AccountingEventRepository accountingEventRepository;
    private final com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository
            reprocessingAttemptHistoryRepository;
    private final IdempotencyService idempotencyService;
    private final com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository
            auditTrailEntryRepository;
    private final PostingEngineOrchestrator postingEngineOrchestrator;

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
     * @throws IllegalArgumentException if event is invalid or rule set not found
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
            transactionDate = LocalDateTime.now(clock);
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
            throw new IllegalArgumentException(msg);
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
                .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));
        return AccountingEventMapper.toEventResponse(event);
    }

    /**
     * Retries processing of a failed event.
     * Useful when posting rules have been updated or temporary errors resolved.
     */
    @Override
    public AccountingEventResponse retryEventProcessing(UUID eventId) {
        AccountingEvent accountingEvent = accountingEventRepository
                .findById(eventId)
                .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));
        log.info("Retrying event {}", eventId);

        accountingEvent.setStatus(AccountingEventStatus.RECEIVED);
        accountingEvent.setErrorMessage(null);

        return AccountingEventMapper.toEventResponse(accountingEventRepository.save(accountingEvent));
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
     * @param request reprocess request with user context
     * @return updated accounting event response
     * @throws EventNotFoundException if event not found
     * @throws IllegalStateException  if event is not SUSPENDED or already
     *                                PROCESSED
     */
    @Override
    public AccountingEventResponse reprocessEvent(@NonNull UUID eventId, @NonNull ReprocessEventRequest request) {
        log.info("Reprocessing suspended event {} triggered by user {}", eventId, request.getTriggeredByUserId());

        // Load the accounting event
        AccountingEvent event = accountingEventRepository
                .findById(eventId)
                .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));

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
            if (request.getMappingVersionToUse() != null
                    && !request.getMappingVersionToUse().isBlank()) {
                try {
                    mappingVersion = UUID.fromString(request.getMappingVersionToUse());
                } catch (IllegalArgumentException e) {
                    String msg = "Invalid UUID format for mappingVersionToUse: '" + request.getMappingVersionToUse()
                            + "'. Value must be a valid UUID or left empty to use the default active version.";
                    log.warn(msg);
                    throw new IllegalArgumentException(msg, e);
                }
            }

            PostingResult postingResult = postingEngineOrchestrator.processEvent(
                    event, mappingVersion, request.getTriggeredByUserId(), true // autoPost=true for reprocessing flow
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
    public List<String> getEventProcessingLog(@NonNull UUID eventId) {
        // Query audit trail entries linked to this accounting event
        List<com.positivity.accounting.internal.audit.entity.AuditTrailEntry> auditEntries =
                auditTrailEntryRepository.findBySourceEventId(eventId);

        if (auditEntries.isEmpty()) {
            log.debug("No audit trail entries found for event {}", eventId);
            return List.of(EVENT_SPACE + eventId + ": No processing log entries found");
        }

        // Format audit entries as log messages
        return auditEntries.stream()
                .map(entry -> String.format(
                        "[%s] %s - Actor: %s (%s) - Status: %s - %s",
                        entry.getTimestamp(),
                        entry.getExceptionType(),
                        entry.getActorId(),
                        entry.getActorRole(),
                        entry.getAccountingStatus(),
                        entry.getReason() != null ? entry.getReason() : "No reason provided"))
                .toList();
    }

    /**
     * Retrieves the processing log for an event as a string.
     */
    @Override
    public String getProcessingLog(UUID eventId) {
        List<String> log = getEventProcessingLog(eventId);
        return String.join("\n", log);
    }

    /**
     * Lists all events with filtering, returning a page of response DTOs.
     *
     * @param organizationId the organization identifier
     * @param status         optional status filter
     * @param pageable       pagination parameters
     * @return paginated accounting event responses
     */
    @Override
    public Page<AccountingEventResponse> listEvents(
            @NonNull UUID organizationId, String status, @NonNull Pageable pageable) {
        log.debug("Listing events for organization {} with status filter: {}", organizationId, status);

        Page<AccountingEvent> eventPage;

        if (status != null && !status.isBlank()) {
            try {
                AccountingEventStatus eventStatus =
                        AccountingEventStatus.valueOf(status.trim().toUpperCase());
                eventPage =
                        accountingEventRepository.findByOrganizationIdAndStatus(organizationId, eventStatus, pageable);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid status filter '{}', returning all events for organization", status);
                eventPage = accountingEventRepository.findByOrganizationId(organizationId, pageable);
            }
        } else {
            eventPage = accountingEventRepository.findByOrganizationId(organizationId, pageable);
        }

        return eventPage.map(AccountingEventMapper::toEventResponse);
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
     * Process all failed events asynchronously.
     * Called by scheduled job to retry failed events.
     *
     * @param maxRetries maximum retries per record
     * @return count of records processed
     */
    @Override
    public int processFailed(int maxRetries) {
        log.info("Processing failed events (max retries: {})", maxRetries);

        int processedCount = 0;

        // Query all FAILED and SUSPENDED events that haven't exceeded max retries
        List<AccountingEvent> failedEvents = accountingEventRepository.findAll().stream()
                .filter(event -> (event.getStatus() == AccountingEventStatus.FAILED
                                || event.getStatus() == AccountingEventStatus.SUSPENDED)
                        && (event.getAttemptCount() == null || event.getAttemptCount() < maxRetries))
                .toList();

        log.info("Found {} eligible failed/suspended events for retry", failedEvents.size());

        for (AccountingEvent event : failedEvents) {
            try {
                int currentAttempt = event.getAttemptCount() != null ? event.getAttemptCount() : 0;
                log.debug("Retrying event {} (attempt {}/{})", event.getEventId(), currentAttempt + 1, maxRetries);

                // Retry processing through reprocessEvent
                ReprocessEventRequest request = new ReprocessEventRequest();
                request.setTriggeredByUserId("SYSTEM_RETRY_JOB");
                request.setMappingVersionToUse(null); // Use current active mapping

                reprocessEvent(event.getEventId(), request);
                processedCount++;

            } catch (Exception e) {
                log.error("Failed to retry event {}: {}", event.getEventId(), e.getMessage(), e);
                // Continue processing other events even if one fails
            }
        }

        log.info("Completed processing {} failed events out of {} candidates", processedCount, failedEvents.size());

        return processedCount;
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

        if (event.get(ORGANIZATION_ID) == null) {
            errors.add("organizationId is required");
        }
        if (event.get(EVENT_TYPE) == null) {
            errors.add("eventType is required");
        }
        if (event.get("payload") == null) {
            errors.add("payload is required");
        }

        return errors;
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
                + "|" + event.get("payload");
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
        return UUID.fromString(String.valueOf(value));
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
            throw new IllegalArgumentException("transactionDate must be a valid ISO-8601 datetime", ex);
        }
    }
}
