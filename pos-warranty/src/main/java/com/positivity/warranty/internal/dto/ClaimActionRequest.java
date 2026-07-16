package com.positivity.warranty.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Optional reason payload for the cancel/close terminal moves (PRD §8). */
@Schema(description = "Optional reason for a claim lifecycle action")
public record ClaimActionRequest(
        @Schema(description = "Reason recorded in the claim status history") @Size(max = 10_000)
        String reason) {}
