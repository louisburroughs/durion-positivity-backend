package com.positivity.inventory.internal.dto.replenishment;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriggerReplenishmentRequest {

    private String productId;
    private UUID pickFaceLocationId;
}
