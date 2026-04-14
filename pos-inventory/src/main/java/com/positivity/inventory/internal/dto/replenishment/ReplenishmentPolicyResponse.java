package com.positivity.inventory.internal.dto.replenishment;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplenishmentPolicyResponse {

    private String policyId;
    private UUID locationId;
    private String itemSKU;
    private int minimumQuantity;
    private int maximumQuantity;
    private String createdAt;
}
