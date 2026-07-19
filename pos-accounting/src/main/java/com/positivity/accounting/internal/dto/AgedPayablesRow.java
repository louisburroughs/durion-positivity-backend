package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * One per-vendor row of the Aged Payables report.
 *
 * Buckets the vendor's open (unpaid) vendor-bill balances by days past due as of
 * the report date. Open balance per bill is the bill total minus applied
 * {@code APPaymentAllocation} amounts. All bucket amounts are non-negative;
 * {@code totalOutstanding} is their sum.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Per-vendor aged payables row with bucketed open vendor-bill balances")
public class AgedPayablesRow {

    /**
     * Vendor identifier.
     */
    @Schema(description = "Vendor UUID", example = "d10217f9-3ec6-46b9-9c87-e7066c100c24", requiredMode = REQUIRED)
    @NonNull
    private UUID vendorId;

    /**
     * Vendor display name (may be null when the directory lookup is unavailable).
     */
    @Schema(description = "Vendor display name", example = "Global Parts Supply", requiredMode = NOT_REQUIRED)
    private String vendorName;

    /**
     * Outstanding 0-30 days past due (includes not-yet-due).
     */
    @Schema(
            description = "Outstanding 0-30 days past due (includes not-yet-due)",
            example = "3200.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal current;

    /**
     * Outstanding 31-60 days past due.
     */
    @Schema(description = "Outstanding 31-60 days past due", example = "750.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days31To60;

    /**
     * Outstanding 61-90 days past due.
     */
    @Schema(description = "Outstanding 61-90 days past due", example = "0.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days61To90;

    /**
     * Outstanding more than 90 days past due.
     */
    @Schema(description = "Outstanding more than 90 days past due", example = "0.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days90Plus;

    /**
     * Total outstanding for the vendor across all buckets.
     */
    @Schema(
            description = "Total outstanding for the vendor across all buckets",
            example = "3950.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalOutstanding;
}
