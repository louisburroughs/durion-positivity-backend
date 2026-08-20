package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.MeasurementMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for cycle count history entries.
 *
 * <p>Carries every field the owner's tolerance-reconciliation spec (issue #1414 comment) lists as
 * "Required Data" for a count except adjustment quantity and approval status, which live on the
 * linked {@code CycleCountAdjustment} (queryable via {@code cycleCountTaskId}) rather than being
 * duplicated here — a count and its adjustment are deliberately separate records for separate
 * transactions.
 */
@Schema(description = "A single recorded count entry in a cycle count task's history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CountEntryResponse {

    @Schema(
            description = "Unique identifier of the count entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID countEntryId;

    @Schema(
            description = "Identifier of the cycle count task this entry belongs to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID cycleCountTaskId;

    @Schema(
            description = "Identifier of the auditor who recorded the count",
            example = "auditor-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String auditorId;

    @Schema(
            description = "Quantity counted, converted to the product's base UoM — what expectedQuantity and"
                    + " variance are computed against",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull
    private BigDecimal actualQuantity;

    @Schema(
            description = "Quantity exactly as measured, in unitOfMeasure. Equal to actualQuantity when"
                    + " unitOfMeasure is null.",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull
    private BigDecimal measuredQuantity;

    @Schema(
            description = "Unit the count was physically measured in; null means the product's base UoM",
            example = "GAL",
            requiredMode = NOT_REQUIRED)
    private String unitOfMeasure;

    @Schema(description = "How the quantity was obtained", example = "MANUAL_COUNT", requiredMode = REQUIRED)
    @NotNull
    private MeasurementMethod measurementMethod;

    @Schema(
            description = "Book (expected) quantity at the time of the count, in base UoM",
            example = "15",
            requiredMode = REQUIRED)
    @NotNull
    private BigDecimal expectedQuantity;

    @Schema(
            description = "Difference between counted and expected quantity, in base UoM (actual minus expected)",
            example = "-3",
            requiredMode = REQUIRED)
    @NotNull
    private BigDecimal variance;

    @Schema(
            description = "Absolute variance as a percentage of book quantity; 100 when book quantity is zero",
            example = "20.0000",
            requiredMode = REQUIRED)
    @NotNull
    private BigDecimal variancePercentage;

    @Schema(
            description = "Resolved absolute-tolerance bound at count time, if configured for this product/location",
            example = "50",
            requiredMode = NOT_REQUIRED)
    private BigDecimal allowedToleranceAbsolute;

    @Schema(
            description =
                    "Resolved percentage-tolerance bound at count time, if configured for this" + " product/location",
            example = "1.5",
            requiredMode = NOT_REQUIRED)
    private BigDecimal allowedTolerancePercentage;

    @Schema(
            description = "Whether the variance fell within the resolved tolerance. true: reconciled, no"
                    + " adjustment created. false: flagged for investigation and review before an adjustment"
                    + " may be posted.",
            example = "true",
            requiredMode = REQUIRED)
    private boolean withinTolerance;

    @Schema(
            description = "Reason for the variance, when known. Optional even for an out-of-tolerance count — the"
                    + " eventual adjustment's own reason code is what gates posting.",
            example = "Evaporation since last count",
            requiredMode = NOT_REQUIRED)
    private String varianceReason;

    @Schema(
            description = "Sequence number of this count within the recount chain; 0 for the initial count",
            example = "1",
            requiredMode = REQUIRED)
    @NotNull
    private Integer recountSequenceNumber;

    @Schema(
            description = "Identifier of the count entry this entry is a recount of, if any",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID recountOfCountEntryId;

    @Schema(
            description = "Measurement timestamp — when the count was physically taken and recorded",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant countedAt;

    @Schema(
            description = "Whether this entry is a recount of a prior count",
            example = "false",
            requiredMode = REQUIRED)
    private boolean recount;
}
