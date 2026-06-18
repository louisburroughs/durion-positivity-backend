package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "A detected scheduling or resource conflict on the dispatch board")
public class ConflictEntry {
    @Schema(description = "Type of conflict detected", example = "DOUBLE_BOOKING", requiredMode = REQUIRED)
    String conflictType;

    @Schema(description = "Severity of the conflict", example = "HIGH", requiredMode = REQUIRED)
    String severity;

    @Schema(
            description = "Human-readable conflict message",
            example = "Mechanic assigned to two workorders in the same slot",
            requiredMode = REQUIRED)
    String message;

    @Schema(
            description = "Identifier of the affected resource (mechanic or bay)",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    String affectedResourceId;

    @Schema(
            description = "Identifier of the affected workorder",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    String affectedWorkorderId;
}
