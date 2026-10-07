package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.StepUpRequest;
import com.positivity.securityservice.internal.dto.StepUpResponse;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.StepUpDeniedException;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * CAP:550 S16 (#2512, AW31): the step-up credential check runs the sign-in's checks in the bound
 * tenant, counts a wrong password under the lockout policy, answers one refusal for every reason, and
 * issues no token.
 */
@DisplayName("StepUpServiceImpl — step-up credential check (AW31)")
class StepUpServiceImplTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-0000000000aa");
    private static final UUID MANAGER_ID = UUID.fromString("01900000-0000-7000-8000-0000000000b1");
    private static final String PERMISSION = "order:session:approve_cash_movement";

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final LockoutService lockoutService = mock(LockoutService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleAuthorityService roleAuthorityService = mock(RoleAuthorityService.class);
    private final StaffingAssignmentProjectionService staffingAssignments =
            mock(StaffingAssignmentProjectionService.class);
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder =
            mock(org.springframework.security.crypto.password.PasswordEncoder.class);
    private static final UUID PERSON_ID = UUID.fromString("01900000-0000-7000-8000-0000000000c1");
    private static final UUID LOCATION = UUID.fromString("01900000-0000-7000-8000-0000000000d1");
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private StepUpServiceImpl service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> registry = mock(ObjectProvider.class);
        when(registry.getIfAvailable()).thenReturn(meterRegistry);
        service = new StepUpServiceImpl(
                authenticationManager,
                lockoutService,
                userRepository,
                roleAuthorityService,
                staffingAssignments,
                passwordEncoder,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-07T12:00:00Z"), java.time.ZoneOffset.UTC),
                registry);
        TenantContext.bind(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void knownUser() {
        User user = new User();
        user.setId(MANAGER_ID);
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(user));
    }

    private void authenticates(String... roles) {
        List<GrantedAuthority> authorities = new java.util.ArrayList<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .build());
        var principal = new CustomUserDetailsService.SecurityUserPrincipal(
                MANAGER_ID,
                PERSON_ID,
                new org.springframework.security.core.userdetails.User("manager", "n/a", authorities));
        when(authenticationManager.authenticate(any()))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
    }

    @Test
    @DisplayName("valid credentials of a holder: the user id and holdsPermission=true, no sign-in recorded")
    void validHolder() {
        knownUser();
        authenticates("GENERAL_MANAGER");
        when(roleAuthorityService.expandRolesToAuthorities(Set.of("GENERAL_MANAGER")))
                .thenReturn(Set.of("ROLE_GENERAL_MANAGER", PERMISSION));

        StepUpResponse response = service.verify("manager", "s3cret", PERMISSION, LOCATION);

        assertThat(response.userId()).isEqualTo(MANAGER_ID);
        assertThat(response.holdsPermission()).isTrue();
        // A step-up is not a sign-in: no success bookkeeping, no token.
        verify(lockoutService, never()).recordSuccessfulLogin(any());
        assertThat(meterRegistry
                        .get(StepUpServiceImpl.COUNTER)
                        .tag("outcome", "verified")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("valid credentials without the permission: holdsPermission=false (the caller denies)")
    void validNonHolder() {
        knownUser();
        authenticates("CASHIER");
        when(roleAuthorityService.expandRolesToAuthorities(Set.of("CASHIER")))
                .thenReturn(Set.of("ROLE_CASHIER", "order:session:cash_movement"));

        StepUpResponse response = service.verify("manager", "s3cret", PERMISSION, LOCATION);

        assertThat(response.holdsPermission()).isFalse();
    }

    @Test
    @DisplayName("a wrong password is denied and counts as a failed attempt under the lockout policy")
    void wrongPasswordCountsUnderLockout() {
        knownUser();
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> service.verify("manager", "wrong", PERMISSION, LOCATION))
                .isInstanceOf(StepUpDeniedException.class)
                .hasMessage("Step-up denied");

        verify(lockoutService).recordFailedAttempt(MANAGER_ID);
    }

    @Test
    @DisplayName("an unknown user is denied with the same exception and no lockout bookkeeping")
    void unknownUserDenied() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> service.verify("ghost", "x", PERMISSION, null))
                .isInstanceOf(StepUpDeniedException.class)
                .hasMessage("Step-up denied");

        verify(lockoutService, never()).recordFailedAttempt(any());
    }

    @Test
    @DisplayName("a locked account is denied before the password is checked")
    void lockedDeniedBeforeAuthentication() {
        knownUser();
        when(lockoutService.isLockedOut(MANAGER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.verify("manager", "s3cret", PERMISSION, LOCATION))
                .isInstanceOf(StepUpDeniedException.class);

        verifyNoInteractions(authenticationManager);
        // A throw-away hash keeps the locked refusal about as slow as a wrong password.
        verify(passwordEncoder).encode("s3cret");
    }

    @Test
    @DisplayName("an inactive (disabled) or administratively locked account is denied with the same exception")
    void disabledOrLockedByProviderDenied() {
        knownUser();
        when(authenticationManager.authenticate(any()))
                .thenThrow(new DisabledException("disabled"))
                .thenThrow(new LockedException("locked"));

        assertThatThrownBy(() -> service.verify("manager", "s3cret", PERMISSION, LOCATION))
                .isInstanceOf(StepUpDeniedException.class)
                .hasMessage("Step-up denied");
        assertThatThrownBy(() -> service.verify("manager", "s3cret", PERMISSION, LOCATION))
                .isInstanceOf(StepUpDeniedException.class)
                .hasMessage("Step-up denied");
        verify(lockoutService, never()).recordFailedAttempt(any());
    }

    @Test
    @DisplayName("an unbound request fails closed: no tenant is ever taken from the body")
    void unboundTenantFailsClosed() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.verify("manager", "s3cret", PERMISSION, LOCATION))
                .isNotInstanceOf(StepUpDeniedException.class);
        verifyNoInteractions(authenticationManager);
    }

    @Test
    @DisplayName("M3: a holder through a LOCATION role answers its dimension and the person's assigned nodes")
    void scopedHolderAnswersScope() {
        knownUser();
        authenticates("LOCATION_MANAGER");
        when(roleAuthorityService.expandRolesToAuthorities(Set.of("LOCATION_MANAGER")))
                .thenReturn(Set.of("ROLE_LOCATION_MANAGER", PERMISSION));
        when(roleAuthorityService.resolveRoleGrants(Set.of("LOCATION_MANAGER")))
                .thenReturn(List.of(new com.positivity.securityservice.internal.domain.RoleGrant(
                        "LOCATION_MANAGER",
                        com.positivity.securityservice.internal.enums.LocationScope.LOCATION,
                        com.positivity.securityservice.internal.enums.LocationHierarchy.OTHER,
                        Set.of(PERMISSION))));
        when(staffingAssignments.assignedLocationIds(PERSON_ID, java.time.LocalDate.of(2026, 10, 7)))
                .thenReturn(List.of(LOCATION));

        StepUpResponse response = service.verify("manager", "s3cret", PERMISSION, LOCATION);

        assertThat(response.holdsPermission()).isTrue();
        assertThat(response.otherScoped()).isTrue();
        assertThat(response.financialScoped()).isFalse();
        assertThat(response.assignedLocationIds()).containsExactly(LOCATION);
    }

    @Test
    @DisplayName("M3: a holder through an ALL role is global — no dimension, no nodes looked up")
    void globalHolderAnswersNoScope() {
        knownUser();
        authenticates("GENERAL_MANAGER");
        when(roleAuthorityService.expandRolesToAuthorities(Set.of("GENERAL_MANAGER")))
                .thenReturn(Set.of(PERMISSION));
        when(roleAuthorityService.resolveRoleGrants(Set.of("GENERAL_MANAGER")))
                .thenReturn(List.of(new com.positivity.securityservice.internal.domain.RoleGrant(
                        "GENERAL_MANAGER",
                        com.positivity.securityservice.internal.enums.LocationScope.ALL,
                        com.positivity.securityservice.internal.enums.LocationHierarchy.OTHER,
                        Set.of(PERMISSION))));

        StepUpResponse response = service.verify("manager", "s3cret", PERMISSION, LOCATION);

        assertThat(response.financialScoped()).isFalse();
        assertThat(response.otherScoped()).isFalse();
        assertThat(response.assignedLocationIds()).isEmpty();
        verifyNoInteractions(staffingAssignments);
    }

    @Test
    @DisplayName("the request's string form never carries the password")
    void requestToStringHidesPassword() {
        assertThat(new StepUpRequest("manager", "s3cret-value", PERMISSION, LOCATION).toString())
                .doesNotContain("s3cret-value")
                .contains("manager");
    }
}
