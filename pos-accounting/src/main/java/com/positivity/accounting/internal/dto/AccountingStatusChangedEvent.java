package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AccountingStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Event payload carrying a change in the authoritative accounting status for an
 * invoice.
 *
 * <p>
 * Published by the accounting subsystem when the GL/posting state of an invoice
 * transitions to a new {@link AccountingStatus}. Consumed by
 * {@code AccountingStatusSyncService} to reconcile POS invoice state.
 * </p>
 *
 * <p>
 * Carries an {@code eventId} for at-least-once idempotent consumer enforcement.
 * </p>
 *
 * Issue: CAP-251 #5
 */
@Value
@Builder
public class AccountingStatusChangedEvent {

    /** Invoice whose accounting status has changed. */
    UUID invoiceId;

    /** New authoritative accounting status for the invoice. */
    AccountingStatus newStatus;

    /** Wall-clock time at which this status transition occurred. */
    Instant timestamp;

    /** Human-readable reason supplied by the accounting system when REJECTED. May be null. */
    @Nullable
    String discrepancyReason;

    /**
     * Posting reference for GL traceability (e.g., journal entry or document
     * number).
     * May be {@code null} for non-posting statuses such as {@code ON_HOLD}.
     */
    String postingReference;

    /**
     * Unique event identifier used for idempotency enforcement.
     * Consumers must treat events with duplicate {@code eventId} as no-ops.
     */
    String eventId;
}
