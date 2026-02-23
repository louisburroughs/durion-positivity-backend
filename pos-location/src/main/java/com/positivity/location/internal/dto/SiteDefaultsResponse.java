package com.positivity.location.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for site default storage location configuration.
 *
 * Issue: CAP-214 #38
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteDefaultsResponse {

    private UUID siteId;
    private UUID defaultStagingLocationId;
    private UUID defaultQuarantineLocationId;
}
