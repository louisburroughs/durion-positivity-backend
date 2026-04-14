package com.positivity.security.common;

import org.jspecify.annotations.NonNull;

/**
 * Definition of a permission to be registered with pos-security-service.
 *
 * <h2>Permission Naming Convention</h2>
 * <p>
 * Permissions follow the pattern: {@code domain:resource:action}
 * </p>
 * <ul>
 *   <li>{@code domain} - Service domain, lowercase (e.g., catalog, crm, pricing)</li>
 *   <li>{@code resource} - Resource type, lowercase with underscores (e.g., product, price_book)</li>
 *   <li>{@code action} - Action type, lowercase (e.g., view, create, edit, delete)</li>
 * </ul>
 *
 * <h2>Common Actions</h2>
 * <ul>
 *   <li>{@code view} - Read/list resources</li>
 *   <li>{@code create} - Create new resources</li>
 *   <li>{@code edit} - Update existing resources</li>
 *   <li>{@code delete} - Delete resources</li>
 *   <li>{@code approve} - Approve workflow items</li>
 *   <li>{@code reject} - Reject workflow items</li>
 * </ul>
 *
 * @param name Permission name in domain:resource:action format
 * @param description Human-readable description
 */
public record PermissionDefinition(@NonNull String name, String description) {
    /**
     * Create a permission definition with name and description.
     *
     * @param name Permission name (e.g., "catalog:product:view")
     * @param description Human-readable description
     * @return permission definition
     */
    public static PermissionDefinition of(@NonNull String name, String description) {
        return new PermissionDefinition(name, description);
    }

    /**
     * Create a permission definition with name only.
     *
     * @param name Permission name (e.g., "catalog:product:view")
     * @return permission definition
     */
    public static PermissionDefinition of(@NonNull String name) {
        return new PermissionDefinition(name, null);
    }
}
