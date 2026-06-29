package com.positivity.inventory.internal.dto.consumption;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(
        description =
                "A single line in a consumption request, identifying a SKU and quantity to consume against a pick task")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsumeItemLine {

    @Schema(
            description = "Identifier of the pick task the consumed items are drawn from",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID pickTaskId;

    @Schema(
            description = "Identifier of the SKU being consumed",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID skuId;

    @Schema(description = "Quantity of the SKU to consume", example = "4", requiredMode = REQUIRED)
    @Positive
    private int quantity;
}
