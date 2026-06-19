package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Response payload confirming a created sale-restriction override")
public record RestrictionOverrideResponse(
        @Schema(
                        description = "Identifier of the created override",
                        example = "9c3b1f2a-5d6e-4a7b-8c9d-0e1f2a3b4c5d",
                        requiredMode = REQUIRED)
                @NotNull UUID overrideId,
        @Schema(
                        description = "Timestamp when the override expires",
                        example = "2026-03-05T21:38:21.752Z",
                        requiredMode = REQUIRED)
                @NotNull Instant expiresAt) {}
