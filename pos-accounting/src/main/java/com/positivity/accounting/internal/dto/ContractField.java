package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single field definition in an event envelope contract")
public class ContractField {

    @Schema(description = "Name of the field", example = "eventId", requiredMode = REQUIRED)
    private String name;

    @Schema(
            description = "JSON path locating the field within the envelope",
            example = "$.payload.eventId",
            requiredMode = REQUIRED)
    private String jsonPath;

    @Schema(description = "Data type of the field", example = "string", requiredMode = REQUIRED)
    private String type;

    @Schema(description = "Whether the field is required in the envelope", example = "true", requiredMode = REQUIRED)
    private boolean required;

    @Schema(
            description = "Human-readable description of the field",
            example = "Unique identifier of the event",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Nullable
    @Schema(
            description = "Allowed enum values for the field, if constrained",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private List<String> enumValues;
}
