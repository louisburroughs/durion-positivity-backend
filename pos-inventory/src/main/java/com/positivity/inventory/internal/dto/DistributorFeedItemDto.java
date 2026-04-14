package com.positivity.inventory.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canonical distributor feed item payload for normalization.
 *
 * Issue: CAP-170 (#47)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributorFeedItemDto {
    private UUID productId;
    private String distributorId;
    private String distributorSku;
    private Integer quantityAvailable;
    private String rawLeadTime;
    private String rawShipFromRegion;
}
