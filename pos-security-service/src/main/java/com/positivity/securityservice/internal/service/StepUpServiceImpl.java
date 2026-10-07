package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.StepUpResponse;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.StepUpDeniedException;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.tenancy.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * The step-up credential check (CAP:550 S16, #2512; AW31).
 *
 * <p>It runs the sign-in's own checks — the account lookup, the lockout pre-check, the {@link
 * AuthenticationManager} (password, enabled, expired, locked) — in the tenant the calling service
 * bound from its own request, never one named in the body (ADR-0062). A wrong password counts as a
 * failed attempt under the sign-in lockout policy ({@code LockoutPolicy}), exactly as a failed sign-in
 * does. Unlike a sign-in it issues no token, opens no session and does not record a successful sign-in:
 * the person never signed in, they only proved who they are for one action.
 *
 * <p>The permission is answered from the person's effective roles at this instant, the same expansion
 * the token issuer uses ({@link RoleAuthorityService#expandRolesToAuthorities}).
 *
 * <p>The password is never logged; every refusal throws {@link StepUpDeniedException}, whose reason is
 * logged here and never answered.
 */
@Service
public class StepUpServiceImpl implements StepUpService {

    private static final Logger log = LoggerFactory.getLogger(StepUpServiceImpl.class);

    static final String COUNTER = "security.step_up";

    private final AuthenticationManager authenticationManager;
    private final LockoutService lockoutService;
    private final UserRepository userRepository;
    private final RoleAuthorityService roleAuthorityService;
    private final @Nullable MeterRegistry meterRegistry;

    public StepUpServiceImpl(
            AuthenticationManager authenticationManager,
            LockoutService lockoutService,
            UserRepository userRepository,
            RoleAuthorityService roleAuthorityService,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.authenticationManager = authenticationManager;
        this.lockoutService = lockoutService;
        this.userRepository = userRepository;
        this.roleAuthorityService = roleAuthorityService;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @Override
    @NonNull
    public StepUpResponse verify(@NonNull String username, @NonNull String password, @NonNull String permission) {
        // The calling service's tenant, bound by TenantContextFilter from X-Tenant-Id; an unbound
        // request fails closed rather than looking the person up in no tenant.
        UUID tenantId = TenantContext.require();
        UUID knownUserId =
                userRepository.findByUsername(username).map(User::getId).orElse(null);
        if (knownUserId != null) {
            lockoutService.unlockIfCooldownExpired(knownUserId);
            if (lockoutService.isLockedOut(knownUserId)) {
                throw denied("account_locked", username, tenantId);
            }
        }

        Authentication authentication;
        try {
            authentication =
                    authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        } catch (AuthenticationException ex) {
            if (knownUserId != null && ex instanceof BadCredentialsException) {
                lockoutService.recordFailedAttempt(knownUserId);
            }
            throw denied(reasonOf(ex), username, tenantId);
        }

        if (!(authentication.getPrincipal() instanceof CustomUserDetailsService.SecurityUserPrincipal principal)) {
            throw new IllegalStateException("Unexpected principal type: "
                    + authentication.getPrincipal().getClass().getName());
        }
        // Spring Security 7 factor markers describe how the person authenticated, not a role.
        Set<String> roleNames = authentication.getAuthorities().stream()
                .filter(authority -> !(authority instanceof FactorGrantedAuthority))
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith(RoleAuthorityService.ROLE_PREFIX)
                        ? authority.substring(RoleAuthorityService.ROLE_PREFIX.length())
                        : authority)
                .collect(Collectors.toSet());
        boolean holdsPermission =
                roleAuthorityService.expandRolesToAuthorities(roleNames).contains(permission);
        count(holdsPermission ? "verified" : "lacks_permission");
        log.info(
                "Step-up verified username={} userId={} tenant={} permission={} holdsPermission={}",
                username,
                principal.userId(),
                tenantId,
                permission,
                holdsPermission);
        return new StepUpResponse(principal.userId(), holdsPermission);
    }

    private StepUpDeniedException denied(String reason, String username, UUID tenantId) {
        count(reason);
        log.warn("Step-up denied username={} tenant={} reason={}", username, tenantId, reason);
        return new StepUpDeniedException(reason);
    }

    private static String reasonOf(AuthenticationException ex) {
        if (ex instanceof BadCredentialsException) {
            return "bad_credentials";
        }
        if (ex instanceof LockedException) {
            return "account_locked";
        }
        if (ex instanceof DisabledException) {
            return "account_disabled";
        }
        if (ex instanceof AccountExpiredException) {
            return "account_expired";
        }
        if (ex instanceof CredentialsExpiredException) {
            return "credentials_expired";
        }
        return "authentication_failed";
    }

    private void count(String outcome) {
        if (meterRegistry != null) {
            Counter.builder(COUNTER)
                    .description("Step-up credential checks by outcome")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }
}
