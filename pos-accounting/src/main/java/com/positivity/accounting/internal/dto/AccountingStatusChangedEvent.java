package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.AccountingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "Event carrying a change in the authoritative accounting status for an invoice")
public class AccountingStatusChangedEvent {

    /** Invoice whose accounting status has changed. */
    @Schema(
            description = "Identifier of the invoice whose accounting status changed",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    UUID invoiceId;

    /** New authoritative accounting status for the invoice. */
    @Schema(
            description = "New authoritative accounting status for the invoice",
            example = "POSTED",
            requiredMode = REQUIRED)
    @NotNull
    AccountingStatus newStatus;

    /** Wall-clock time at which this status transition occurred. */
    @Schema(
            description = "Timestamp at which the status transition occurred (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    Instant timestamp;

    /**
     * Human-readable reason supplied by the accounting system when REJECTED. May be
     * null.
     */
    @Schema(
            description = "Human-readable reason supplied when the status is REJECTED",
            example = "GL mapping mismatch",
            requiredMode = NOT_REQUIRED)
    @Nullable
    String discrepancyReason;

    /**
     * Posting reference for GL traceability (e.g., journal entry or document
     * number).
     * May be {@code null} for non-posting statuses such as {@code ON_HOLD}.
     */
    @Schema(
            description = "Posting reference for GL traceability",
            example = "JE-2026-000456",
            requiredMode = NOT_REQUIRED)
    String postingReference;

    /**
     * Unique event identifier used for idempotency enforcement.
     * Consumers must treat events with duplicate {@code eventId} as no-ops.
     */
    @Schema(
            description = "Unique event identifier used for idempotency enforcement",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    String eventId;
}
