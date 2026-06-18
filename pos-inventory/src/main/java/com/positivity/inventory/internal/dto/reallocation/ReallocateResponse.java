package com.positivity.inventory.internal.dto.reallocation;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Result of a stock reallocation, summarizing reallocated quantities and resulting availability")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReallocateResponse {

    @Schema(
            description = "Identifier of the stock item that was reallocated",
            example = "01960003-0000-7000-8000-000000000110",
            requiredMode = REQUIRED)
    @NotNull
    private UUID stockItemId;

    @Schema(
            description = "Total quantity reallocated across all affected reservations",
            example = "12",
            requiredMode = REQUIRED)
    private int totalReallocated;

    @Schema(
            description = "Number of audit records created during the reallocation",
            example = "3",
            requiredMode = REQUIRED)
    private int auditRecordsCreated;

    @Schema(
            description = "Available-to-promise quantity for the stock item after reallocation",
            example = "20",
            requiredMode = REQUIRED)
    private int atpAfterReallocation;
}
