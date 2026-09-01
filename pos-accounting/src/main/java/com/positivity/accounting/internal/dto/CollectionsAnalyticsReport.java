package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Invoiced-vs-collected analytics for one date window (Wave 2 E2, Issue #1590).
 *
 * <p>{@code invoiced} and {@code collected} are deliberately <b>different invoice cohorts</b>:
 * {@code invoiced} sums {@code ExtInvoice.total} for invoices finalized in the window, while {@code
 * collected} sums {@code PaymentApplication.appliedAmount} for cash applications posted in the
 * window, regardless of which invoice — or which period that invoice was finalized in — they
 * settle. {@code collectionRatePct} is therefore a period-level cash-efficiency signal, not a
 * per-cohort collection rate; do not read it as "what fraction of this window's invoiced amount got
 * paid".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Invoiced-vs-collected analytics for one date window. invoiced and collected are different"
                + " invoice cohorts (see field descriptions) — do not present collectionRatePct as a"
                + " cohort collection rate.")
public class CollectionsAnalyticsReport {

    @Schema(description = "Window start date (inclusive)", example = "2026-06-01", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Schema(description = "Window end date (inclusive)", example = "2026-06-30", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @Schema(
            description = "Timestamp when the report was generated (ISO 8601)",
            example = "2026-06-30T08:00:00Z",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant generatedAt;

    @Schema(
            description = "Sum of ExtInvoice.total for invoices whose finalizedAt (accrual/posting date) falls in the"
                    + " window; 0 when none finalized in the window",
            example = "125000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal invoiced;

    @Schema(
            description = "Sum of settled PaymentApplication.appliedAmount whose applicationTimestamp falls in the"
                    + " window (never PaymentIntent/Receipt pre-settlement amounts); 0 when none applied"
                    + " in the window",
            example = "98250.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal collected;

    @Schema(
            description = "collected divided by invoiced, times 100, rounded HALF_UP to 2 decimals; null when invoiced"
                    + " is zero (the ratio is undefined — never a divide-by-zero error and never a"
                    + " misleading 0)",
            example = "78.60",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    @Nullable
    private BigDecimal collectionRatePct;
}
