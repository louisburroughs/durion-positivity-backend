package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.service.model.SiteMap;
import com.positivity.mcp.internal.service.model.SiteMapSection;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Read-only access to the durion-positivity-frontend site map (the frontend's {@code /sitemap.json}
 * artifact). Implementations fetch the artifact over HTTP, validate it against the shared contract,
 * cache it with a TTL, and keep a last-known-good copy so a brief frontend outage does not break the
 * server.
 *
 * <p>This is a <strong>pull-only</strong> consumer: no frontend code/types are imported and the
 * frontend never pushes here. The only shared contract is the JSON schema plus its integer
 * {@code version}.
 */
public interface SiteMapService {

    /**
     * Returns the site map, serving the cached copy while it is fresh and otherwise fetching a new
     * one. On a fetch failure with a warm cache the last-known-good copy is returned; on a fetch
     * failure with a cold cache a {@link SiteMapUnavailableException} is thrown.
     */
    @NonNull
    SiteMap getSiteMap();

    /**
     * Forces an HTTP fetch regardless of the cache TTL (e.g. an admin "reload" action), updating the
     * cache on success. Falls back to last-known-good on failure with the same semantics as
     * {@link #getSiteMap()}.
     */
    @NonNull
    SiteMap refresh();

    /**
     * Filters the currently cached site map to the sections the given roles can reach. Pure and
     * cached-only — performs no I/O; callers must ensure the cache is warm (e.g. call
     * {@link #getSiteMap()} first). Returns an empty list when the cache is cold. A section without a
     * {@code roles} restriction is visible to any authenticated user; otherwise the caller must hold
     * at least one of the section's roles.
     */
    @NonNull
    List<SiteMapSection> visibleSections(@NonNull Collection<String> userRoles);
}
