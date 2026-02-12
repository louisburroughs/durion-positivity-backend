package com.positivity.security.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
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
        if (authorities == null) {
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
