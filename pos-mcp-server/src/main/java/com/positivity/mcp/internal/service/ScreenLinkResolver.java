package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ScreenLink;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the best UI screen for a request that no tool could answer (rung 3 of the answer
 * resolution ladder). Returns empty when nothing clears the similarity floor or the caller lacks
 * permission for every candidate.
 *
 * <p>Resolution is two-tiered: first the semantic, permission-gated screen registry; then, only when
 * the registry surfaces nothing the caller can open, a coarse fallback over the frontend site map
 * (gated by the caller's <em>roles</em> rather than permission codes). The registry remains the
 * fine-grained path — the site map contributes section-level routes so a fresh frontend deployment
 * is reachable without re-seeding the registry.
 */
public interface ScreenLinkResolver {

    /**
     * @param userMessage      the original request text (embedded for the search)
     * @param domain           optional domain hint (e.g. router-classified scope); {@code null} = all
     * @param callerPermissions the caller's bare permission codes, used to gate the registry's
     *     {@code required_perm}
     * @param callerRoles      the caller's {@code ROLE_*} authorities, used to gate site-map sections
     *     (whose access control is role-based, unlike the permission-gated registry); a section
     *     without a {@code roles} restriction is open to any authenticated caller
     * @param params           values for filling the screen's url_template (status, locationId, …)
     */
    @NonNull
    Optional<ScreenLink> resolve(
            @NonNull String userMessage,
            @Nullable String domain,
            @NonNull Set<String> callerPermissions,
            @NonNull Set<String> callerRoles,
            @NonNull Map<String, String> params);
}
