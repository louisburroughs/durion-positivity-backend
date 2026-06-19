package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Service catalog entry resolved from pos-catalog")
public class ServiceEntityDTO {

    @Schema(description = "Internal service identifier", example = "501", requiredMode = REQUIRED)
    private Long id;

    @Schema(description = "Service display name", example = "Oil Change", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Service description",
            example = "Standard oil and filter replacement",
            requiredMode = NOT_REQUIRED)
    private String description;
    // Add other fields as needed from pos-catalog ServiceEntity
}
