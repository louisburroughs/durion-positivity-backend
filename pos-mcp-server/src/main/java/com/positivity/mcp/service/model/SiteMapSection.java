package com.positivity.mcp.service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One navigable section of the durion-positivity-frontend, as published in the frontend's
 * {@code /sitemap.json} artifact and governed by {@code docs/sitemap/sitemap.schema.json} in the
 * frontend repo.
 *
 * <p>Forward-compatible by design: unknown JSON fields added by a newer contract version are
 * ignored ({@link JsonIgnoreProperties}) rather than failing the parse. {@code roles} is optional —
 * {@code null}/empty means the section is reachable by any authenticated user; otherwise the caller
 * must hold at least one of the listed roles.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SiteMapSection(
        @NonNull String route,
        @NonNull String titleKey,
        @NonNull String title,
        @NonNull String descriptionKey,
        @NonNull String description,
        @Nullable List<String> roles,
        @NonNull String group,
        int order) {

    /**
     * Whether a caller holding {@code userRoles} may reach this section. A section without a
     * {@code roles} restriction is open to any authenticated user; otherwise access is granted if
     * the caller holds <em>any</em> of the listed roles.
     */
    public boolean isVisibleTo(@NonNull Collection<String> userRoles) {
        if (roles == null || roles.isEmpty()) {
            return true;
        }
        return roles.stream().anyMatch(userRoles::contains);
    }
}
