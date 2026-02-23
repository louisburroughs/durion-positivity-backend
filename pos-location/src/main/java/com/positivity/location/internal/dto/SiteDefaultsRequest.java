package com.positivity.location.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for configuring default storage locations for a site.
 *
 * Issue: CAP-214 #38
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteDefaultsRequest {

    private UUID defaultStagingLocationId;
    private UUID defaultQuarantineLocationId;
}
