package com.positivity.accounting.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.AccountingEventMapper;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
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

    /**
     * In-memory idempotency tracker keyed by content hash.
     * TODO: Replace with persistent store (e.g., database table) for production
     * use.
     */
    private final Set<String> processedEventHashes = ConcurrentHashMap.newKeySet();

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
        if (!processedEventHashes.add(contentHash)) {
            throw new DuplicateEventException(
                    "Duplicate event detected for org " + organizationId + " from " + sourceSystem);
        }

        // Accept event with RECEIVED status and persist to database
        UUID eventId = (UUID) event.get("eventId");
        if (eventId == null) {
            eventId = UUID.randomUUID();
        }

        AccountingEvent accountingEvent = new AccountingEvent();
        accountingEvent.setEventId(eventId);
        accountingEvent.setOrganizationId(organizationId);
        accountingEvent.setEventType(eventType);
        accountingEvent.setTransactionDate(transactionDate);
        accountingEvent.setPayload(event);
        accountingEvent.setStatus(AccountingEventStatus.RECEIVED);
        
        accountingEvent = accountingEventRepository.save(accountingEvent);
        log.info("Persisted accounting event {} with status RECEIVED", eventId);

        AccountingEventResponse response = AccountingEventMapper.toEventResponse(accountingEvent);

        log.info("Accepted accounting event {} with status RECEIVED", eventId);
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
     * @throws IllegalArgumentException if event not found
     */
    public AccountingEventResponse getEventById(@NonNull UUID eventId) {
        AccountingEvent event = accountingEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
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
     * Retrieves the processing log for an event.
     * Contains matched rules, generated journal entries, any errors.
     */
    public List<String> getEventProcessingLog(UUID eventId) {
        // TODO: Return audit log entries for this event
        return List.of();
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
                    pageable
                );
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
     */
    public List<Map<String, Object>> findBySourceSystem(String sourceSystem) {
        // TODO: Query audit table by source system
        return List.of();
    }

    /**
     * Process all failed events asynchronously.
     * Called by scheduled job to retry failed events.
     *
     * @param maxRetries maximum retries per record
     * @return count of records processed
     */
    public int processFailed(int maxRetries) {
        // TODO: Query failed records, retry up to maxRetries times
        log.info("Processing failed events (max retries: {})", maxRetries);
        return 0;
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
