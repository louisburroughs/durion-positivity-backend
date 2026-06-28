package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Natural-language task interpretation request submitted by a client")
public record NltiRequestDTO(
        @Schema(
                description = "Natural-language prompt to interpret",
                example = "Create a workorder for an oil change at the downtown store",
                requiredMode = REQUIRED)
        @NotBlank
        String prompt,

        @Schema(
                description = "Conversation session identifier to maintain interpretation context",
                example = "01960003-0000-7000-8000-000000000006",
                requiredMode = NOT_REQUIRED)
        UUID sessionId,

        @Schema(
                description = "Arbitrary client-supplied context to aid interpretation",
                example = "{\"locationId\": \"01960003-0000-7000-8000-000000000001\"}",
                requiredMode = NOT_REQUIRED)
        Map<String, Object> clientContext) {}
