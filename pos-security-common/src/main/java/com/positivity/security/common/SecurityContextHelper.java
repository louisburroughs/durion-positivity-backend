package com.positivity.security.common;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Utility class for accessing authenticated security context information.
 *
 * <h2>Identity Semantics</h2>
 * <ul>
 * <li><b>username</b> is the human-readable audit identity from {@code X-User}.
 * Use this for logs, createdBy/updatedBy fields, and operational audit trails.</li>
 * <li><b>userId</b> is the stable UUID identity sourced primarily from JWT claim
 * {@code uid} (legacy fallback: {@code userId}). Use this only when you must
 * resolve user/person records.</li>
 * </ul>
 *
 * <p>
 * Methods in this helper are intentionally fail-fast for malformed or missing
 * authentication context. Silent fallbacks hide gateway/security integration
 * problems.
 * </p>
 *
 * <p>
 * ADR References:
 * ADR-0018 (Audit Actor Fields from Security Context as Strings)
 * and ADR-0022 (Audit Stable Person Identifier Claim Policy).
 * </p>
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
     * <p>
     * This is the preferred identifier for audit/logging use cases. The helper
     * first reads gateway-injected authentication details and then falls back to
     * principal name.
     * </p>
     * <p>
     * See ADR-0018 for audit actor sourcing rules.
     * </p>
     *
     * @return Optional containing the username
     * @throws IllegalStateException if authentication is missing/not authenticated,
     *                               details map is missing, or no username can be
     *                               resolved
     */
    public static Optional<String> getCurrentUsername() {
        Authentication authentication = requireAuthenticatedAuthentication();
        Map<?, ?> detailsMap = requireAuthenticationDetailsMap(authentication);

        Object detailUsername = detailsMap.get(GatewaySecurityConstants.DETAIL_USERNAME);
        if (detailUsername instanceof String username && !username.isBlank()) {
            return Optional.of(username);
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof String username && !username.isBlank()) {
            return Optional.of(username);
        }
        if (principal != null) {
            String principalValue = principal.toString();
            if (!principalValue.isBlank()) {
                return Optional.of(principalValue);
            }
        }

        throw new IllegalStateException(
                "Authenticated context is present but no username was found in details or principal");
    }

    /**
     * Get the current authenticated username or a default value.
     * <p>
     * Prefer {@link #getCurrentUsername()} when authentication is expected.
     * This method is intended for fallback-only flows (for example,
     * framework/system contexts without user authentication).
     * </p>
     *
     * @param defaultValue Value to return if not authenticated
     * @return Username or default value
     */
    public static String getCurrentUsernameOrDefault(@NonNull String defaultValue) {
        try {
            return getCurrentUsername().orElse(defaultValue);
        } catch (IllegalStateException ex) {
            return defaultValue;
        }
    }

    /**
     * Get the current authenticated stable user identifier.
     * <p>
     * This is the machine identity and should be used only for person/user
     * lookups and relationships requiring stable UUID identity.
     * See ADR-0022 for canonical stable identity requirements.
     * </p>
     *
     * @return Optional containing stable UUID user ID
     * @throws IllegalStateException  if authentication/details map is missing
     * @throws MissingPersonIdException if user id is missing or not a UUID in details map
     */
    private static Optional<UUID> getCurrentUserId() {
        Authentication authentication = requireAuthenticatedAuthentication();
        Map<?, ?> detailsMap = requireAuthenticationDetailsMap(authentication);

        Object userId = detailsMap.get(GatewaySecurityConstants.DETAIL_USER_ID);
        if (userId instanceof UUID userIdUuid) {
            return Optional.of(userIdUuid);
        }

        if (userId == null) {
            throw new MissingPersonIdException("Missing authenticated userId in authentication details key '"
                    + GatewaySecurityConstants.DETAIL_USER_ID + "'");
        }

        throw new MissingPersonIdException(
                "Invalid authenticated userId type. Expected UUID in authentication details key '"
                        + GatewaySecurityConstants.DETAIL_USER_ID + "' but was: "
                        + userId.getClass().getName());
    }

    /**
     * Get the current authenticated stable user identifier as UUID.
     *
     * @return Optional containing UUID user identifier
     * @throws IllegalStateException if authentication/details map is missing
     * @throws MissingPersonIdException if user id is missing or malformed UUID
     */
    public static Optional<UUID> getCurrentUserIdAsUuid() {
        return getCurrentUserId();
    }

    /**
     * Get the current authenticated stable user identifier as UUID or default
     * value.
     *
     * @param defaultValue value to return if unauthenticated or invalid UUID
     * @return stable user identifier UUID or default value
     */
    public static UUID getCurrentUserIdAsUuidOrDefault(@NonNull UUID defaultValue) {
        try {
            return getCurrentUserIdAsUuid().orElse(defaultValue);
        } catch (IllegalStateException ex) {
            return defaultValue;
        }
    }

    /**
     * Get the current authenticated stable user identifier as UUID.
     *
     * @return stable user identifier UUID
     * @throws MissingPersonIdException if no authenticated user identifier is
     *                                  available or the value is not a valid UUID
     */
    public static UUID getCurrentUserIdAsUuidOrThrowIllegalStateException() throws MissingPersonIdException {
        return getCurrentUserIdAsUuid()
                .orElseThrow(() -> new MissingPersonIdException(
                        "Missing authenticated UUID userId for flow requiring UUID audit identity"));
    }

    /**
     * Get all authorities for the current user.
     *
     * @return Set of authority strings
     * @throws IllegalStateException if authentication is missing/not authenticated
     */
    public static Set<String> getAuthorities() {
        Authentication authentication = requireAuthenticatedAuthentication();
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
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
        return authentication != null
                && authentication.isAuthenticated()
                && !GatewaySecurityConstants.ANONYMOUS_USER.equals(authentication.getPrincipal());
    }

    private static Authentication requireAuthenticatedAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException("Security context has no Authentication");
        }
        if (!authentication.isAuthenticated()) {
            throw new IllegalStateException("Security context Authentication is not authenticated");
        }
        if (GatewaySecurityConstants.ANONYMOUS_USER.equals(authentication.getPrincipal())) {
            throw new IllegalStateException("Anonymous authentication is not allowed for this helper");
        }
        return authentication;
    }

    private static Map<?, ?> requireAuthenticationDetailsMap(Authentication authentication) {
        Object details = authentication.getDetails();
        if (details instanceof Map<?, ?> detailsMap) {
            return detailsMap;
        }
        throw new IllegalStateException("Authentication details map is missing from security context");
    }
}
