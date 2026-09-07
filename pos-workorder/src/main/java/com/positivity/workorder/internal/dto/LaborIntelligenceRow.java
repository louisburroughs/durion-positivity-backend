package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a shop's own finished work says about one operation (#1575 Tier 0 / Tier 4 sketch, T0-5).
 *
 * <p>Advisory, always. {@link #suggestedStandardHours} is a candidate a curator may promote by
 * authoring it as a DURION labor standard in pos-catalog; nothing here writes to the catalog and
 * nothing auto-promotes. A number derived from a handful of jobs is a rumour, not a standard,
 * which is why the suggestion is withheld below the sample threshold rather than being offered
 * with a caveat nobody reads.
 *
 * @param serviceId the catalog service the finished lines named
 * @param operationCode the Durion operation code from the local catalog replica, when known
 * @param locationId the shop these lines were worked at
 * @param sampleCount finished service lines behind these numbers
 * @param medianActualHours the shop's median clocked time for the operation
 * @param meanActualHours the mean, reported beside the median so a skewed sample is visible
 * @param medianGuideHours the median guide baseline those same lines were quoted against
 * @param varianceHours medianActual − medianGuide; positive means the shop runs long
 * @param variancePct the same as a percentage of the guide baseline
 * @param suggestedStandardHours the median actual, offered as a candidate standard only once
 *     {@code sampleCount} reaches the configured threshold; null below it
 * @param technicianSampleCount technicians with enough sole-worked lines to have their own median
 * @param fastestTechnicianMedianHours the best technician median, when any technician qualifies
 */
@Schema(
        name = "LaborIntelligenceRow",
        description = "What a shop's own finished work says about one operation: median actual against the"
                + " guide baseline it was quoted at, and a suggested standard once the sample is deep"
                + " enough. Advisory only — promotion is a deliberate catalog write.")
public record LaborIntelligenceRow(
        @Schema(description = "Catalog service the finished lines named.") @NonNull
        UUID serviceId,

        @Schema(description = "Durion operation code, when the local replica knows it.", example = "TIRE-ROTATION")
        @Nullable
        String operationCode,

        @Schema(description = "Shop these lines were worked at.") @Nullable
        UUID locationId,

        @Schema(description = "Finished service lines behind these numbers.", example = "14")
        int sampleCount,

        @Schema(description = "The shop's median clocked time.", example = "0.4") @NonNull
        BigDecimal medianActualHours,

        @Schema(description = "The mean, so a skewed sample is visible.", example = "0.5") @NonNull
        BigDecimal meanActualHours,

        @Schema(description = "Median guide baseline those lines were quoted at.", example = "0.5") @NonNull
        BigDecimal medianGuideHours,

        @Schema(description = "medianActual minus medianGuide; positive means the shop runs long.", example = "-0.1")
        @NonNull
        BigDecimal varianceHours,

        @Schema(description = "The variance as a percentage of the guide baseline.", example = "-20.0") @NonNull
        BigDecimal variancePct,

        @Schema(description = "Candidate standard hours; null until the sample is deep enough.", example = "0.4")
        @Nullable
        BigDecimal suggestedStandardHours,

        @Schema(description = "Technicians with enough sole-worked lines to have their own median.", example = "2")
        int technicianSampleCount,

        @Schema(description = "The best qualifying technician median.", example = "0.3") @Nullable
        BigDecimal fastestTechnicianMedianHours) {}
