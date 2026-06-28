package com.positivity.image.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Lightweight view of a stored image, exposing its filename and resolvable URL.
 */
@Schema(description = "Stored image reference exposing its filename and resolvable URL")
public record ImageFileView(
        @Schema(description = "Filename of the stored image", example = "vehicle-front.jpg", requiredMode = REQUIRED)
        @NotNull
        String filename,

        @Schema(
                description = "Resolvable URL where the image can be retrieved",
                example = "https://cdn.example.com/images/vehicle-front.jpg",
                requiredMode = REQUIRED)
        @NotNull
        String url) {}
