package com.positivity.securityservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.PrincipalRoleRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.RoleManagementService;
import com.positivity.securityservice.internal.service.TokenRevocationManager;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-0061 §1 / #1875: after the scope branch left {@code userHasPermission}, effective dating
 * is the only filter between a persisted role assignment and a granted permission. This runs
 * the real {@code findEffectiveAssignmentsByUser} query against the H2 test schema so the
 * guarantee is proven at the persistence boundary, not only against a mock.
 *
 * <p>The database's own {@code CURRENT_DATE} is the reference point for the JPQL query, so the
 * fixture places windows a year on either side of it rather than at the boundary.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PosSecurityServiceApplication.class)
@ActiveProfiles("test")
@DisplayName("userHasPermission honours effective dating without a scope branch")
class UserHasPermissionEffectiveDatingIT extends BaseIntegrationTest {

    private static final String PERMISSION = "security:effective-dating:probe";

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
    private RoleManagementService roleManagementService;

    private User user;
    private Role role;

    @BeforeEach
    void seed() {
        roleAssignmentRepository.deleteAll();
        principalRoleRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Permission permission = permissionRepository.findByName(PERMISSION).orElseGet(() -> {
            Permission created = new Permission();
            created.setId(UUIDv7Generator.generate());
            created.setName(PERMISSION);
            created.setDescription("Effective-dating probe");
            created.setDomain("security");
            created.setResource("effective-dating");
            created.setAction("probe");
            created.setRegisteredAt(Instant.parse("2024-01-01T00:00:00Z"));
            created.setRegisteredByService("pos-security-service-test");
            return permissionRepository.save(created);
        });

        user = new User();
        user.setId(UUIDv7Generator.generate());
        user.setUsername("effective-dating-" + UUIDv7Generator.generate());
        user.setPassword("password");
        user = userRepository.save(user);

        role = new Role();
        role.setId(UUIDv7Generator.generate());
        role.setName("EFFECTIVE_DATING_" + UUIDv7Generator.generate());
        role.setDescription("Role holding the probe permission");
        role.setCreatedBy("test");
        Set<Permission> grants = new HashSet<>();
        grants.add(permission);
        role.setPermissions(grants);
        role = roleRepository.save(role);
    }

    @Test
    @DisplayName("an assignment inside its window grants the role's permission")
    void currentAssignmentGrants() {
        persistAssignment(LocalDateTime.now().minusYears(1), null);

        assertThat(roleManagementService.userHasPermission(user.getId(), PERMISSION))
                .isTrue();
    }

    @Test
    @DisplayName("an assignment that has ended grants nothing")
    void expiredAssignmentDoesNotGrant() {
        persistAssignment(LocalDateTime.now().minusYears(2), LocalDateTime.now().minusYears(1));

        assertThat(roleManagementService.userHasPermission(user.getId(), PERMISSION))
                .isFalse();
    }

    @Test
    @DisplayName("an assignment that has not started yet grants nothing")
    void futureAssignmentDoesNotGrant() {
        persistAssignment(LocalDateTime.now().plusYears(1), null);

        assertThat(roleManagementService.userHasPermission(user.getId(), PERMISSION))
                .isFalse();
    }

    @Test
    @DisplayName("a revoked assignment stops granting even though its row is kept for history")
    void revokedAssignmentDoesNotGrant() {
        RoleAssignment assignment = persistAssignment(LocalDateTime.now().minusYears(1), null);

        roleManagementService.revokeRoleAssignment(
                assignment.getId(), LocalDateTime.now().minusDays(1));

        assertThat(roleAssignmentRepository.findById(assignment.getId()))
                .as("revocation is an end date, not a delete")
                .isPresent();
        assertThat(roleManagementService.userHasPermission(user.getId(), PERMISSION))
                .isFalse();
    }

    private RoleAssignment persistAssignment(LocalDateTime start, LocalDateTime end) {
        RoleAssignment assignment = new RoleAssignment();
        assignment.setId(UUIDv7Generator.generate());
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setEffectiveStartDate(start);
        assignment.setEffectiveEndDate(end);
        assignment.setCreatedBy("test");
        return roleAssignmentRepository.save(assignment);
    }
}
