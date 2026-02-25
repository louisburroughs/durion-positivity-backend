package com.positivity.inventory.internal.dto.replenishment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriggerReplenishmentRequest {

    private String productId;
    private UUID pickFaceLocationId;
}
