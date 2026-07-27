package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Request to create a substitution group")
public class SubstitutionGroupCreateRequestDto {

    @NotBlank
    @Size(max = 128)
    @Schema(description = "Group name", example = "5W-30 full synthetic quarts", requiredMode = REQUIRED)
    private String name;

    @Size(max = 512)
    @Schema(description = "Free-text notes", example = "Any brand acceptable", requiredMode = NOT_REQUIRED)
    private String notes;
}
