package com.positivity.inventory.internal.dto.returns;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnableItemDto {
    private UUID itemId;
    private String sku;
    private String description;
    private int quantityReturnable;
    private UUID workorderId;
}
