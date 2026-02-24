package com.positivity.inventory.internal.dto.putaway;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PutawayLineItemRequest {

    @NotBlank
    private String productId;

    @NotNull
    @Min(1)
    private Integer quantity;
}
