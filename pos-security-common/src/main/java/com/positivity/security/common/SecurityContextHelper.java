package com.positivity.security.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Utility class for accessing security context information.
 *
 * <h2>Usage</h2>
 * 
 * <pre>{@code
 * // Get current username
 * String user = SecurityContextHelper.getCurrentUsername().orElse("anonymous");
 *
 * // Check if user has authority
 * if (SecurityContextHelper.hasAuthority("catalog:product:edit")) {
 *     // Allow edit
 * }
 *
 * // Get all authorities
 * Set<String> authorities = SecurityContextHelper.getAuthorities();
 * }</pre>
 */
public final class SecurityContextHelper {

    private SecurityContextHelper() {
        // Utility class
    }

    /**
     * Get the current authenticated username.
     *
     * @return Optional containing username, or empty if not authenticated
     */
    public static Optional<String> getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof String username) {
            return Optional.of(username);
        }
        return Optional.of(principal.toString());
    }

    /**
     * Get the current authenticated username or a default value.
     *
     * @param defaultValue Value to return if not authenticated
     * @return Username or default value
     */
    public static String getCurrentUsernameOrDefault(@NonNull String defaultValue) {
        return getCurrentUsername().orElse(defaultValue);
    }

    /**
     * Get the current authenticated stable person identifier.
     * 
     * @deprecated Prefer {@link #getCurrentUsername()} for flows requiring a stable
     *             authenticated identity, and explicitly handle missing/invalid
     *             user ID cases.
     * @return stable person identifier
     * @throws MissingPersonIdException if no authenticated person identifier is
     *                                  available
     */
    @Deprecated(since = "2024-06", forRemoval = true)
    public static String getCurrentUserIdOrThrowIllegalStateException() throws MissingPersonIdException {
        return getCurrentUserId()
                .orElseThrow(() -> new MissingPersonIdException(
                        "Missing authenticated personId for flow requiring audit identity"));
    }

    /**
     * Get the current authenticated stable person identifier.
     * <p>
     * Resolves from gateway-injected authentication details (`X-User-Id`) when
     * available. Falls back to principal/username for compatibility.
     * 
     * @deprecated Prefer {@link #getCurrentUsername()} for flows requiring a stable
     *             authenticated identity, and explicitly handle missing/invalid
     *             user ID cases.
     * @return Optional containing stable user ID/person ID, or empty if
     *         unauthenticated
     */
    @Deprecated(since = "2024-06", forRemoval = true)
    public static Optional<String> getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        Object details = authentication.getDetails();
        if (details instanceof Map<?, ?> detailsMap) {
            Object userId = detailsMap.get(GatewaySecurityConstants.DETAIL_USER_ID);
            if (userId instanceof String userIdStr && !userIdStr.isBlank()) {
                return Optional.of(userIdStr);
            }
        }

        return getCurrentUsername();
    }

    /**
     * Get the current authenticated stable person identifier or a default value.
     * 
     * @deprecated Prefer {@link #getCurrentUsernameOrDefault(String)} for flows
     *             requiring a stable authenticated identity, and explicitly handle
     *             missing/invalid user ID cases.
     * @param defaultValue value to return if unauthenticated
     * @return stable user ID/person ID or default value
     */
    @Deprecated(since = "2024-06", forRemoval = true)
    public static String getCurrentUserIdOrDefault(@NonNull String defaultValue) {
        return getCurrentUserId().orElse(defaultValue);
    }

    /**
     * Get the current authenticated stable person identifier as UUID.
     *
     * @return Optional containing UUID person identifier, or empty when absent or
     *         not a valid UUID
     */
    public static Optional<UUID> getCurrentUserIdAsUuid() {
        return getCurrentUserId().flatMap(SecurityContextHelper::tryParseUuid);
    }

    /**
     * Get the current authenticated stable person identifier as UUID or default
     * value.
     *
     * @param defaultValue value to return if unauthenticated or invalid UUID
     * @return stable person identifier UUID or default value
     */
    public static UUID getCurrentUserIdAsUuidOrDefault(@NonNull UUID defaultValue) {
        return getCurrentUserIdAsUuid().orElse(defaultValue);
    }

    /**
     * Get the current authenticated stable person identifier as UUID.
     *
     * @return stable person identifier UUID
     * @throws MissingPersonIdException if no authenticated person identifier is
     *                                  available or the value is not a valid UUID
     */
    public static UUID getCurrentUserIdAsUuidOrThrowIllegalStateException() throws MissingPersonIdException {
        return getCurrentUserIdAsUuid()
                .orElseThrow(() -> new MissingPersonIdException(
                        "Missing authenticated UUID personId for flow requiring UUID audit identity"));
    }

    private static Optional<UUID> tryParseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Get all authorities for the current user.
     *
     * @return Set of authority strings, or empty set if not authenticated
     */
    public static Set<String> getAuthorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Collections.emptySet();
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities.isEmpty()) {
            return Collections.emptySet();
        }
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    /**
     * Check if the current user has a specific authority.
     *
     * @param authority Authority to check (e.g., "catalog:product:view" or
     *                  "ROLE_ADMIN")
     * @return true if user has the authority
     */
    public static boolean hasAuthority(@NonNull String authority) {
        return getAuthorities().contains(authority);
    }

    /**
     * Check if the current user has any of the specified authorities.
     *
     * @param authorities Authorities to check
     * @return true if user has at least one of the authorities
     */
    public static boolean hasAnyAuthority(@NonNull String... authorities) {
        Set<String> userAuthorities = getAuthorities();
        for (String authority : authorities) {
            if (userAuthorities.contains(authority)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the current user has all of the specified authorities.
     *
     * @param authorities Authorities to check
     * @return true if user has all of the authorities
     */
    public static boolean hasAllAuthorities(@NonNull String... authorities) {
        Set<String> userAuthorities = getAuthorities();
        for (String authority : authorities) {
            if (!userAuthorities.contains(authority)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if the current user has a specific role.
     *
     * @param role Role to check (without ROLE_ prefix)
     * @return true if user has the role
     */
    public static boolean hasRole(@NonNull String role) {
        String roleAuthority = role.startsWith(GatewaySecurityConstants.ROLE_PREFIX)
                ? role
                : GatewaySecurityConstants.ROLE_PREFIX + role;
        return hasAuthority(roleAuthority);
    }

    /**
     * Check if there is an authenticated user in the current context.
     *
     * @return true if a user is authenticated
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !GatewaySecurityConstants.ANONYMOUS_USER.equals(authentication.getPrincipal());
    }
}
