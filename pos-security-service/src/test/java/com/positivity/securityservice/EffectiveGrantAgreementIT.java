package com.positivity.securityservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.PrincipalRoleRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.AuthorizationService;
import com.positivity.securityservice.internal.service.AuthorizationService.Decision;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.service.RoleAuthorityService;
import com.positivity.securityservice.internal.service.RoleManagementService;
import com.positivity.securityservice.internal.service.TokenRevocationManager;
import com.positivity.securityservice.internal.service.UserService;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-0061 amendment (2026-09-09, #1914): before {@code EffectiveGrantResolver} existed, four
 * call sites each read a different slice of a user's grants — some unioned {@code user_roles}
 * with effective-dated {@code role_assignments}, one read {@code user_roles} only, two read
 * {@code role_assignments} only — so the same permission could pass one decision point and fail
 * another. Phase 2 (#1914) then dropped {@code user_roles} entirely, making {@code
 * role_assignments} the only store. This builds a fixture matrix covering no grants at all,
 * effective-dated assignments, and their dating edge cases, then asserts the permission set every
 * decision point answers is identical for every fixture.
 *
 * <p>Modeled on {@link UserHasPermissionEffectiveDatingIT} for the Spring/H2/{@link Clock}
 * fixture pattern, and on {@code CustomUserDetailsServiceIT} for exercising the real
 * authentication-adjacent bean graph. Every timestamp is built from the autowired {@link Clock}
 * (PR #1915): it is UTC (see {@code TimeConfig}), and {@code LocalDateTime.now()} without it would
 * skew a boundary fixture by the runner's local offset.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PosSecurityServiceApplication.class)
@ActiveProfiles("test")
@DisplayName("Every decision point resolves the same effective grant set (#1914)")
class EffectiveGrantAgreementIT extends BaseIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public TokenRevocationManager tokenRevocationManager() {
            TokenRevocationManager revocationManager = mock(TokenRevocationManager.class);
            when(revocationManager.revokeToken(anyString(), anyLong())).thenReturn(true);
            when(revocationManager.isRevoked(anyString())).thenReturn(false);
            when(revocationManager.clearAllRevoked()).thenReturn(0L);
            return revocationManager;
        }
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private PrincipalRoleRepository principalRoleRepository;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private RoleManagementService roleManagementService;

    @Autowired
    private RoleAuthorityService roleAuthorityService;

    /**
     * The application's own clock — UTC ({@code TimeConfig} supplies {@link Clock#systemUTC()}).
     * See {@link UserHasPermissionEffectiveDatingIT} for why fixtures must be built from it rather
     * than the system default zone.
     */
    @Autowired
    private Clock clock;

    @BeforeEach
    void seed() {
        roleAssignmentRepository.deleteAll();
        principalRoleRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
    }

    /** One fixture user, the permission(s) it was set up to hold, and which of those are effective now. */
    private record Fixture(
            String label, User user, UUID personId, Set<String> declaredPermissions, Set<String> expectedGranted) {}

    @Test
    @DisplayName("loadUserByUsername, getUserByUsername, authorizePerson, userHasPermission and getUserPermissions"
            + " all agree, with no grants at all, effective-dated assignments, and their dating edge cases")
    void allDecisionPointsAgreeOnTheEffectivePermissionSet() {
        List<Fixture> fixtures = List.of(
                noGrantsAtAll(),
                openEndedAssignment(),
                futureDatedAssignment(),
                expiredAssignment(),
                revokedEarlierToday(),
                boundedAssignmentInsideWindow(),
                mixOfTwoEffectiveAssignments());

        Set<String> universe =
                fixtures.stream().flatMap(f -> f.declaredPermissions().stream()).collect(Collectors.toSet());

        for (Fixture fixture : fixtures) {
            Set<String> loadUserByUsernamePermissions = permissionsFromLoadUserByUsername(fixture.user());
            Set<String> getUserByUsernamePermissions = permissionsFromGetUserByUsername(fixture.user());
            Set<String> authorizePersonAllowed = allowedPermissions(fixture.personId(), universe);
            Set<String> userHasPermissionTrue =
                    trueUserHasPermissions(fixture.user().getId(), universe);
            Set<String> getUserPermissionsNames =
                    roleManagementService.getUserPermissions(fixture.user().getId()).stream()
                            .map(PermissionDto::getName)
                            .collect(Collectors.toSet());

            assertThat(loadUserByUsernamePermissions)
                    .as("%s: loadUserByUsername vs expected", fixture.label())
                    .isEqualTo(fixture.expectedGranted());
            assertThat(getUserByUsernamePermissions)
                    .as("%s: getUserByUsername vs expected", fixture.label())
                    .isEqualTo(fixture.expectedGranted());
            assertThat(authorizePersonAllowed)
                    .as("%s: authorizePerson ALLOW set vs expected", fixture.label())
                    .isEqualTo(fixture.expectedGranted());
            assertThat(userHasPermissionTrue)
                    .as("%s: userHasPermission true set vs expected", fixture.label())
                    .isEqualTo(fixture.expectedGranted());
            assertThat(getUserPermissionsNames)
                    .as("%s: getUserPermissions vs expected", fixture.label())
                    .isEqualTo(fixture.expectedGranted());
        }
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    /**
     * (a) No grants at all: no {@code role_assignments} row for this user, and no other store to
     * fall back to since {@code user_roles} was dropped (ADR-0061 amendment phase 2, #1914).
     * Still exercised against the shared {@code universe} built from every other fixture's
     * declared permissions, so this proves fail-closed rather than merely "an empty set is
     * trivially equal to itself".
     */
    private Fixture noGrantsAtAll() {
        User user = userRepository.saveAndFlush(newUser("eg-a-no-grants"));
        return new Fixture("(a) no grants at all", user, user.getPersonId(), Set.of(), Set.of());
    }

    /** (b) An effective open-ended role_assignments row, no user_roles grant. */
    private Fixture openEndedAssignment() {
        String permissionName = permissionName("b-open-ended-assignment");
        Role role = roleWithPermission("B_OPEN_ENDED", permissionName);
        User user = userRepository.saveAndFlush(newUser("eg-b-open-ended"));
        persistAssignment(user, role, LocalDateTime.now(clock).minusYears(1), null);
        return new Fixture(
                "(b) effective open-ended assignment",
                user,
                user.getPersonId(),
                Set.of(permissionName),
                Set.of(permissionName));
    }

    /** (c) A future-dated assignment — not yet effective, so it must grant nothing. */
    private Fixture futureDatedAssignment() {
        String permissionName = permissionName("c-future-dated-assignment");
        Role role = roleWithPermission("C_FUTURE_DATED", permissionName);
        User user = userRepository.saveAndFlush(newUser("eg-c-future-dated"));
        persistAssignment(user, role, LocalDateTime.now(clock).plusYears(1), null);
        return new Fixture(
                "(c) future-dated assignment (negative fixture)",
                user,
                user.getPersonId(),
                Set.of(permissionName),
                Set.of());
    }

    /** (d) An assignment whose window has already ended — expired, so it must grant nothing. */
    private Fixture expiredAssignment() {
        String permissionName = permissionName("d-expired-assignment");
        Role role = roleWithPermission("D_EXPIRED", permissionName);
        User user = userRepository.saveAndFlush(newUser("eg-d-expired"));
        persistAssignment(
                user,
                role,
                LocalDateTime.now(clock).minusYears(2),
                LocalDateTime.now(clock).minusYears(1));
        return new Fixture(
                "(d) expired assignment (negative fixture)",
                user,
                user.getPersonId(),
                Set.of(permissionName),
                Set.of());
    }

    /** (e) An assignment revoked earlier today — the revocation must take effect immediately, not at midnight. */
    private Fixture revokedEarlierToday() {
        String permissionName = permissionName("e-revoked-earlier-today");
        Role role = roleWithPermission("E_REVOKED_TODAY", permissionName);
        User user = userRepository.saveAndFlush(newUser("eg-e-revoked-today"));
        RoleAssignment assignment =
                persistAssignment(user, role, LocalDateTime.now(clock).minusYears(1), null);
        roleManagementService.revokeRoleAssignment(
                assignment.getId(), LocalDateTime.now(clock).minusMinutes(1));
        return new Fixture(
                "(e) assignment revoked earlier today (negative fixture)",
                user,
                user.getPersonId(),
                Set.of(permissionName),
                Set.of());
    }

    /** (f) A bounded assignment whose window is still open — must still grant. */
    private Fixture boundedAssignmentInsideWindow() {
        String permissionName = permissionName("f-bounded-assignment-in-window");
        Role role = roleWithPermission("F_BOUNDED_IN_WINDOW", permissionName);
        User user = userRepository.saveAndFlush(newUser("eg-f-bounded-in-window"));
        persistAssignment(
                user,
                role,
                LocalDateTime.now(clock).minusDays(1),
                LocalDateTime.now(clock).plusYears(1));
        return new Fixture(
                "(f) bounded assignment still inside its window",
                user,
                user.getPersonId(),
                Set.of(permissionName),
                Set.of(permissionName));
    }

    /**
     * (g) A mix of two roles, each granted through its own open-ended effective assignment —
     * proves the resolved set aggregates across multiple {@code role_assignments} rows rather
     * than only ever reading one.
     */
    private Fixture mixOfTwoEffectiveAssignments() {
        String firstPermissionName = permissionName("g-mix-first");
        String secondPermissionName = permissionName("g-mix-second");
        Role firstRole = roleWithPermission("G_MIX_FIRST", firstPermissionName);
        Role secondRole = roleWithPermission("G_MIX_SECOND", secondPermissionName);

        User user = userRepository.saveAndFlush(newUser("eg-g-mix"));
        persistAssignment(user, firstRole, LocalDateTime.now(clock).minusYears(1), null);
        persistAssignment(user, secondRole, LocalDateTime.now(clock).minusYears(1), null);

        return new Fixture(
                "(g) mix of two effective assignments",
                user,
                user.getPersonId(),
                Set.of(firstPermissionName, secondPermissionName),
                Set.of(firstPermissionName, secondPermissionName));
    }

    // ── Decision-point adapters ───────────────────────────────────────────────

    /**
     * The authorities {@code CustomUserDetailsService} produces are role-based
     * ({@code ROLE_<name>}), the same set token issuance expands into {@code perm_bits}
     * (see {@code AuthenticationServiceImpl.login}): strip the {@code ROLE_} prefix, expand
     * through {@link RoleAuthorityService#expandRolesToAuthorities}, then keep only the
     * permission-name entries.
     */
    private Set<String> permissionsFromLoadUserByUsername(User user) {
        Set<String> roleNames =
                customUserDetailsService.loadUserByUsername(user.getUsername()).getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
                        .collect(Collectors.toSet());
        return permissionNamesOnly(roleAuthorityService.expandRolesToAuthorities(roleNames));
    }

    /** Same expansion as above, starting from {@code UserService.getUserByUsername(...).roles}. */
    private Set<String> permissionsFromGetUserByUsername(User user) {
        Set<String> roleNames =
                userService.getUserByUsername(user.getUsername()).orElseThrow().getRoles();
        return permissionNamesOnly(roleAuthorityService.expandRolesToAuthorities(roleNames));
    }

    private Set<String> permissionNamesOnly(Set<String> authorities) {
        return authorities.stream()
                .filter(authority -> !authority.startsWith(RoleAuthorityService.ROLE_PREFIX))
                .collect(Collectors.toSet());
    }

    private Set<String> allowedPermissions(UUID personId, Set<String> universe) {
        return universe.stream()
                .filter(permission -> authorizationService.authorizePerson(personId, permission) == Decision.ALLOW)
                .collect(Collectors.toSet());
    }

    private Set<String> trueUserHasPermissions(UUID userId, Set<String> universe) {
        return universe.stream()
                .filter(permission -> roleManagementService.userHasPermission(userId, permission))
                .collect(Collectors.toSet());
    }

    // ── Shared fixture plumbing ───────────────────────────────────────────────

    private String permissionName(String label) {
        return "security:eg-agreement:" + label + "-" + uniqueSuffix();
    }

    private String uniqueSuffix() {
        return UUIDv7Generator.generate().toString().replace("-", "");
    }

    private Role roleWithPermission(String roleNamePrefix, String permissionName) {
        Permission permission = new Permission();
        permission.setId(UUIDv7Generator.generate());
        permission.setName(permissionName);
        permission.setDescription("Effective-grant agreement fixture");
        permission.setDomain("security");
        permission.setResource("eg-agreement");
        permission.setAction("probe");
        permission.setRegisteredAt(Instant.now(clock));
        permission.setRegisteredByService("pos-security-service-test");
        permission = permissionRepository.save(permission);

        Role role = new Role();
        role.setId(UUIDv7Generator.generate());
        role.setName(roleNamePrefix + "_" + uniqueSuffix());
        role.setDescription("Effective-grant agreement fixture role");
        role.setCreatedBy("test");
        Set<Permission> permissions = new HashSet<>();
        permissions.add(permission);
        role.setPermissions(permissions);
        return roleRepository.save(role);
    }

    private User newUser(String usernamePrefix) {
        User user = new User();
        user.setId(UUIDv7Generator.generate());
        user.setUsername(usernamePrefix + "-" + UUIDv7Generator.generate());
        user.setPassword("password");
        user.setPersonId(UUIDv7Generator.generate());
        return user;
    }

    private RoleAssignment persistAssignment(User user, Role role, LocalDateTime start, LocalDateTime end) {
        RoleAssignment assignment = new RoleAssignment();
        assignment.setId(UUIDv7Generator.generate());
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setEffectiveStartDate(start);
        assignment.setEffectiveEndDate(end);
        assignment.setCreatedBy("test");
        return roleAssignmentRepository.saveAndFlush(assignment);
    }
}
