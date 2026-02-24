package com.positivity.inventory.internal.dto.replenishment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReplenishmentPolicyRequest {

    @NotBlank
    private String locationId;

    @NotBlank
    private String itemSKU;

    @NotNull
    @Min(0)
    private Integer minimumQuantity;

    @NotNull
    @Min(1)
    private Integer maximumQuantity;
}