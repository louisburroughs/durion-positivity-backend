package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AccountingStatus;
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
public class AccountingStatusView {

    /** Invoice identifier. */
    UUID invoiceId;

    /** Current authoritative accounting status. */
    AccountingStatus accountingStatus;

    /** Timestamp of the last accounting status update. */
    Instant accountingStatusUpdatedAt;

    /** True when the accounting system reported a discrepancy for this invoice. */
    boolean discrepancyDetected;

    /**
     * Explanation of the discrepancy when discrepancyDetected=true. May be null.
     */
    @Nullable
    String discrepancyReason;

    /**
     * GL posting reference associated with the current status.
     * May be {@code null} for statuses that do not involve a posting.
     */
    String postingReference;

    /**
     * {@code true} when the status has not been refreshed within the freshness SLA
     * window
     * (1 hour for non-critical statuses). Callers should trigger a refresh when
     * this is {@code true}.
     */
    boolean stale;
}
