package com.positivity.inventory.internal.dto.returns;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReturnItemLine {

    @NotNull
    private UUID skuId;

    @Positive
    private int quantityReturned;
}
