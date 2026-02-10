package com.positivity.accounting.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.AccountingEventMapper;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryMapper;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryResponse;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import com.positivity.accounting.internal.exception.EventNotFoundException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
public class EventIngestionService {

    private final JournalEntryService journalEntryService;
    private final AccountingEventRepository accountingEventRepository;
    private final com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;
    private final IdempotencyService idempotencyService;
    private final com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository auditTrailEntryRepository;

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
    public AccountingEventResponse submitEvent(Map<String, Object> event) {
        UUID organizationId = (UUID) event.get("organizationId");
        String sourceSystem = (String) event.get("sourceSystem");
        LocalDateTime transactionDate = (LocalDateTime) event.get("transactionDate");
        String eventType = (String) event.get("eventType");

        log.info("Processing event type {} for org {} from {} on {}",
                eventType, organizationId, sourceSystem, transactionDate);

        // Validate event structure
        List<String> errors = validateEvent(event);
        if (!errors.isEmpty()) {
            String msg = "Event validation failed: " + String.join("; ", errors);
            log.warn(msg);
            throw new IllegalArgumentException(msg);
        }

        // Idempotency check — reject duplicate events based on content hash
        String contentHash = computeEventHash(event);
        if (idempotencyService.isKeyProcessed(contentHash)) {
            throw new DuplicateEventException(
                    "Duplicate event detected for org " + organizationId + " from " + sourceSystem);
        }

        // Accept event with RECEIVED status and persist to database
        // Let @PrePersist generate UUIDv7 for time-ordered indexing unless provided
        UUID eventId = (UUID) event.get("eventId");

        AccountingEvent accountingEvent = new AccountingEvent();
        if (eventId != null) {
            accountingEvent.setEventId(eventId);
        }
        accountingEvent.setOrganizationId(organizationId);
        accountingEvent.setEventType(eventType);
        accountingEvent.setTransactionDate(transactionDate);
        accountingEvent.setPayload(event);
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
    public Map<String, Object> getEvent(@NonNull UUID eventId) {
        return accountingEventRepository.findById(eventId)
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
    public AccountingEventResponse getEventById(@NonNull UUID eventId) {
        AccountingEvent event = accountingEventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));
        return AccountingEventMapper.toEventResponse(event);
    }

    /**
     * Retries processing of a failed event.
     * Useful when posting rules have been updated or temporary errors resolved.
     */
    public AccountingEventResponse retryEventProcessing(UUID eventId) {
        Map<String, Object> record = getEvent(eventId);
        log.info("Retrying event {}", eventId);
        return submitEvent(record);
    }

    /**
     * Retries processing of a failed event (alias for retryEventProcessing).
     */
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
    public AccountingEventResponse reprocessEvent(@NonNull UUID eventId, @NonNull ReprocessEventRequest request) {
        log.info("Reprocessing suspended event {} triggered by user {}", eventId, request.getTriggeredByUserId());

        try {
            // Load the accounting event
            AccountingEvent event = accountingEventRepository.findById(eventId)
                    .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));

            // BR-3: Idempotency check - reject if already PROCESSED
            if (event.getStatus() == AccountingEventStatus.PROCESSED) {
                String msg = "Event " + eventId + " is already PROCESSED. Reprocessing would create duplicate posting.";
                log.warn(msg);
                throw new IllegalStateException(msg);
            }

            // Verify event is SUSPENDED or FAILED (eligible for reprocessing)
            if (event.getStatus() != AccountingEventStatus.SUSPENDED
                    && event.getStatus() != AccountingEventStatus.FAILED) {
                String msg = "Event " + eventId + " has status " + event.getStatus()
                        + " and cannot be reprocessed. Only SUSPENDED or FAILED events can be reprocessed.";
                log.warn(msg);
                throw new IllegalStateException(msg);
            }

            // Increment attempt count
            Integer currentAttemptCount = event.getAttemptCount();
            int nextAttemptCount = (currentAttemptCount == null ? 0 : currentAttemptCount) + 1;
            event.setAttemptCount(nextAttemptCount);

            // Create reprocessing attempt history record
            ReprocessingAttemptHistory attemptHistory = new ReprocessingAttemptHistory(
                    event,
                    request.getTriggeredByUserId(),
                    ReprocessingOutcome.FAILURE, // Default to FAILURE, will update on success
                    "Reprocessing attempt initiated");

            if (request.getMappingVersionToUse() != null) {
                attemptHistory.setMappingVersionUsed(request.getMappingVersionToUse());
            }

            try {
                // Re-run mapping/posting logic using current rules
                // TODO: Integrate with actual posting rule engine when available
                // For now, simulate reprocessing:
                // 1. Change status to PROCESSING
                // 2. Attempt to create journal entry
                // 3. On success: mark as PROCESSED
                // 4. On failure: keep as SUSPENDED/FAILED

                event.setStatus(AccountingEventStatus.PROCESSING);
                accountingEventRepository.save(event);

                log.info("Attempting to reprocess event {} with current mapping rules", eventId);

                // Simulate successful reprocessing (placeholder for actual posting logic)
                // In real implementation, this would call the posting rule engine
                boolean reprocessingSucceeded = attemptReprocessingLogic(event, request);

                if (reprocessingSucceeded) {
                    // SUCCESS: Update event status and history
                    event.setStatus(AccountingEventStatus.PROCESSED);
                    event.setProcessedAt(java.time.Instant.now());
                    event.setResolvedByUserId(request.getTriggeredByUserId());

                    // Set final posting reference (would come from JE created)
                    String finalPostingRef = "JE-" + UUID.randomUUID().toString().substring(0, 8);
                    event.setFinalPostingReferenceId(finalPostingRef);

                    // Update attempt history outcome
                    attemptHistory.setOutcome(ReprocessingOutcome.SUCCESS);
                    attemptHistory
                            .setOutcomeDetails(
                                    "Reprocessing succeeded. Posted to GL with reference: " + finalPostingRef);

                    log.info("Reprocessing succeeded for event {}: posted with reference {}", eventId, finalPostingRef);
                } else {
                    // FAILURE: Keep as SUSPENDED, update error details
                    event.setStatus(AccountingEventStatus.SUSPENDED);
                    String errorDetails = "Reprocessing failed: mapping/rule still invalid";
                    event.setFailureDetails(errorDetails);

                    attemptHistory.setOutcome(ReprocessingOutcome.FAILURE);
                    attemptHistory.setOutcomeDetails(errorDetails);

                    log.warn("Reprocessing failed for event {}: {}", eventId, errorDetails);
                }

            } catch (Exception e) {
                // FAILURE: Keep as SUSPENDED/FAILED, log error
                event.setStatus(AccountingEventStatus.SUSPENDED);
                String errorMsg = "Reprocessing exception: " + e.getMessage();
                event.setFailureDetails(errorMsg);
                event.setErrorMessage(e.getMessage());

                attemptHistory.setOutcome(ReprocessingOutcome.FAILURE);
                attemptHistory.setOutcomeDetails(errorMsg);

                log.error("Reprocessing failed for event {} due to exception", eventId, e);
            } finally {
                // Always persist the event state and attempt history
                accountingEventRepository.save(event);
                reprocessingAttemptHistoryRepository.save(attemptHistory);
            }

            return AccountingEventMapper.toEventResponse(event);

        } catch (OptimisticLockingFailureException e) {
            // BR-3: Optimistic locking prevents concurrent reprocessing from creating
            // duplicate postings
            String msg = "Concurrent reprocessing detected for event " + eventId
                    + ". Another transaction has modified this event. Please retry.";
            log.warn(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * Placeholder for actual reprocessing logic.
     * In production, this would integrate with the posting rule engine.
     * 
     * @param event   the event to reprocess
     * @param request reprocess request context
     * @return true if reprocessing succeeded, false otherwise
     */
    private boolean attemptReprocessingLogic(@NonNull AccountingEvent event, @NonNull ReprocessEventRequest request) {
        // TODO: Integrate with posting rule engine
        // For now, simulate success if failureReasonCode is not UNMAPPED_EVENT_TYPE
        // In real implementation, this would:
        // 1. Load current posting rule set for event.organizationId +
        // event.transactionDate
        // 2. Apply rules to event.payload
        // 3. Generate journal entry if rules match
        // 4. Post to GL if auto-post is enabled
        // 5. Return success/failure

        log.debug("Simulating reprocessing logic for event {}", event.getEventId());

        // Simulate: if failure was UNMAPPED_EVENT_TYPE and we still don't have a
        // mapping, fail
        if ("UNMAPPED_EVENT_TYPE".equals(event.getFailureReasonCode())) {
            // Simulate checking if mapping now exists (50% chance for demo purposes)
            return Math.random() > 0.5;
        }

        // For other failure codes, simulate success
        return true;
    }

    /**
     * Retrieves all reprocessing attempt history for an accounting event.
     * Used for audit trail and diagnostics.
     * 
     * @param eventId the event identifier
     * @return list of reprocessing attempts, most recent first
     */
    public List<ReprocessingAttemptHistoryResponse> getReprocessingHistory(@NonNull UUID eventId) {
        log.debug("Retrieving reprocessing history for event {}", eventId);

        List<ReprocessingAttemptHistory> history = reprocessingAttemptHistoryRepository
                .findByAccountingEvent_EventIdOrderByAttemptedAtDesc(eventId);

        return history.stream()
                .map(ReprocessingAttemptHistoryMapper::toResponse)
                .toList();
    }

    /**
     * Retrieves the processing log for an event.
     * Contains matched rules, generated journal entries, any errors.
     */
    public List<String> getEventProcessingLog(@NonNull UUID eventId) {
        // Query audit trail entries linked to this accounting event
        List<com.positivity.accounting.internal.audit.entity.AuditTrailEntry> auditEntries = auditTrailEntryRepository
                .findBySourceEventId(eventId);

        if (auditEntries.isEmpty()) {
            log.debug("No audit trail entries found for event {}", eventId);
            return List.of("Event " + eventId + ": No processing log entries found");
        }

        // Format audit entries as log messages
        return auditEntries.stream()
                .map(entry -> String.format("[%s] %s - Actor: %s (%s) - Status: %s - %s",
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
    public Page<AccountingEventResponse> listEvents(
            @NonNull UUID organizationId,
            String status,
            @NonNull Pageable pageable) {
        log.debug("Listing events for organization {} with status filter: {}", organizationId, status);

        Page<AccountingEvent> eventPage;

        if (status != null && !status.isBlank()) {
            try {
                AccountingEventStatus eventStatus = AccountingEventStatus.valueOf(status.trim().toUpperCase());
                eventPage = accountingEventRepository.findByOrganizationIdAndStatus(
                        organizationId,
                        eventStatus,
                        pageable);
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
     * Note: sourceSystem is stored in the JSON payload, not as a separate column.
     * This implementation requires full table scan and is not performant for large
     * datasets.
     * Consider adding a sourceSystem column for production use.
     */
    public List<Map<String, Object>> findBySourceSystem(String sourceSystem) {
        log.warn("findBySourceSystem performs full table scan - consider schema optimization");

        // Query all events and filter by sourceSystem in payload
        return accountingEventRepository.findAll()
                .stream()
                .filter(event -> {
                    Map<String, Object> payload = event.getPayload();
                    return payload != null && sourceSystem.equals(payload.get("sourceSystem"));
                })
                .map(AccountingEvent::getPayload)
                .toList();
    }

    /**
     * Process all failed events asynchronously.
     * Called by scheduled job to retry failed events.
     *
     * @param maxRetries maximum retries per record
     * @return count of records processed
     */
    public int processFailed(int maxRetries) {
        log.info("Processing failed events (max retries: {})", maxRetries);

        int processedCount = 0;

        // Query all FAILED and SUSPENDED events that haven't exceeded max retries
        List<AccountingEvent> failedEvents = accountingEventRepository.findAll()
                .stream()
                .filter(event -> (event.getStatus() == AccountingEventStatus.FAILED
                        || event.getStatus() == AccountingEventStatus.SUSPENDED)
                        && event.getAttemptCount() < maxRetries)
                .toList();

        log.info("Found {} eligible failed/suspended events for retry", failedEvents.size());

        for (AccountingEvent event : failedEvents) {
            try {
                log.debug("Retrying event {} (attempt {}/{})",
                        event.getEventId(), event.getAttemptCount() + 1, maxRetries);

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

        log.info("Completed processing {} failed events out of {} candidates",
                processedCount, failedEvents.size());

        return processedCount;
    }

    /**
     * Validate that an event has required fields and can be processed.
     *
     * @param event event to validate
     * @return list of validation errors (empty if valid)
     */
    public List<String> validateEvent(Map<String, Object> event) {
        List<String> errors = new java.util.ArrayList<>();

        if (event.get("organizationId") == null) {
            errors.add("organizationId is required");
        }
        if (event.get("sourceSystem") == null) {
            errors.add("sourceSystem is required");
        }
        if (event.get("transactionDate") == null) {
            errors.add("transactionDate is required");
        }
        if (event.get("eventType") == null) {
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
        String content = String.valueOf(event.get("organizationId"))
                + "|" + event.get("sourceSystem")
                + "|" + event.get("eventType")
                + "|" + event.get("transactionDate")
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
}
