package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.domain.LocationScopeBits;
import com.positivity.securityservice.internal.dto.StepUpResponse;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.internal.exception.StepUpDeniedException;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.tenancy.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * the token issuer uses ({@link RoleAuthorityService#expandRolesToAuthorities}), together with that
 * permission's location scope for the person — the dimensions a location-scoped role grants it on and
 * the person's assigned nodes today, composed exactly as the token's {@code loc_*} claims are (ADR-0061
 * §2). The caller decides the person's reach at its location with its own location replica, as it would
 * for a token of theirs; a scoped grant with no assignment answers no nodes, which reaches nowhere.
 *
 * <p>A locked account is refused before the password is checked, after a throw-away password hash so the
 * refusal takes about as long as a wrong password does.
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
    private final StaffingAssignmentProjectionService staffingAssignments;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final @Nullable MeterRegistry meterRegistry;

    @SuppressWarnings("java:S107") // one collaborator per check the sign-in runs
    public StepUpServiceImpl(
            AuthenticationManager authenticationManager,
            LockoutService lockoutService,
            UserRepository userRepository,
            RoleAuthorityService roleAuthorityService,
            StaffingAssignmentProjectionService staffingAssignments,
            PasswordEncoder passwordEncoder,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.authenticationManager = authenticationManager;
        this.lockoutService = lockoutService;
        this.userRepository = userRepository;
        this.roleAuthorityService = roleAuthorityService;
        this.staffingAssignments = staffingAssignments;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @Override
    @NonNull
    public StepUpResponse verify(
            @NonNull String username, @NonNull String password, @NonNull String permission, @Nullable UUID locationId) {
        // The calling service's tenant, bound by TenantContextFilter from X-Tenant-Id; an unbound
        // request fails closed rather than looking the person up in no tenant.
        UUID tenantId = TenantContext.require();
        UUID knownUserId =
                userRepository.findByUsername(username).map(User::getId).orElse(null);
        if (knownUserId != null) {
            lockoutService.unlockIfCooldownExpired(knownUserId);
            if (lockoutService.isLockedOut(knownUserId)) {
                // Spend about what a password check costs, so a locked account is not told apart by time.
                passwordEncoder.encode(password);
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
        StepUpResponse response = holdsPermission
                ? withScope(principal, roleNames, permission)
                : new StepUpResponse(principal.userId(), false, false, false, List.of());
        count(holdsPermission ? "verified" : "lacks_permission");
        log.info(
                "Step-up verified username={} userId={} tenant={} permission={} locationId={} holdsPermission={}"
                        + " financialScoped={} otherScoped={}",
                username,
                principal.userId(),
                tenantId,
                permission,
                locationId,
                holdsPermission,
                response.financialScoped(),
                response.otherScoped());
        return response;
    }

    /** The grant's location scope for the person, composed as the token's {@code loc_*} claims are. */
    private StepUpResponse withScope(
            CustomUserDetailsService.SecurityUserPrincipal principal, Set<String> roleNames, String permission) {
        LocationScopeBits bits = LocationScopeBits.compose(roleAuthorityService.resolveRoleGrants(roleNames));
        PermissionCode code = PermissionCode.fromCode(permission).orElse(null);
        boolean financial = code != null && bits.financial().contains(code);
        boolean other = code != null && bits.other().contains(code);
        List<UUID> nodes = List.of();
        if ((financial || other) && principal.personId() != null) {
            nodes = staffingAssignments.assignedLocationIds(principal.personId(), LocalDate.now(clock));
        }
        return new StepUpResponse(principal.userId(), true, financial, other, List.copyOf(nodes));
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
