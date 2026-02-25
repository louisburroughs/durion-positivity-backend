package com.positivity.inventory.internal.dto.replenishment;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReplenishmentPolicyRequest {

    @NotNull
    private UUID locationId;

    @NotBlank
    private String itemSKU;

    @NotNull
    @Min(0)
    private Integer minimumQuantity;

    @NotNull
    @Min(1)
    private Integer maximumQuantity;

    @AssertTrue(message = "minimumQuantity must be less than maximumQuantity")
    public boolean isMinimumLessThanMaximum() {
        if (minimumQuantity == null || maximumQuantity == null)
            return true;
        return minimumQuantity < maximumQuantity;
    }
}
