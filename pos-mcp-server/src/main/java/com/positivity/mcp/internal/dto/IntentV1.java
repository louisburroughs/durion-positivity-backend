package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

@Schema(description = "Resolved natural-language intent (version 1) with its slots and any clarification questions")
public record IntentV1(
        @Schema(
                description = "Unique identifier of the resolved intent",
                example = "01960003-0000-7000-8000-000000000004",
                requiredMode = REQUIRED)
        @NotNull
        UUID intentId,

        @Schema(description = "Classified type of the intent", example = "CREATE_WORKORDER", requiredMode = REQUIRED)
        @NotNull
        String intentType,

        @Schema(description = "Resolution status of the intent", example = "RESOLVED", requiredMode = REQUIRED) @NotNull
        String status,

        @Schema(
                description = "Assessed risk level of executing the intent",
                example = "LOW",
                requiredMode = NOT_REQUIRED)
        String riskLevel,

        @Schema(description = "Extracted slots associated with the intent", requiredMode = NOT_REQUIRED)
        List<IntentSlot> slots,

        @Schema(
                description = "Clarification questions raised when the intent is ambiguous",
                requiredMode = NOT_REQUIRED)
        List<ClarificationQuestion> clarificationQuestions) {}
