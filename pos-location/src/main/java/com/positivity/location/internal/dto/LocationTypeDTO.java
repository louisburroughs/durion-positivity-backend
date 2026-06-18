package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Location type classification reference")
public class LocationTypeDTO {

    @Schema(
            description = "Unique identifier of the location type",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID id;

    @Schema(description = "Name of the location type", example = "STORE", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Description of the location type",
            example = "Retail storefront with point-of-sale operations",
            requiredMode = NOT_REQUIRED)
    private String description;
}
