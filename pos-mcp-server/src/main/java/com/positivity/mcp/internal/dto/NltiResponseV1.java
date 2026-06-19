package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Natural-language task interpretation response (version 1)")
public record NltiResponseV1(
        @Schema(
                        description = "Unique identifier of the interpretation request",
                        example = "01960003-0000-7000-8000-000000000007",
                        requiredMode = REQUIRED)
                @NotNull
                UUID requestId,
        @Schema(
                        description = "Correlation identifier linking the response to the originating request flow",
                        example = "01960003-0000-7000-8000-000000000002",
                        requiredMode = REQUIRED)
                @NotNull
                UUID correlationId,
        @Schema(
                        description = "Conversation session identifier the request belonged to",
                        example = "01960003-0000-7000-8000-000000000006",
                        requiredMode = NOT_REQUIRED)
                UUID sessionId,
        @Schema(
                        description = "Status of the interpretation",
                        example = "RESOLVED",
                        requiredMode = REQUIRED)
                @NotNull
                String status,
        @Schema(
                        description = "Interpretation result payload, typically the resolved intent",
                        requiredMode = NOT_REQUIRED)
                Object result,
        @Schema(
                        description = "Additional metadata about the interpretation",
                        example = "{\"durationMs\": 142}",
                        requiredMode = NOT_REQUIRED)
                Map<String, Object> meta) {}
