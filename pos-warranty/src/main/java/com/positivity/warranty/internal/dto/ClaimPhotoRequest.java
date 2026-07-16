package com.positivity.warranty.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Add one photo-evidence URL to a claim (URL-reference pattern, PRD §1 scope decisions). */
@Schema(description = "Photo evidence URL to attach to a warranty claim")
public record ClaimPhotoRequest(
        @Schema(description = "Photo URL", example = "https://media.example.com/claims/wc-2026-000123/tire-1.jpg")
        @NotBlank
        @Size(max = 2048)
        String url) {}
