package com.positivity.inventory.internal.dto.putaway;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "A single product and quantity line for a putaway tasks generation request")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PutawayLineItemRequest {

    @Schema(
            description = "Identifier of the product to be put away",
            example = "01960003-0000-7000-8000-000000000080",
            requiredMode = REQUIRED)
    @NotBlank
    private String productId;

    @Schema(
            description = "Quantity of the product to be put away",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull
    @Min(1)
    private Integer quantity;
}
