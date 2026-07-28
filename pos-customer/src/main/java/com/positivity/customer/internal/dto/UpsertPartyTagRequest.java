package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create or update a CRM party tag")
public class UpsertPartyTagRequest {

    @Schema(description = "Unique tag name", example = "Fleet Prospect", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 100)
    private String name;

    @Schema(description = "Optional display grouping", example = "Lifecycle", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 100)
    private String category;

    @Schema(description = "Presentation colour hint", example = "#1f7a4d", requiredMode = NOT_REQUIRED)
    @Nullable
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "color must be a #rrggbb hex value")
    private String color;

    @Schema(
            description = "Whether the tag can be assigned; defaults to true on create",
            example = "true",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private Boolean active;
}
