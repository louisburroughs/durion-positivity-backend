package com.positivity.vehiclefitment.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for filter products response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response payload containing product IDs matching vehicle attributes")
public class FilterProductsResponse {

    @Schema(description = "List of matching product identifiers", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private List<String> productIds;

    @Schema(description = "Count of matching products", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    @Min(0)
    private int count;
}
