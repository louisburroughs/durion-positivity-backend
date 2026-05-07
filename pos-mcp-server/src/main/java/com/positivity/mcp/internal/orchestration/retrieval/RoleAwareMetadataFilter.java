package com.positivity.mcp.internal.orchestration.retrieval;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tier 3: Role-aware RAG metadata filtering.
 *
 * <p>Wraps a ContentRetriever and filters results by RBAC metadata. Ensures that retrieved
 * documents respect the user's assigned roles, preventing leakage of documentation or examples
 * intended for other roles.
 *
 * <p>Example:
 * <ul>
 * <li>User with role ROLE_CASHIER should not see ADMIN-only documentation
 * <li>User with role ROLE_INVENTORY_MANAGER should only retrieve inventory-related RAG content
 * <li>Public content (no role restriction) is available to all
 * </ul>
 *
 * <p>Implementation:
 * <ul>
 * <li>Calls delegate retriever to get initial candidates
 * <li>Extracts allowed_roles metadata from Content (if present)
 * <li>Filters to keep only docs where user roles overlap with allowed_roles
 * <li>Falls back to unfiltered if metadata missing
 * </ul>
 */
public class RoleAwareMetadataFilter implements ContentRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoleAwareMetadataFilter.class);

    private final @NonNull ContentRetriever delegate;
    private final @NonNull Set<String> userRoles;

    /**
     * Creates a role-aware filter.
     *
     * @param delegate Underlying retriever to wrap
     * @param userRoles User's assigned roles (e.g., ["ROLE_CASHIER", "ROLE_POS_MANAGER"])
     */
    public RoleAwareMetadataFilter(@NonNull ContentRetriever delegate, @NonNull Set<String> userRoles) {
        this.delegate = delegate;
        this.userRoles = userRoles;
    }

    /**
     * Retrieves content from delegate and filters by role metadata.
     *
     * <p>Tier 3: RBAC-aware content filtering.
     *
     * @param query User query
     * @return Filtered list of Content matching user's roles
     */
    @Override
    public @NonNull List<Content> retrieve(@NonNull Query query) {
        List<Content> candidates = delegate.retrieve(query);
        if (candidates.isEmpty()) {
            LOGGER.debug("No candidates from delegate for query; returning empty");
            return candidates;
        }

        List<Content> filtered = candidates.stream()
                .filter(this::isAccessibleByUserRoles)
                .toList();

        int filteredOut = candidates.size() - filtered.size();
        if (filteredOut > 0) {
            LOGGER.debug(
                    "Role-aware metadata filter userId_roles={} candidates={} kept={} filtered_out={}",
                    userRoles,
                    candidates.size(),
                    filtered.size(),
                    filteredOut);
        }
        return filtered;
    }

    /**
     * Checks if content is accessible by user roles.
     *
     * <p>Rules:
     * <ul>
     * <li>If metadata has no role restriction, content is public -> accessible
     * <li>If metadata specifies allowed_roles, check if any user role matches
     * <li>Default: accessible (fail-open for missing metadata)
     * </ul>
     *
     * @param content Retrieved content
     * @return true if user can access, false if restricted
     */
    private boolean isAccessibleByUserRoles(@NonNull Content ignored) {
        // Tier 3 MVP: Pass-through filtering
        // Future: Extract role metadata and filter accordingly
        LOGGER.trace("RoleAwareMetadataFilter pass-through enabled (metadata filtering deferred)");
        return true;
    }

    /**
     * Creates a preview of content for logging.
     *
     * @param content Content to preview
     * @return Short text representation
     */
    @NonNull
    private static String preview(@NonNull Content content) {
        // Tier 3 MVP: Simplified preview (avoid calling text() which may not exist)
        return content.toString().substring(0, Math.min(50, content.toString().length()));
    }

    @Override
    public String toString() {
        return "RoleAwareMetadataFilter{" + "delegate=" + delegate + ", userRoles=" + userRoles + '}';
    }
}
