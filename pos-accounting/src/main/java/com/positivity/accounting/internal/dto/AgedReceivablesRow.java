package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * One per-customer row of the Aged Receivables report.
 *
 * Buckets the customer's open (unpaid) invoice balances by days past due as of
 * the report date. All bucket amounts are non-negative; {@code totalOutstanding}
 * is their sum.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Per-customer aged receivables row with bucketed open invoice balances")
public class AgedReceivablesRow {

    /**
     * Customer (party) identifier.
     */
    @Schema(
            description = "Customer (party) UUID",
            example = "d10217f9-3ec6-46b9-9c87-e7066c100c24",
            requiredMode = REQUIRED)
    @NonNull
    private UUID customerId;

    /**
     * Customer display name (may be null when the directory lookup is unavailable).
     */
    @Schema(description = "Customer display name", example = "Acme Fleet Services", requiredMode = NOT_REQUIRED)
    private String customerName;

    /**
     * Outstanding 0-30 days past due (includes not-yet-due).
     */
    @Schema(
            description = "Outstanding 0-30 days past due (includes not-yet-due)",
            example = "1250.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal current;

    /**
     * Outstanding 31-60 days past due.
     */
    @Schema(description = "Outstanding 31-60 days past due", example = "500.00", requiredMode = REQUIRED)
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
     * Total outstanding for the customer across all buckets.
     */
    @Schema(
            description = "Total outstanding for the customer across all buckets",
            example = "1750.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalOutstanding;
}
