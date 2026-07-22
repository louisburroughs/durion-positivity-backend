package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Full frozen Sales-Tax Liability snapshot (issue #998, Phase-2 scope item 2): the T8 report as it
 * stood when a CLOSED accounting period was frozen, plus freeze/supersede audit metadata and the
 * canonical content hash used by the verify endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Frozen Sales-Tax Liability snapshot for a closed accounting period")
public class TaxLiabilitySnapshotResponse {

    @Schema(description = "Snapshot id", requiredMode = REQUIRED)
    @NonNull
    private UUID snapshotId;

    @Schema(description = "Accounting period id the snapshot freezes", requiredMode = REQUIRED)
    @NonNull
    private UUID periodId;

    @Schema(description = "Accounting period code (YYYY-MM)", example = "2026-06", requiredMode = REQUIRED)
    @NonNull
    private String periodCode;

    @Schema(description = "Period start date (inclusive)", example = "2026-06-01", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Schema(description = "Period end date (inclusive)", example = "2026-06-30", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @Schema(
            description = "Snapshot lifecycle status: ACTIVE or SUPERSEDED",
            example = "ACTIVE",
            requiredMode = REQUIRED)
    @NonNull
    private String status;

    @Schema(description = "The period's closedAt at freeze time", requiredMode = REQUIRED)
    @NonNull
    private Instant periodClosedAt;

    @Schema(description = "Freeze timestamp", requiredMode = REQUIRED)
    @NonNull
    private Instant frozenAt;

    @Schema(description = "User who froze the snapshot", example = "controller", requiredMode = REQUIRED)
    @NonNull
    private String frozenBy;

    @Schema(description = "SHA-256 (lowercase hex) over the canonical report serialization", requiredMode = REQUIRED)
    @NonNull
    private String contentHash;

    @Schema(description = "Frozen per-jurisdiction rows in report order", requiredMode = REQUIRED)
    @NonNull
    private List<TaxLiabilityRow> rows;

    @Schema(description = "Grand total taxable base", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalTaxableBase;

    @Schema(description = "Grand total exempt base", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalExemptBase;

    @Schema(description = "Grand total gross tax collected", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalTaxCollectedGross;

    @Schema(description = "Grand total credits netted", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalCreditsNetted;

    @Schema(description = "Grand total net tax", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalNetTax;

    @Schema(description = "GL-drift reconciliation block as frozen", requiredMode = REQUIRED)
    @NonNull
    private TaxLiabilityReconciliation reconciliation;

    @Schema(
            description = "Id of the ACTIVE snapshot this one superseded at freeze time; null for a first freeze",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID supersedesSnapshotId;

    @Schema(description = "When this snapshot was superseded; null while ACTIVE", requiredMode = NOT_REQUIRED)
    @Nullable
    private Instant supersededAt;

    @Schema(
            description = "User whose re-freeze superseded this snapshot; null while ACTIVE",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String supersededBy;
}
