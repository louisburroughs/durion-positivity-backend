package com.positivity.inventory.internal.dto.returns;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "A single line of a return request: the SKU and the quantity being returned")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReturnItemLine {

    @Schema(
            description = "Identifier of the SKU being returned",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID skuId;

    @Schema(description = "Quantity of the SKU being returned", example = "2", requiredMode = REQUIRED)
    @Positive
    private int quantityReturned;
}
