package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.AccountingEventFilter;
import com.positivity.accounting.internal.dto.EventEnvelopeContract;
import com.positivity.accounting.internal.dto.EventProcessingLogEntry;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryResponse;
import com.positivity.accounting.internal.exception.EventNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EventIngestionService {

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
    AccountingEventResponse submitEvent(Map<String, Object> event);

    /**
     * Retrieves an event and its processing status.
     */
    Map<String, Object> getEvent(UUID eventId);

    /**
     * Retrieves an event by ID and returns a response DTO.
     *
     * @param eventId the event identifier
     * @return the accounting event response
     * @throws EventNotFoundException if event not found
     */
    AccountingEventResponse getEventById(UUID eventId);

    /**
     * Retries processing of a failed event.
     * Useful when posting rules have been updated or temporary errors resolved.
     */
    AccountingEventResponse retryEventProcessing(UUID eventId);

    /**
     * Retries processing of a failed event (alias for retryEventProcessing).
     */
    AccountingEventResponse retryEvent(UUID eventId);

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
    AccountingEventResponse reprocessEvent(UUID eventId, ReprocessEventRequest request);

    /**
     * Retrieves all reprocessing attempt history for an accounting event.
     * Used for audit trail and diagnostics.
     *
     * @param eventId the event identifier
     * @return list of reprocessing attempts, most recent first
     */
    List<ReprocessingAttemptHistoryResponse> getReprocessingHistory(UUID eventId);

    /**
     * Retrieves the processing log for an event.
     * Contains matched rules, generated journal entries, any errors.
     */
    List<EventProcessingLogEntry> getEventProcessingLog(@NonNull UUID eventId);

    /**
     * Lists all events with filtering, returning a page of response DTOs.
     *
     * @param organizationId the organization identifier
     * @param status         optional status filter
     * @param pageable       pagination parameters
     * @return paginated accounting event responses
     */
    Page<AccountingEventResponse> listEvents(@NonNull AccountingEventFilter filter, @NonNull Pageable pageable);

    /**
     * Gets the current event-envelope contract.
     */
    @NonNull
    EventEnvelopeContract getEventContract();

    /**
     * Find all events from a specific source system.
     * Uses indexed sourceSystem column for efficient querying.
     *
     * @param sourceSystem the source system identifier
     * @param pageable     pagination parameters
     * @return paginated accounting event responses
     */
    Page<AccountingEventResponse> findBySourceSystem(String sourceSystem, Pageable pageable);

    /**
     * Process all failed events asynchronously.
     * Called by scheduled job to retry failed events.
     *
     * @param maxRetries maximum retries per record
     * @return count of records processed
     */
    int processFailed(int maxRetries);

    /**
     * Validate that an event has required fields and can be processed.
     *
     * @param event event to validate
     * @return list of validation errors (empty if valid)
     */
    List<String> validateEvent(Map<String, Object> event);
}
