package com.positivity.inventory.internal.dto.shortage;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to compute resolution options for an inventory shortage on an allocation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortageResolutionRequest {
    @Schema(
            description = "Identifier of the allocation experiencing the shortage",
            example = "01960003-0000-7000-8000-000000000014",
            requiredMode = REQUIRED)
    @NotNull
    private UUID allocationId;

    @Schema(description = "Stock keeping unit that is short", example = "SKU-10042", requiredMode = REQUIRED)
    @NotBlank
    private String sku;

    @Schema(description = "Quantity that is short and needs resolving", example = "3", requiredMode = REQUIRED)
    @Positive
    private Integer shortQuantity;
}
