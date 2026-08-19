package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.JwtService;
import com.positivity.securityservice.service.RoleAuthorityService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves a user's effective permissions resolve along the persisted chain
 *
 * <pre>
 *   users -&gt; user_roles -&gt; roles -&gt; role_permissions -&gt; permissions
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
    private EntityManager entityManager;

    @Test
    @DisplayName("a user inherits its role's granted permissions, and they reach the issued token")
    void userInheritsRolePermissions_throughUserRolesAndRolePermissions() {
        Permission view = permission("crm:party:view", 27);
        Permission search = permission("crm:party:search", 28);
        Role role = role(Set.of(view, search));
        User user = user(Set.of(role));

        entityManager.clear();

        Set<String> roleNames = userRepository.findByUsername(user.getUsername()).orElseThrow().getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

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

        Set<String> roleNames = userRepository.findByUsername(user.getUsername()).orElseThrow().getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

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

    private Role role(String name, Set<Permission> permissions) {
        Role role = new Role();
        role.setName(name);
        role.setDescription("Integration-test role");
        role.setCreatedAt(Instant.now());
        role.setCreatedBy("test");
        role.setPermissions(new java.util.HashSet<>(permissions));
        return roleRepository.saveAndFlush(role);
    }

    private User user(Set<Role> roles) {
        User user = new User();
        user.setUsername("it-user-" + UUID.randomUUID());
        user.setPassword("{noop}password");
        user.setRoles(new java.util.HashSet<>(roles));
        return userRepository.saveAndFlush(user);
    }
}
