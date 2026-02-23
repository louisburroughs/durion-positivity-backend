package com.positivity.location.service;

import com.positivity.location.internal.dto.SiteDefaultsRequest;
import com.positivity.location.internal.dto.SiteDefaultsResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Public service contract for site default storage location operations.
 *
 * Issue: CAP-214 #38
 */
public interface SiteDefaultsService {

    /**
     * Configures default staging and quarantine locations for a site.
     *
     * @param siteId  site identifier from the route
     * @param request request containing staging and quarantine location IDs
     * @return configured site defaults response
     */
    @NonNull
    SiteDefaultsResponse configureDefaults(@NonNull UUID siteId, @NonNull SiteDefaultsRequest request);

    /**
     * Gets configured defaults for a site.
     *
     * @param siteId site identifier from the route
     * @return site defaults response, potentially with null defaults
     */
    @NonNull
    SiteDefaultsResponse getDefaults(@NonNull UUID siteId);
}
