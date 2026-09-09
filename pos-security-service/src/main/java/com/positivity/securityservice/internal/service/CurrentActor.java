package com.positivity.securityservice.internal.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The name to stamp as the actor of a change made through the currently authenticated request, or
 * {@code "system"} when there is none.
 *
 * <p>This is the same fallback {@code RoleManagementServiceImpl.getCurrentUsername} used before
 * ADR-0061 amendment phase 2 (#1914); {@link UserServiceImpl}, {@link SelfRegistrationServiceImpl}
 * and {@link UserRoleGrantServiceImpl} all need it too now that they write {@code
 * role_assignments} rows directly, so it is shared here rather than copied a third and fourth
 * time.
 */
final class CurrentActor {

    private CurrentActor() {}

    static String resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return "system";
    }
}
