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
 */
public interface ScreenLinkResolver {

    /**
     * @param userMessage      the original request text (embedded for the search)
     * @param domain           optional domain hint (e.g. router-classified scope); {@code null} = all
     * @param callerPermissions the caller's permission codes, used to gate {@code required_perm}
     * @param params           values for filling the screen's url_template (status, locationId, …)
     */
    @NonNull
    Optional<ScreenLink> resolve(
            @NonNull String userMessage,
            @Nullable String domain,
            @NonNull Set<String> callerPermissions,
            @NonNull Map<String, String> params);
}
