package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.domain.RoleGrant;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves a user's effective permissions resolve along the persisted chain
 *
 * <pre>
 *   users -&gt; role_assignments -&gt; roles -&gt; role_permissions -&gt; permissions
 * </pre>
 *
 * all the way into the {@code perm_bits} claim of an issued token, and that a principal with
 * no grants fails closed instead of inheriting a default bundle.
 *
 * <p>This is the behaviour the retired hardcoded role-authority switch used to short-circuit:
 * grants in {@code role_permissions} had no effect on an issued token. Data drives it now, so
 * the chain is worth asserting end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("Effective permission resolution")
class EffectivePermissionResolutionIT {

    @Autowired
    private RoleAuthorityService roleAuthorityService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private EffectiveGrantResolver effectiveGrantResolver;

    @Autowired
    private Clock clock;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("a user inherits its role's granted permissions, and they reach the issued token")
    void userInheritsRolePermissions_throughRoleAssignmentsAndRolePermissions() {
        Permission view = permission("crm:party:view", 27);
        Permission search = permission("crm:party:search", 28);
        Role role = role(Set.of(view, search));
        User user = user(Set.of(role));

        entityManager.clear();

        Set<String> roleNames = effectiveGrantResolver.resolve(user).roleNames();

        assertThat(roleNames).containsExactly(role.getName());

        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(roleNames);
        assertThat(authorities).contains("ROLE_" + role.getName(), "crm:party:view", "crm:party:search");

        // perm_bits is what downstream services actually enforce against, so assert the grant
        // survives the bitset encode/decode round trip rather than stopping at the authority set.
        JwtService.TokenPair pair = jwtService.generateTokenPair(user.getUsername(), user.getId(), null, roleNames);
        assertThat(jwtService.getAuthoritiesFromToken(pair.accessToken()))
                .contains("crm:party:view", "crm:party:search");
    }

    @Test
    @DisplayName("a user whose role has no grants receives only the ROLE_ authority")
    void userWithUngrantedRole_failsClosed() {
        Role role = role(Set.of());
        User user = user(Set.of(role));

        entityManager.clear();

        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of(role.getName()));

        assertThat(authorities).containsExactly("ROLE_" + role.getName());

        JwtService.TokenPair pair =
                jwtService.generateTokenPair(user.getUsername(), user.getId(), null, Set.of(role.getName()));
        assertThat(jwtService.getAuthoritiesFromToken(pair.accessToken())).isEmpty();
    }

    @Test
    @DisplayName("a user with no roles at all receives no authorities")
    void userWithNoRoles_receivesNoAuthorities() {
        User user = user(Set.of());

        entityManager.clear();

        Set<String> roleNames = effectiveGrantResolver.resolve(user).roleNames();

        assertThat(roleNames).isEmpty();
        assertThat(roleAuthorityService.expandRolesToAuthorities(roleNames)).isEmpty();
    }

    @Test
    @DisplayName("a role stored in mixed case still resolves its grants")
    void roleStoredInMixedCase_stillResolvesGrants() {
        Permission view = permission("shop:schedule:view", 157);
        String mixedCase = "It_Role_" + UUID.randomUUID().toString().replace("-", "");
        role(mixedCase, Set.of(view));

        entityManager.clear();

        assertThat(roleAuthorityService.expandRolesToAuthorities(Set.of(mixedCase)))
                .containsExactlyInAnyOrder(
                        "ROLE_" + mixedCase.toUpperCase(java.util.Locale.ROOT), "shop:schedule:view");
    }

    @Test
    @DisplayName("revoking a grant removes the authority without touching the role assignment")
    void revokingGrant_removesAuthorityButKeepsRole() {
        Permission view = permission("workorder:workorder:view", 176);
        Role role = role(Set.of(view));
        user(Set.of(role));

        assertThat(roleAuthorityService.expandRolesToAuthorities(Set.of(role.getName())))
                .contains("workorder:workorder:view");

        Role stored = roleRepository.findById(role.getId()).orElseThrow();
        stored.getPermissions().clear();
        roleRepository.saveAndFlush(stored);
        entityManager.clear();

        assertThat(roleAuthorityService.expandRolesToAuthorities(Set.of(role.getName())))
                .containsExactly("ROLE_" + role.getName());
    }

    // ---------------------------------------------------------------------------------------
    // ADR-0061 §2 (#1868): per-role grants with location reach, end to end against H2
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("resolveRoleGrants returns each role's grants with its own location reach, ordered by name")
    void resolveRoleGrants_returnsPerRoleGrantsWithReach() {
        Permission view = permission("accounting:je:view", 0);
        Permission approve = permission("inventory:adjustment:approve", 57);
        Role financial =
                role(uniqueName("IT_A_FIN"), LocationScope.LOCATION, LocationHierarchy.FINANCIAL, Set.of(view));
        Role operational =
                role(uniqueName("IT_B_OTH"), LocationScope.LOCATION, LocationHierarchy.OTHER, Set.of(approve, view));
        Role ungranted = role(uniqueName("IT_C_ALL"), Set.of());

        entityManager.clear();

        assertThat(roleAuthorityService.resolveRoleGrants(
                        Set.of(financial.getName(), operational.getName(), ungranted.getName())))
                .containsExactly(
                        new RoleGrant(
                                financial.getName(),
                                LocationScope.LOCATION,
                                LocationHierarchy.FINANCIAL,
                                Set.of("accounting:je:view")),
                        new RoleGrant(
                                operational.getName(),
                                LocationScope.LOCATION,
                                LocationHierarchy.OTHER,
                                Set.of("accounting:je:view", "inventory:adjustment:approve")));
    }

    @Test
    @DisplayName("a role saved without setting scope is ALL / OTHER, and its grants stay global in the token")
    void roleDefaults_areAllOther_andGrantsStayGlobal() {
        Permission view = permission("accounting:je:view", 0);
        Role role = role(Set.of(view));
        User user = user(Set.of(role));

        entityManager.clear();

        Role stored = roleRepository.findById(role.getId()).orElseThrow();
        assertThat(stored.getLocationScope()).isEqualTo(LocationScope.ALL);
        assertThat(stored.getLocationHierarchy()).isEqualTo(LocationHierarchy.OTHER);

        JwtService.TokenPair pair =
                jwtService.generateTokenPair(user.getUsername(), user.getId(), null, Set.of(role.getName()));
        assertThat(jwtService.getAuthoritiesFromToken(pair.accessToken())).contains("accounting:je:view");
        assertThat(jwtService.getFinancialLocationScopedPermissionsFromToken(pair.accessToken()))
                .isEmpty();
        assertThat(jwtService.getOtherLocationScopedPermissionsFromToken(pair.accessToken()))
                .isEmpty();
        assertThat(jwtService.getLocationScopeFromToken(pair.accessToken())).isEmpty();
    }

    @Test
    @DisplayName(
            "a LOCATION role's grants reach the scope bitset of its hierarchy; with no person the token fails closed")
    void locationScopedRole_grantsReachScopeBitset_andFailClosedWithoutPerson() {
        Permission view = permission("accounting:je:view", 0);
        Role role = role(uniqueName("IT_SCOPED"), LocationScope.LOCATION, LocationHierarchy.OTHER, Set.of(view));
        User user = user(Set.of(role));

        entityManager.clear();

        JwtService.TokenPair pair =
                jwtService.generateTokenPair(user.getUsername(), user.getId(), null, Set.of(role.getName()));
        assertThat(jwtService.getAuthoritiesFromToken(pair.accessToken())).contains("accounting:je:view");
        assertThat(jwtService.getOtherLocationScopedPermissionsFromToken(pair.accessToken()))
                .containsExactly("accounting:je:view");
        assertThat(jwtService.getFinancialLocationScopedPermissionsFromToken(pair.accessToken()))
                .isEmpty();
        // No personId → no assigned node can be resolved → loc_scope omitted, never ALL.
        assertThat(jwtService.getLocationScopeFromToken(pair.accessToken())).isEmpty();
    }

    private Permission permission(String name, int bitIndex) {
        return permissionRepository.findByName(name).orElseGet(() -> {
            Permission permission = new Permission();
            permission.setName(name);
            permission.setDescription(name);
            permission.setRegisteredByService("pos-security-service");
            permission.setDeprecated(false);
            permission.setBitIndex(bitIndex);
            permission.parsePermissionName();
            return permissionRepository.saveAndFlush(permission);
        });
    }

    private Role role(Set<Permission> permissions) {
        String name = "IT_ROLE_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        return role(name, permissions);
    }

    /** Scope left untouched on purpose: the entity defaults (ALL / OTHER) are what get persisted. */
    private Role role(String name, Set<Permission> permissions) {
        return roleRepository.saveAndFlush(newRole(name, permissions));
    }

    private Role role(String name, LocationScope scope, LocationHierarchy hierarchy, Set<Permission> permissions) {
        Role role = newRole(name, permissions);
        role.setLocationScope(scope);
        role.setLocationHierarchy(hierarchy);
        return roleRepository.saveAndFlush(role);
    }

    private static Role newRole(String name, Set<Permission> permissions) {
        Role role = new Role();
        role.setName(name);
        role.setDescription("Integration-test role");
        role.setCreatedAt(Instant.now());
        role.setCreatedBy("test");
        role.setPermissions(new java.util.HashSet<>(permissions));
        return role;
    }

    private static String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }

    private User user(Set<Role> roles) {
        User user = new User();
        user.setUsername("it-user-" + UUID.randomUUID());
        user.setPassword("{noop}password");
        User saved = userRepository.saveAndFlush(user);
        for (Role role : roles) {
            RoleAssignment assignment = new RoleAssignment();
            assignment.setUser(saved);
            assignment.setRole(role);
            assignment.setEffectiveStartDate(LocalDateTime.now(clock));
            assignment.setCreatedBy("test");
            roleAssignmentRepository.saveAndFlush(assignment);
        }
        return saved;
    }
}
