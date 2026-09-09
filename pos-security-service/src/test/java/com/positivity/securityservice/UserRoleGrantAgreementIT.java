package com.positivity.securityservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentDto;
import com.positivity.securityservice.internal.dto.SelfRegistrationRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationResponse;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.AuthorizationService;
import com.positivity.securityservice.internal.service.AuthorizationService.Decision;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.service.PeopleContactCommandEmitter;
import com.positivity.securityservice.internal.service.RoleManagementService;
import com.positivity.securityservice.internal.service.SelfRegistrationService;
import com.positivity.securityservice.internal.service.TokenRevocationManager;
import com.positivity.securityservice.internal.service.UserService;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * ADR-0061 amendment phase 2 (#1914): {@code role_assignments} is the only store of a user's
 * roles, and every provisioning path — {@link UserService#createUser}, {@link
 * UserService#assignRoles}, and {@link SelfRegistrationService#selfRegister} — writes assignments
 * through {@code UserRoleGrantService} rather than a direct {@code user.roles} set. This proves
 * the grants those paths make are actually visible to the assignment-listing / People access view
 * ({@link RoleManagementService#getEffectiveRoleAssignments}) and to every decision point, and
 * that {@code assignRoles} revoking a role keeps the row (for history) while excluding it from
 * every decision point going forward.
 *
 * <p>Modeled on {@link EffectiveGrantAgreementIT} for the Spring/H2/{@link Clock} fixture
 * pattern.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PosSecurityServiceApplication.class)
@ActiveProfiles("test")
@DisplayName("Every provisioning path writes an effective role assignment (#1914 phase 2)")
class UserRoleGrantAgreementIT extends BaseIntegrationTest {

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
    private UserService userService;

    @Autowired
    private RoleManagementService roleManagementService;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Autowired
    private SelfRegistrationService selfRegistrationService;

    @MockitoBean
    private PeopleContactCommandEmitter peopleContactCommandEmitter;

    /** The application's own clock — UTC ({@code TimeConfig} supplies {@link Clock#systemUTC()}). */
    @Autowired
    private Clock clock;

    @BeforeEach
    void seed() {
        roleAssignmentRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
    }

    @Test
    @DisplayName("UserService.createUser grants effective assignments visible through getEffectiveRoleAssignments, "
            + "the token path, and getUserPermissions")
    void createUser_yieldsEffectiveAssignments_visibleEverywhere() {
        String permissionName = permissionName("create-user");
        Role role = roleWithPermission("IT_CREATE_USER", permissionName);
        String username = "it-create-" + uniqueSuffix();

        var created = userService.createUser(username, "Sup3rS3cret!1", Set.of(role.getName()));

        assertThat(roleManagementService.getEffectiveRoleAssignments(created.getId()))
                .extracting(RoleAssignmentDto::getRoleId)
                .containsExactly(role.getId());

        Set<String> tokenAuthorities = customUserDetailsService.loadUserByUsername(username).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertThat(tokenAuthorities).contains("ROLE_" + role.getName());

        assertThat(permissionNamesOf(created.getId())).contains(permissionName);
    }

    @Test
    @DisplayName("UserService.assignRoles with a smaller set revokes the dropped role: the row is kept with "
            + "effectiveEndDate and revokedAt set, and it no longer grants through getUserPermissions or "
            + "authorizePerson")
    void assignRoles_smallerSet_revokesDroppedRole() {
        String keptPermissionName = permissionName("kept");
        String droppedPermissionName = permissionName("dropped");
        Role keptRole = roleWithPermission("IT_KEPT", keptPermissionName);
        Role droppedRole = roleWithPermission("IT_DROPPED", droppedPermissionName);
        String username = "it-assign-" + uniqueSuffix();
        UUID personId = UUIDv7Generator.generate();

        var created =
                userService.createUser(username, "Sup3rS3cret!1", Set.of(keptRole.getName(), droppedRole.getName()));
        User user = userRepository.findById(created.getId()).orElseThrow();
        user.setPersonId(personId);
        userRepository.saveAndFlush(user);

        assertThat(permissionNamesOf(created.getId())).contains(keptPermissionName, droppedPermissionName);
        assertThat(authorizationService.authorizePerson(personId, droppedPermissionName))
                .isEqualTo(Decision.ALLOW);

        userService.assignRoles(username, Set.of(keptRole.getName()));

        // The row is kept for history, with its window closed and the revocation recorded.
        RoleAssignmentDto droppedAssignment =
                roleManagementService.getAssignmentsForUser(created.getId(), true).stream()
                        .filter(dto -> dto.getRoleId().equals(droppedRole.getId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(droppedAssignment.getEffectiveEndDate()).isNotNull();
        assertThat(droppedAssignment.getRevokedAt()).isNotNull();

        // It no longer grants anything going forward.
        assertThat(roleManagementService.getEffectiveRoleAssignments(created.getId()))
                .extracting(RoleAssignmentDto::getRoleId)
                .containsExactly(keptRole.getId());
        assertThat(permissionNamesOf(created.getId()))
                .contains(keptPermissionName)
                .doesNotContain(droppedPermissionName);
        assertThat(authorizationService.authorizePerson(personId, droppedPermissionName))
                .isEqualTo(Decision.DENY);
        assertThat(authorizationService.authorizePerson(personId, keptPermissionName))
                .isEqualTo(Decision.ALLOW);
    }

    @Test
    @DisplayName("self-registration grants an effective assignment of the default role")
    void selfRegistration_producesEffectiveAssignment() {
        ensureSelfServiceCustomerRoleExists();
        String email = "it-selfreg-" + uniqueSuffix() + "@example.com";

        SelfRegistrationResponse response = selfRegistrationService.selfRegister(SelfRegistrationRequest.builder()
                .email(email)
                .password("Sup3rS3cret!1")
                .firstName("Test")
                .lastName("User")
                .build());

        assertThat(roleManagementService.getEffectiveRoleAssignments(response.userId()))
                .extracting(RoleAssignmentDto::getRoleId)
                .containsExactly(selfServiceCustomerRoleId());
    }

    private Set<String> permissionNamesOf(UUID userId) {
        return roleManagementService.getUserPermissions(userId).stream()
                .map(PermissionDto::getName)
                .collect(Collectors.toSet());
    }

    private void ensureSelfServiceCustomerRoleExists() {
        if (roleRepository.findByName("SELF_SERVICE_CUSTOMER").isPresent()) {
            return;
        }
        Role role = new Role();
        role.setName("SELF_SERVICE_CUSTOMER");
        role.setDescription("Self-registration default role");
        role.setCreatedBy("test");
        roleRepository.save(role);
    }

    private UUID selfServiceCustomerRoleId() {
        return roleRepository.findByName("SELF_SERVICE_CUSTOMER").orElseThrow().getId();
    }

    private String permissionName(String label) {
        return "security:ura-agreement:" + label + "-" + uniqueSuffix();
    }

    private String uniqueSuffix() {
        return UUIDv7Generator.generate().toString().replace("-", "");
    }

    private Role roleWithPermission(String roleNamePrefix, String permissionName) {
        Permission permission = new Permission();
        permission.setId(UUIDv7Generator.generate());
        permission.setName(permissionName);
        permission.setDescription("User-role grant agreement fixture");
        permission.setDomain("security");
        permission.setResource("ura-agreement");
        permission.setAction("probe");
        permission.setRegisteredAt(Instant.now(clock));
        permission.setRegisteredByService("pos-security-service-test");
        permission = permissionRepository.save(permission);

        Role role = new Role();
        role.setId(UUIDv7Generator.generate());
        role.setName(roleNamePrefix + "_" + uniqueSuffix());
        role.setDescription("User-role grant agreement fixture role");
        role.setCreatedBy("test");
        role.setPermissions(Set.of(permission));
        return roleRepository.save(role);
    }
}
