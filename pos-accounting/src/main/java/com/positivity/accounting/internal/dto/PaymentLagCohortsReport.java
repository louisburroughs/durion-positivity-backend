package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Payment-lag cohorts for invoices issued (finalized) in a date window (Wave 2 E3, Issue #1591).
 *
 * <p>Unlike {@link CollectionsAnalyticsReport} (E2), this is not a Wave-3 {@code groupBy} candidate:
 * the four cohorts already are the buckets, so this stays a single-window endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payment-lag cohorts for invoices issued (finalized) in a date window")
public class PaymentLagCohortsReport {

    @Schema(
            description = "Window start date (inclusive), anchored on invoice finalizedAt",
            example = "2026-01-01",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate issuedFrom;

    @Schema(description = "Window end date (inclusive)", example = "2026-06-30", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate issuedTo;

    @Schema(
            description = "Timestamp when the report was generated (ISO 8601)",
            example = "2026-06-30T08:00:00Z",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant generatedAt;

    @Schema(
            description =
                    "True when `limit` is below the fixed cohort count (4) and dropped cohorts that would otherwise appear, false otherwise",
            requiredMode = REQUIRED)
    private boolean truncated;

    @Schema(
            description = "Cohort rows in fixed order (<=30, 31-60, 61-90, unpaid), truncated to the requested limit",
            requiredMode = REQUIRED)
    @NonNull
    private List<PaymentLagCohortRow> cohorts;
}
