package com.positivity.vehiclefitment.internal.dto;

import com.positivity.vehiclefitment.internal.entity.TagType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a fitment tag in API requests/responses.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Fitment tag key/value pair used for product applicability matching")
public class FitmentTagDto {

    @Schema(description = "Tag category", requiredMode = Schema.RequiredMode.REQUIRED, example = "MAKE")
    @NotNull(message = "tagType is required")
    private TagType tagType;

    @Schema(description = "Tag value for the selected category", requiredMode = Schema.RequiredMode.REQUIRED, example = "Toyota")
    @NotBlank(message = "tagValue is required")
    @Size(max = 120, message = "tagValue must not exceed 120 characters")
    private String tagValue;
}
