package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "User-supplied answer resolving a clarification question for a pending intent")
public record ClarificationResponseDTO(
        @Schema(
                        description = "Identifier of the intent this clarification answer applies to",
                        example = "01960003-0000-7000-8000-000000000004",
                        requiredMode = REQUIRED)
                @NotNull
                UUID intentId,
        @Schema(
                        description = "Option selected by the user from the clarification question choices",
                        example = "Downtown Store",
                        requiredMode = NOT_REQUIRED)
                String selectedOption,
        @Schema(
                        description = "Free-text answer provided by the user when no option matches",
                        example = "The store on Main Street",
                        requiredMode = NOT_REQUIRED)
                String userAnswer) {}
