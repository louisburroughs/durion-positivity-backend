package com.positivity.securityservice.internal.security.service;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "is the caller this user?" for {@code @PreAuthorize} expressions (#1612).
 *
 * <p>Reading your own permissions is not the same act as reading someone else's, but
 * {@code GET /v1/users/{userId}/permissions} required {@code security:permission:view} for both.
 * Two roles hold that code, so in practice nobody could ask what they themselves are allowed to do
 * — and {@code AdminFacadeTool.getMyPermissions}, which delegates to this endpoint with the
 * caller's own id, was unreachable for thirteen roles because of it.
 *
 * <p>The comparison has to go through a lookup: {@code GatewayHeaderAuthenticationFilter} builds
 * the {@code Authentication} from the {@code X-User} header, so the principal is a username while
 * the path variable is a UUID. There is no shortcut in SpEL for that.
 *
 * <p>This widens a read, so it fails closed on every uncertainty — no authentication, an
 * unauthenticated token, a blank principal, or a username matching no user all return {@code
 * false}, leaving the permission check as the only way through.
 *
 * <p>It lives beside {@code JwtService} rather than in {@code internal.security} because it reads a
 * repository, and the cross-module rule
 * {@code ArchitectureTests#repositoriesShouldOnlyBeAccessedFromServiceLayer} admits only the
 * service and dao layers as repository consumers. Answering "who is the caller" from stored user
 * data is service work, so the rule is right and the first placement was wrong.
 */
@Component("userSelfCheck")
public class UserSelfCheck {

    private final UserRepository userRepository;

    public UserSelfCheck(@NonNull UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * True when the authenticated caller is the user named by {@code userId}.
     *
     * @param userId the user whose data is being requested; null yields false
     */
    @Transactional(readOnly = true)
    public boolean isSelf(@Nullable UUID userId) {
        if (userId == null) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        String username = authentication.getName();
        if (username == null || username.isBlank()) {
            return false;
        }
        return userRepository
                .findByUsername(username)
                .map(User::getId)
                .filter(userId::equals)
                .isPresent();
    }
}
