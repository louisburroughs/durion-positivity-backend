package com.positivity.vehiclefitment.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO for filtering products by vehicle attributes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for filtering products by vehicle attributes")
public class FilterProductsRequest {

    @Schema(description = "Vehicle attributes used as matching criteria", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "{\"make\":\"Toyota\",\"model\":\"Camry\",\"year_range\":\"2018-2022\"}")
    @NotNull(message = "vehicleAttributes is required")
    @NotEmpty(message = "vehicleAttributes must not be empty")
    private Map<@NotBlank(message = "attribute key must not be blank") String,
            @NotBlank(message = "attribute value must not be blank") String> vehicleAttributes;
}
