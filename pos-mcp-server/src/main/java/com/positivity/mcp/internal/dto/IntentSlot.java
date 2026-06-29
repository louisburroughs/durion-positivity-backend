package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Extracted slot of a resolved intent, pairing a named parameter with its value and confidence")
public record IntentSlot(
        @Schema(description = "Name of the slot parameter", example = "locationId", requiredMode = REQUIRED) @NotNull
        String name,

        @Schema(
                description = "Extracted value bound to the slot",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = NOT_REQUIRED)
        Object value,

        @Schema(
                description = "Confidence score for the extracted slot value, between 0 and 1",
                example = "0.92",
                requiredMode = REQUIRED)
        double confidence) {}
