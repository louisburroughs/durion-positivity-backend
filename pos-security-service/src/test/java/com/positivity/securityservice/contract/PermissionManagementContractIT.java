package com.positivity.securityservice.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.BaseContractIntegrationTest;
import com.positivity.securityservice.PosSecurityServiceApplication;
import com.positivity.securityservice.internal.entity.AuditLogEvent;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.PrincipalRole;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.AuditLogEventRepository;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.PrincipalRoleRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.TokenRevocationManager;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Contract behavior tests for the POS roles and permission matrix.
 *
 * <p>Validates:
 * <ul>
 *   <li>AC1 – HTTP 403 on insufficient authority triggers a {@code PermissionDenied} audit event.</li>
 *   <li>AC2 – Principal with the required permission receives an {@code allow} authorization decision.</li>
 *   <li>AC3 – Successful role assignment persists a {@code RoleAssignedToUser} audit event.</li>
 *   <li>AC4 – Successful role revocation persists a {@code RoleRevokedFromUser} audit event.</li>
 *   <li>AC5 – Audit events are queryable by {@code eventType}.</li>
 * </ul>
 *
 * <p>Actor fields are sourced from the security context per ADR-0018; no caller-supplied
 * actor fields are accepted.
 *
 * Issue: #2
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PosSecurityServiceApplication.class,
        properties = {"security.jwt.secret=test-jwt-secret-key-01234567890123456789"})
@ActiveProfiles("test")
@WithMockUser(
        username = "system-admin",
        roles = {"ADMIN"})
@DisplayName("Issue #2: Permission Management Contract Behavior")
class PermissionManagementContractIT extends BaseContractIntegrationTest {
    private static final String ROLE_ASSIGN_AUTH = "ROLE_ADMIN,security:role:assign";
    private static final String AUTHZ_DECISION_AUTH = "ROLE_ADMIN,security:authorization:decide";
    private static final String AUDIT_VIEW_AUTH = "ROLE_ADMIN,security:audit:view";

    @MockitoBean
    private TokenRevocationManager tokenRevocationManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private AuditLogEventRepository auditLogEventRepository;

    @Autowired
    private PrincipalRoleRepository principalRoleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    /** Test-owned role, re-created per test. */
    private Role testRole;

    /** Test-owned user on whom role operations are performed. */
    private User subjectUser;

    /**
     * Cleans all test-owned state in FK-safe order and seeds fresh entities
     * before each test.
     *
     * <p>Cleanup order prevents FK constraint violations:
     * <ol>
     *   <li>{@code role_assignments} → references user + role</li>
     *   <li>{@code principal_roles} → references role</li>
     *   <li>{@code audit_log_events} → standalone</li>
     *   <li>{@code users} → cascade-clears {@code user_roles} join table</li>
     *   <li>{@code roles} → cascade-clears {@code role_permissions} join table</li>
     *   <li>{@code permissions} → now FK-free</li>
     * </ol>
     */
    @BeforeEach
    void seedTestData() {
        roleAssignmentRepository.deleteAll();
        principalRoleRepository.deleteAll();
        auditLogEventRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();

        testRole = buildRole("TestRole-" + System.currentTimeMillis(), "Contract test role – Issue #2");
        subjectUser = buildUser("subject-" + System.currentTimeMillis());
    }

    // ── AC1 ──────────────────────────────────────────────────────────────────

    /**
     * AC1: An authenticated user without the required authority receives HTTP 403
     * AND a {@code PermissionDenied} audit event is persisted in
     * {@code audit_log_events} containing actor_id (from security context per
     * ADR-0018) and timestamp.
     *
     * <p><b>GREEN path:</b> {@code GlobalExceptionHandler.handleAuthorizationDeniedException()}
     * persists a {@code PermissionDenied} audit event on HTTP 403 using
     * actor identity from security context (ADR-0018).
     */
    @Test
    @DisplayName("AC1: cashier denied role-assign operation – PermissionDenied audit event persisted")
    void ac1_cashierDeniedRoleAssign_permissionDeniedAuditEventPersisted() throws Exception {
        // When: CASHIER (no ROLE_ADMIN) attempts to assign a role — endpoint requires ROLE_ADMIN
        mockMvc.perform(withAuth(
                        put("/v1/users/{userId}/roles/{roleId}", subjectUser.getId(), testRole.getId()),
                        "ROLE_CASHIER"))
                .andExpect(status().isForbidden());

        // Then: a PermissionDenied audit event must be persisted
        List<AuditLogEvent> deniedEvents = auditLogEventRepository.findAll().stream()
                .filter(e -> "PermissionDenied".equals(e.getEventType()))
                .toList();

        // Issue #2 GREEN: GlobalExceptionHandler persists PermissionDenied audit event on HTTP 403
        assertThat(deniedEvents)
                .as(
                        "Expected 1 AuditLogEvent with eventType='PermissionDenied' after HTTP 403, but found %d",
                        deniedEvents.size())
                .hasSize(1);

        AuditLogEvent event = deniedEvents.get(0);
        // ADR-0018: actorId must be sourced from security context, not request body
        assertThat(event.getActorId())
                .as("actorId must equal the authenticated principal in the security context (ADR-0018)")
                .isEqualTo(TEST_USER);
        assertThat(event.getTimestamp())
                .as("PermissionDenied event must carry a non-null timestamp")
                .isNotNull();
    }

    // ── AC2 ──────────────────────────────────────────────────────────────────

    /**
     * AC2 (GREEN): A principal whose role carries the {@code order:refund:approve}
     * permission receives an {@code allow} authorization decision.
     *
     * <p>This test exercises the existing {@code AuthorizationServiceImpl} and is
     * expected to pass once the role–permission–principal chain is seeded.
     */
    @Test
    @DisplayName("AC2: principal with APPROVE_REFUND permission via Manager role gets 'allow' authorization decision")
    void ac2_managerWithApproveRefundPermission_authorizationDecisionIsAllow() throws Exception {
        // Given: order:refund:approve permission exists and is linked to testRole
        Permission approveRefund = buildPermission("order:refund:approve", "order", "refund", "approve");
        testRole.getPermissions().add(approveRefund);
        testRole = roleRepository.save(testRole);

        // Assign testRole (acting as Manager) to a string-keyed principal
        String managerPrincipal = "manager-principal-" + System.currentTimeMillis();
        PrincipalRole principalRole = new PrincipalRole();
        principalRole.setPrincipalId(managerPrincipal);
        principalRole.setRole(testRole);
        principalRoleRepository.save(principalRole);

        // When: Admin queries the authorization decision for the manager principal
        // Then: decision must be allow
        mockMvc.perform(withAuth(
                        get("/v1/users/authorization/decision")
                                .param("principalId", managerPrincipal)
                                .param("permission", "order:refund:approve"),
                        AUTHZ_DECISION_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("allow"));
    }

    // ── AC2b ─────────────────────────────────────────────────────────────────

    /**
     * AC2b (GREEN): A principal with NO role assignments receives a {@code deny}
     * authorization decision for {@code order:refund:approve}.
     *
     * <p>This is the deny-path complement to AC2. The principal string used here
     * has never been assigned any role, so the authorization service must return
     * {@code deny} per the default-deny policy.
     *
     * Issue: #2
     */
    @Test
    @DisplayName("AC2b: principal without permission gets 'deny' authorization decision")
    void ac2b_principalWithoutPermission_authorizationDecisionIsDeny() throws Exception {
        // Given: a principal that has no role assignments at all
        String unprivilegedPrincipal = "no-role-principal-" + System.currentTimeMillis();

        // When: Admin queries the authorization decision for the unprivileged principal
        // Then: decision must be deny (default-deny policy)
        mockMvc.perform(withAuth(
                        get("/v1/users/authorization/decision")
                                .param("principalId", unprivilegedPrincipal)
                                .param("permission", "order:refund:approve"),
                        AUTHZ_DECISION_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("deny"));
    }

    // ── AC3 ──────────────────────────────────────────────────────────────────

    /**
     * AC3: A successful role assignment via {@code PUT /v1/users/{userId}/roles/{roleId}}
     * must persist a {@code RoleAssignedToUser} audit event in {@code audit_log_events}
     * recording actor_id (from security context), subject user_id, entity type, and timestamp.
     *
     * <p><b>GREEN path:</b> {@code RoleManagementServiceImpl.assignRoleToUser()} now emits
     * a {@code RoleAssignedToUser} audit event via {@code AuditEventService} after
     * persisting the assignment.
     */
    @Test
    @DisplayName(
            "AC3: role assignment persists RoleAssignedToUser audit event with actor, subject, role, and timestamp")
    void ac3_roleAssignment_persistsRoleAssignedToUserAuditEvent() throws Exception {
        // When: Admin assigns testRole to subjectUser
        mockMvc.perform(withAuth(
                        put("/v1/users/{userId}/roles/{roleId}", subjectUser.getId(), testRole.getId()),
                        ROLE_ASSIGN_AUTH))
                .andExpect(status().isCreated());

        // Then: a RoleAssignedToUser audit event must be persisted
        List<AuditLogEvent> assignedEvents = auditLogEventRepository.findAll().stream()
                .filter(e -> "RoleAssignedToUser".equals(e.getEventType()))
                .toList();

        // Issue #2 GREEN: RoleManagementServiceImpl.assignRoleToUser() emits AuditLogEvent
        assertThat(assignedEvents)
                .as(
                        "Expected 1 AuditLogEvent with eventType='RoleAssignedToUser' after"
                                + " PUT /v1/users/{userId}/roles/{roleId}, but found %d",
                        assignedEvents.size())
                .hasSize(1);

        AuditLogEvent event = assignedEvents.get(0);
        // ADR-0018: actorId must be sourced from security context
        assertThat(event.getActorId())
                .as("actorId must equal the authenticated principal from the security context (ADR-0018)")
                .isEqualTo(TEST_USER);
        assertThat(event.getEntityId())
                .as("entityId must be the subject user's UUID string")
                .isEqualTo(subjectUser.getId().toString());
        assertThat(event.getEntityType())
                .as("entityType must identify the domain object on which the action was performed")
                .isEqualTo("User");
        assertThat(event.getTimestamp())
                .as("RoleAssignedToUser event must carry a non-null timestamp")
                .isNotNull();
        assertThat(event.getNewValue())
                .as("newValue must contain the role name for audit traceability")
                .contains(testRole.getName());
    }

    // ── AC4 ──────────────────────────────────────────────────────────────────

    /**
     * AC4: A successful role revocation via {@code DELETE /v1/users/{userId}/roles/{roleId}}
     * must persist a {@code RoleRevokedFromUser} audit event in {@code audit_log_events}
     * recording actor_id (from security context), subject user_id, and timestamp.
     *
     * <p><b>GREEN path:</b> {@code RoleManagementServiceImpl.revokeRoleFromUser()} now emits
     * a {@code RoleRevokedFromUser} audit event via {@code AuditEventService} after
     * updating assignment end-date metadata.
     */
    @Test
    @DisplayName("AC4: role revocation persists RoleRevokedFromUser audit event with actor, subject, and timestamp")
    void ac4_roleRevocation_persistsRoleRevokedFromUserAuditEvent() throws Exception {
        // Given: subjectUser has testRole assigned
        mockMvc.perform(withAuth(
                        put("/v1/users/{userId}/roles/{roleId}", subjectUser.getId(), testRole.getId()),
                        ROLE_ASSIGN_AUTH))
                .andExpect(status().isCreated());

        // Clear any events from the assignment setup to isolate the revocation assertion
        auditLogEventRepository.deleteAll();

        // When: Admin revokes testRole from subjectUser
        mockMvc.perform(withAuth(
                        delete("/v1/users/{userId}/roles/{roleId}", subjectUser.getId(), testRole.getId()),
                        ROLE_ASSIGN_AUTH))
                .andExpect(status().isNoContent());

        // Then: a RoleRevokedFromUser audit event must be persisted
        List<AuditLogEvent> revokedEvents = auditLogEventRepository.findAll().stream()
                .filter(e -> "RoleRevokedFromUser".equals(e.getEventType()))
                .toList();

        // Issue #2 GREEN: RoleManagementServiceImpl.revokeRoleFromUser() emits AuditLogEvent
        assertThat(revokedEvents)
                .as(
                        "Expected 1 AuditLogEvent with eventType='RoleRevokedFromUser' after"
                                + " DELETE /v1/users/{userId}/roles/{roleId}, but found %d",
                        revokedEvents.size())
                .hasSize(1);

        AuditLogEvent event = revokedEvents.get(0);
        // ADR-0018: actorId must be sourced from security context
        assertThat(event.getActorId())
                .as("actorId must equal the authenticated principal from the security context (ADR-0018)")
                .isEqualTo(TEST_USER);
        assertThat(event.getEntityId())
                .as("entityId must identify the subject user whose role was revoked")
                .isEqualTo(subjectUser.getId().toString());
        assertThat(event.getTimestamp())
                .as("RoleRevokedFromUser event must carry a non-null timestamp")
                .isNotNull();
    }

    // ── AC5 ──────────────────────────────────────────────────────────────────

    /**
     * AC5: A Security Officer must be able to query {@code audit_log_events} by
     * {@code eventType} to retrieve role-assignment history with timestamp,
     * actor identity, subject identity, and role name.
     *
     * <p><b>GREEN path:</b> {@code AuditController.searchEvents()} supports
     * querying by {@code eventType} alone and AC3 persists
     * {@code RoleAssignedToUser}, enabling a non-empty history response.
     */
    @Test
    @DisplayName("AC5: audit events queryable by eventType=RoleAssignedToUser returns role assignment history")
    void ac5_auditEvents_queryableByEventType_returnsRoleAssignmentHistory() throws Exception {
        // Given: a role assignment has occurred
        mockMvc.perform(withAuth(
                        put("/v1/users/{userId}/roles/{roleId}", subjectUser.getId(), testRole.getId()),
                        ROLE_ASSIGN_AUTH))
                .andExpect(status().isCreated());

        // When: Security Officer queries audit events by eventType
        // Issue #2 GREEN: endpoint supports eventType-only query and returns matching history
        mockMvc.perform(withAuth(get("/v1/audit/events").param("eventType", "RoleAssignedToUser"), AUDIT_VIEW_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value("RoleAssignedToUser"))
                .andExpect(jsonPath("$[0].actorId").isNotEmpty())
                .andExpect(jsonPath("$[0].entityId").isNotEmpty())
                .andExpect(jsonPath("$[0].timestamp").isNotEmpty());
    }

    // ── Entity builders ───────────────────────────────────────────────────────

    /**
     * Creates and persists a {@link Role} with a unique name.
     *
     * @param name        unique role name
     * @param description human-readable description
     * @return the persisted {@link Role}
     */
    private Role buildRole(String name, String description) {
        Role role = new Role();
        role.setId(UUIDv7Generator.generate());
        role.setName(name);
        role.setDescription(description);
        role.setCreatedBy("test-issue-2");
        role.setCreatedAt(Instant.now());
        return roleRepository.save(role);
    }

    /**
     * Creates and persists a {@link User} with a unique username.
     *
     * @param username unique username
     * @return the persisted {@link User}
     */
    private User buildUser(String username) {
        User user = new User();
        user.setId(UUIDv7Generator.generate());
        user.setUsername(username);
        user.setPassword("{noop}test-password");
        return userRepository.save(user);
    }

    /**
     * Creates and persists a {@link Permission} following the
     * {@code domain:resource:action} naming convention.
     *
     * @param name     full permission key (e.g. {@code order:refund:approve})
     * @param domain   domain segment (e.g. {@code order})
     * @param resource resource segment (e.g. {@code refund})
     * @param action   action segment (e.g. {@code approve})
     * @return the persisted {@link Permission}
     */
    private Permission buildPermission(String name, String domain, String resource, String action) {
        Permission permission = new Permission();
        permission.setId(UUIDv7Generator.generate());
        permission.setName(name);
        permission.setDomain(domain);
        permission.setResource(resource);
        permission.setAction(action);
        permission.setDescription("Permission for " + name + " – Issue #2 test data");
        permission.setDeprecated(false);
        permission.setRegisteredAt(Instant.now());
        permission.setRegisteredByService("test-issue-2");
        return permissionRepository.save(permission);
    }
}
