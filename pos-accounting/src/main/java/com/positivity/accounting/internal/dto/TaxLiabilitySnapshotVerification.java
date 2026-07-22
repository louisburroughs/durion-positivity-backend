package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * Result of re-deriving a frozen Sales-Tax Liability snapshot from the live replicas and comparing
 * canonical hashes (issue #998 — the "re-derivable" half of the auditable freeze). A mismatch
 * means the underlying data (replicas, credits, or GL activity) changed after the freeze — e.g.
 * postings into a reopened period — and the snapshot should be re-frozen (supersede) once the
 * period is closed again.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Re-derivation check of a frozen Sales-Tax Liability snapshot against live data")
public class TaxLiabilitySnapshotVerification {

    @Schema(description = "Snapshot id verified", requiredMode = REQUIRED)
    @NonNull
    private UUID snapshotId;

    @Schema(description = "Accounting period code (YYYY-MM)", example = "2026-06", requiredMode = REQUIRED)
    @NonNull
    private String periodCode;

    @Schema(description = "Verification timestamp", requiredMode = REQUIRED)
    @NonNull
    private Instant verifiedAt;

    @Schema(description = "Canonical hash stored at freeze time", requiredMode = REQUIRED)
    @NonNull
    private String snapshotContentHash;

    @Schema(description = "Canonical hash of the report re-derived from live data", requiredMode = REQUIRED)
    @NonNull
    private String recomputedContentHash;

    @Schema(
            description = "True when the re-derived report hashes identically to the frozen snapshot",
            requiredMode = REQUIRED)
    @NonNull
    private Boolean consistent;

    @Schema(description = "Total net tax as frozen", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal frozenTotalNetTax;

    @Schema(description = "Total net tax re-derived from live data", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal recomputedTotalNetTax;

    @Schema(
            description = "recomputedTotalNetTax - frozenTotalNetTax; zero when amounts agree even if row detail moved",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal netTaxDelta;
}
