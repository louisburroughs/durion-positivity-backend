package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * List-item view of a frozen Sales-Tax Liability snapshot (issue #998): identity, lifecycle, and
 * headline figures without the per-jurisdiction rows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Sales-Tax Liability snapshot list item (no rows)")
public class TaxLiabilitySnapshotSummary {

    @Schema(description = "Snapshot id", requiredMode = REQUIRED)
    @NonNull
    private UUID snapshotId;

    @Schema(description = "Accounting period code (YYYY-MM)", example = "2026-06", requiredMode = REQUIRED)
    @NonNull
    private String periodCode;

    @Schema(
            description = "Snapshot lifecycle status: ACTIVE or SUPERSEDED",
            example = "ACTIVE",
            requiredMode = REQUIRED)
    @NonNull
    private String status;

    @Schema(description = "Freeze timestamp", requiredMode = REQUIRED)
    @NonNull
    private Instant frozenAt;

    @Schema(description = "User who froze the snapshot", example = "controller", requiredMode = REQUIRED)
    @NonNull
    private String frozenBy;

    @Schema(description = "Grand total net tax as frozen", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalNetTax;

    @Schema(description = "Whether GL drift was within tolerance at freeze time", requiredMode = REQUIRED)
    @NonNull
    private Boolean reconciled;

    @Schema(description = "SHA-256 canonical content hash", requiredMode = REQUIRED)
    @NonNull
    private String contentHash;
}
