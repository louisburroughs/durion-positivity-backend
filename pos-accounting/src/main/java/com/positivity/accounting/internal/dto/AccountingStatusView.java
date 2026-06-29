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
 * Read-model for an invoice's current authoritative accounting status.
 *
 * <p>
 * Returned by
 * {@code AccountingStatusSyncService#getInvoiceAccountingStatus(UUID)}.
 * Includes a {@link #stale} indicator: {@code true} when
 * {@code accountingStatusUpdatedAt} is older than the configured freshness
 * threshold
 * (currently 1 hour), indicating that the cached status may not reflect the
 * latest GL state.
 * </p>
 *
 * Issue: CAP-251 #5
 */
@Value
@Builder
@Schema(description = "Read-model of an invoice's current authoritative accounting status")
public class AccountingStatusView {

    /** Invoice identifier. */
    @Schema(
            description = "Identifier of the invoice",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    UUID invoiceId;

    /** Current authoritative accounting status. */
    @Schema(description = "Current authoritative accounting status", example = "POSTED", requiredMode = REQUIRED)
    @NotNull
    AccountingStatus accountingStatus;

    /** Timestamp of the last accounting status update. */
    @Schema(
            description = "Timestamp of the last accounting status update (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    Instant accountingStatusUpdatedAt;

    /** True when the accounting system reported a discrepancy for this invoice. */
    @Schema(
            description = "True when the accounting system reported a discrepancy",
            example = "false",
            requiredMode = REQUIRED)
    boolean discrepancyDetected;

    /**
     * Explanation of the discrepancy when discrepancyDetected=true. May be null.
     */
    @Schema(
            description = "Explanation of the discrepancy when discrepancyDetected is true",
            example = "GL mapping mismatch",
            requiredMode = NOT_REQUIRED)
    @Nullable
    String discrepancyReason;

    /**
     * GL posting reference associated with the current status.
     * May be {@code null} for statuses that do not involve a posting.
     */
    @Schema(
            description = "GL posting reference associated with the current status",
            example = "JE-2026-000456",
            requiredMode = NOT_REQUIRED)
    String postingReference;

    /**
     * {@code true} when the status has not been refreshed within the freshness SLA
     * window
     * (1 hour for non-critical statuses). Callers should trigger a refresh when
     * this is {@code true}.
     */
    @Schema(
            description = "True when the status has not been refreshed within the freshness SLA window",
            example = "false",
            requiredMode = REQUIRED)
    boolean stale;
}
