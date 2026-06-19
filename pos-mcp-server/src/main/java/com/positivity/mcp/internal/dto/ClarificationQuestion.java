package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "Clarification question presented to the user to resolve an ambiguous intent")
public record ClarificationQuestion(
        @Schema(
                        description = "Text of the clarification question",
                        example = "Which location did you mean?",
                        requiredMode = REQUIRED)
                @NotNull
                String text,
        @Schema(
                        description = "Selectable answer options for the clarification question",
                        example = "[\"Downtown Store\", \"Airport Kiosk\"]",
                        requiredMode = NOT_REQUIRED)
                List<String> options) {}
