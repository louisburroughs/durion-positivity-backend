package com.positivity.inventory.internal.dto.consumption;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsumeItemLine {

    private UUID pickTaskId;
    private UUID skuId;
    private int quantity;
}
