package com.positivity.workorder.service;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyService {

    /**
     * Check if an idempotency key exists and return the associated workorder ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the workorder ID if the key has been processed
     *         before, empty otherwise
     */
    Optional<UUID> getExistingWorkorderId(String keyValue);

    /**
     * Check if an idempotency key exists for a specific operation and return the
     * associated workorder ID.
     *
     * @param operation operation namespace
     * @param keyValue  the idempotency key to check
     * @return Optional containing the workorder ID if the key has been processed
     *         before, empty otherwise
     */
    Optional<UUID> getExistingWorkorderId(String operation, String keyValue);

    /**
     * Check if an idempotency key exists and return the associated change request
     * ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the change request ID if the key has been
     *         processed
     *         before, empty otherwise
     */
    Optional<UUID> getExistingChangeRequestId(String keyValue);

    Optional<UUID> getExistingChangeRequestId(String operation, String keyValue);

    /**
     * Check if an idempotency key exists and return the associated labor entry ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the labor entry ID if the key has been processed
     *         before, empty otherwise
     */
    Optional<UUID> getExistingLaborEntryId(String keyValue);

    Optional<UUID> getExistingLaborEntryId(String operation, String keyValue);

    /**
     * Register a new idempotency key associated with a workorder.
     * 
     * @param keyValue    the idempotency key value
     * @param workorderId the ID of the created workorder
     */
    void registerKey(String keyValue, UUID workorderId);

    void registerKey(String operation, String keyValue, UUID workorderId);

    /**
     * Mark an idempotency key as processed for a change request.
     * 
     * @param keyValue        the idempotency key value
     * @param changeRequestId the ID of the created change request
     */
    void markKeyProcessedForChangeRequest(String keyValue, UUID changeRequestId);

    void markKeyProcessedForChangeRequest(String operation, String keyValue, UUID changeRequestId);

    /**
     * Register a new idempotency key associated with a labor entry.
     * 
     * @param keyValue     the idempotency key value
     * @param laborEntryId the ID of the created labor entry
     */
    void registerLaborKey(String keyValue, UUID laborEntryId);

    void registerLaborKey(String operation, String keyValue, UUID laborEntryId);

    /**
     * Check if an idempotency key exists and return the associated part usage event
     * ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the part usage event ID if the key has been
     *         processed
     *         before, empty otherwise
     */
    Optional<UUID> getExistingPartUsageEventId(String keyValue);

    Optional<UUID> getExistingPartUsageEventId(String operation, String keyValue);

    /**
     * Mark an idempotency key as processed for a part usage event.
     * 
     * @param keyValue         the idempotency key value
     * @param partUsageEventId the ID of the created part usage event
     */
    void markKeyProcessedForPartUsage(String keyValue, UUID partUsageEventId);

    void markKeyProcessedForPartUsage(String operation, String keyValue, UUID partUsageEventId);

    /**
     * Check if an idempotency key exists and return the associated part adjustment
     * event ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the part adjustment event ID if the key has been
     *         processed
     *         before, empty otherwise
     */
    Optional<UUID> getExistingPartAdjustmentEventId(String keyValue);

    Optional<UUID> getExistingPartAdjustmentEventId(String operation, String keyValue);

    /**
     * Check if an idempotency key exists and return the associated invoice ID.
     *
     * @param keyValue the idempotency key to check
     * @return Optional containing the invoice ID if the key has been processed
     *         before, empty otherwise
     */
    Optional<UUID> getExistingInvoiceId(String keyValue);

    Optional<UUID> getExistingInvoiceId(String operation, String keyValue);

    /**
     * Mark an idempotency key as processed for a part adjustment event.
     * 
     * @param keyValue              the idempotency key value
     * @param partAdjustmentEventId the ID of the created part adjustment event
     */
    void markKeyProcessedForPartAdjustment(String keyValue, UUID partAdjustmentEventId);

    void markKeyProcessedForPartAdjustment(String operation, String keyValue, UUID partAdjustmentEventId);

    /**
     * Mark an idempotency key as processed for invoice generation.
     *
     * @param keyValue  the idempotency key value
     * @param invoiceId the ID of the generated invoice
     */
    void registerInvoiceKey(String keyValue, UUID invoiceId);

    void registerInvoiceKey(String operation, String keyValue, UUID invoiceId);

    /**
     * Clean up expired idempotency keys.
     * 
     * <p>
     * This method should be called periodically (e.g., via a scheduled task)
     * to remove old keys and prevent unbounded table growth.
     * </p>
     * 
     * @return count of deleted keys
     */
    int cleanupExpiredKeys();

}