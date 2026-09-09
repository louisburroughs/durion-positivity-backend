package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.config.AuditEventService;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleCreateRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.dto.RolePersonasResponse;
import com.positivity.securityservice.internal.dto.RoleUpdateRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.event.RoleAssignmentRevokedEvent;
import com.positivity.securityservice.internal.exception.DuplicateRoleNameException;
import com.positivity.securityservice.internal.exception.PermissionNotFoundException;
import com.positivity.securityservice.internal.exception.RoleAssignmentNotFoundException;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing roles, effective-dated role assignments, and role-permission mappings.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleManagementServiceImpl implements RoleManagementService {
    private final Clock clock;

    private static final String ROLE_NOT_FOUND_PREFIX = "Role not found: ";
    private static final String USER_NOT_FOUND_PREFIX = "User not found: ";

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final UserRepository userRepository;
    private final AuditEventService auditEventService;
    private final RolePersonaEventEmitter rolePersonaEventEmitter;
    private final EffectiveGrantResolver effectiveGrantResolver;
    private final UserRoleGrantService userRoleGrantService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Create a new role, including its optional MCP persona metadata (#1613).
     */
    @Override
    @Transactional
    public RoleDto createRole(@NonNull RoleCreateRequest request) {
        if (roleRepository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateRoleNameException("Role with name " + request.name() + " already exists");
        }

        Role role = new Role();
        role.setName(request.name());
        role.setDescription(request.description());
        role.setPersonaTitle(request.personaTitle());
        role.setPersonaFocus(request.personaFocus());
        role.setPersonaTone(request.personaTone());
        role.setMcpPersonaRank(request.mcpPersonaRank());
        role.setMcpPersonaEligible(request.personaEligibleOrDefault());
        role.setCreatedBy(getCurrentUsername());
        role.setCreatedAt(Instant.now(clock));

        Role saved = roleRepository.save(role);
        rolePersonaEventEmitter.rolePersonaChanged(saved);
        return toRoleDto(saved);
    }

    /**
     * Replace an existing role's description and MCP persona metadata (#1613).
     *
     * <p>Every field is assigned unconditionally: an omitted field clears the stored value, which is
     * what returns a persona slot to its derived default. Treating null as "leave alone" would make
     * that default unreachable through the API.
     */
    @Override
    @Transactional
    public RoleDto updateRole(@NonNull UUID id, @NonNull RoleUpdateRequest request) {
        Role role =
                roleRepository.findById(id).orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + id));

        role.setDescription(request.description());
        role.setPersonaTitle(request.personaTitle());
        role.setPersonaFocus(request.personaFocus());
        role.setPersonaTone(request.personaTone());
        role.setMcpPersonaRank(request.mcpPersonaRank());
        role.setMcpPersonaEligible(request.personaEligibleOrDefault());
        role.setLastModifiedBy(getCurrentUsername());
        role.setLastModifiedAt(Instant.now(clock));

        Role saved = roleRepository.save(role);
        rolePersonaEventEmitter.rolePersonaChanged(saved);
        return toRoleDto(saved);
    }

    /**
     * MCP persona metadata for every role (#1613).
     */
    @Override
    @Transactional(readOnly = true)
    public RolePersonasResponse getRolePersonas() {
        return new RolePersonasResponse(Instant.now(clock), roleRepository.findAllPersonas());
    }

    /**
     * Update permissions for a role.
     */
    @Override
    @Transactional
    public RoleDto updateRolePermissions(RolePermissionsRequest request) {
        Role role = roleRepository
                .findById(request.getRoleId())
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + request.getRoleId()));

        Set<Permission> permissions = new HashSet<>();
        for (String permissionName : request.getPermissionNames()) {
            Permission permission = permissionRepository
                    .findByName(permissionName)
                    .orElseThrow(() -> new PermissionNotFoundException(
                            "Permission not found: " + permissionName + ". It must be registered first."));
            permissions.add(permission);
        }

        // #1512: only the permissions this call adds get stamped. Ones the role already held
        // keep their original grantor — re-submitting a list is not re-granting what was in it.
        Set<UUID> alreadyHeld =
                role.getPermissions().stream().map(Permission::getId).collect(Collectors.toSet());
        Set<UUID> newlyGranted = permissions.stream()
                .map(Permission::getId)
                .filter(id -> !alreadyHeld.contains(id))
                .collect(Collectors.toSet());

        // Mutate the managed collection rather than replacing it. Assigning a new Set
        // dereferences the persistent collection, and Hibernate answers that by deleting every
        // join row and re-inserting the survivors — which silently resets granted_at/granted_by
        // on permissions the role already held. Mutating in place issues targeted inserts and
        // deletes, so untouched rows keep their provenance.
        role.getPermissions().retainAll(permissions);
        role.getPermissions().addAll(permissions);
        role.setLastModifiedBy(getCurrentUsername());
        role.setLastModifiedAt(Instant.now(clock));

        log.info("Updated permissions for role {}: {} permissions assigned", role.getName(), permissions.size());

        RoleDto dto = toRoleDto(roleRepository.save(role));
        if (!newlyGranted.isEmpty()) {
            roleRepository.recordGrantProvenance(role.getId(), newlyGranted, getCurrentUsername(), Instant.now(clock));
        }
        return dto;
    }

    /**
     * Create a role assignment for a user.
     */
    @Override
    @Transactional
    public RoleAssignmentDto createRoleAssignment(RoleAssignmentRequest request) {
        User user = userRepository
                .findById(request.getUserId())
                .orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND_PREFIX + request.getUserId()));

        Role role = roleRepository
                .findById(request.getRoleId())
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + request.getRoleId()));

        LocalDateTime requestStart =
                request.getEffectiveStartDate() != null ? request.getEffectiveStartDate() : LocalDateTime.now(clock);
        LocalDateTime requestEnd = request.getEffectiveEndDate();

        validateNoOverlappingAssignment(user.getId(), role.getId(), requestStart, requestEnd);

        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setEffectiveStartDate(requestStart);
        assignment.setEffectiveEndDate(request.getEffectiveEndDate());
        assignment.setCreatedBy(getCurrentUsername());
        assignment.setCreatedAt(Instant.now(clock));

        log.info(
                "Created role assignment: user={}, role={}, effectiveStart={}, effectiveEnd={}",
                user.getUsername(),
                role.getName(),
                assignment.getEffectiveStartDate(),
                assignment.getEffectiveEndDate());

        return toRoleAssignmentDto(roleAssignmentRepository.save(assignment));
    }

    private void validateNoOverlappingAssignment(
            UUID userId, UUID roleId, LocalDateTime requestStart, LocalDateTime requestEnd) {

        List<RoleAssignment> existingAssignments = roleAssignmentRepository.findByUser_IdAndRole_Id(userId, roleId);

        for (RoleAssignment existing : existingAssignments) {
            if (hasDateOverlap(existing, requestStart, requestEnd)) {
                throw new IllegalStateException("Overlapping role assignment exists for same user and role");
            }
        }
    }

    /**
     * Whether two effective windows overlap, on the half-open convention
     * {@code RoleAssignment.isEffectiveAt} defines: start-inclusive, end-exclusive.
     *
     * <p>So windows that merely touch do not overlap — an assignment ending at T and another
     * starting at T is a clean handover, which the previous end-inclusive comparison rejected as
     * a conflict.
     */
    private boolean hasDateOverlap(RoleAssignment existing, LocalDateTime requestStart, LocalDateTime requestEnd) {
        LocalDateTime existingStart = existing.getEffectiveStartDate();
        LocalDateTime existingEnd = existing.getEffectiveEndDate();

        return (existingEnd == null || requestStart.isBefore(existingEnd))
                && (requestEnd == null || existingStart.isBefore(requestEnd));
    }

    /**
     * Get role assignments for a user.
     */
    @Override
    public List<RoleAssignmentDto> getAssignmentsForUser(@NonNull UUID userId, boolean includeHistory) {
        return getAssignmentEntitiesForUser(userId, includeHistory).stream()
                .map(this::toRoleAssignmentDto)
                .toList();
    }

    /**
     * Get effective role assignments for a user.
     */
    @Override
    public List<RoleAssignmentDto> getEffectiveRoleAssignments(@NonNull UUID userId) {
        return getAssignmentsForUser(userId, false);
    }

    /**
     * Get all permissions for a user's effective grants — the roles of their currently effective
     * {@code role_assignments}, resolved through {@link EffectiveGrantResolver} (ADR-0061
     * amendment, 2026-09-09, #1914).
     *
     * <p>Read-only transaction: {@code Role.permissions} is lazy, and this read must not depend on
     * an open-in-view session being present.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<PermissionDto> getUserPermissions(UUID userId) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND_PREFIX + userId));
        return effectiveGrantResolver.resolve(user).roles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(this::toPermissionDto)
                .collect(Collectors.toSet());
    }

    /**
     * Check whether a user holds a permission through their effective grants — the roles of
     * their currently effective {@code role_assignments}, resolved through
     * {@link EffectiveGrantResolver} (ADR-0061 amendment, 2026-09-09, #1914). Location scope is
     * not evaluated here (ADR-0061 §1).
     */
    @Override
    @Transactional(readOnly = true)
    public boolean userHasPermission(UUID userId, String permissionName) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND_PREFIX + userId));
        return effectiveGrantResolver.resolve(user).permissionNames().contains(permissionName);
    }

    /**
     * Revoke a role assignment.
     *
     * <p>{@code endDate} may be backdated (the row was already not effective) or scheduled ahead
     * of now (still effective until it arrives) — either way this ends the holder's live tokens
     * now, through {@link RoleAssignmentRevokedEvent} (ADR-0061 §4 amendment, 2026-09-09, #1914
     * phase 3): a future-dated revocation still changes the assignment record the holder's current
     * tokens were minted against, and the token reissued after this call is then clamped to the
     * scheduled end by {@code JwtServiceImpl.generateTokenPair}, which is the correct outcome
     * either way. Unlike {@link #revokeRoleFromUser}, this does not go through {@link
     * UserRoleGrantService} — it revokes a specific assignment by id, which may not be the row
     * {@code revoke}'s effective-window lookup would find — so it publishes the event itself
     * rather than inheriting it from {@code UserRoleGrantServiceImpl}.
     */
    @Override
    @Transactional
    public void revokeRoleAssignment(@NonNull UUID assignmentId, @NonNull LocalDateTime endDate) {
        RoleAssignment assignment = roleAssignmentRepository
                .findById(assignmentId)
                .orElseThrow(() -> new RoleAssignmentNotFoundException("Role assignment not found: " + assignmentId));

        assignment.revoke(endDate, Instant.now(clock));
        assignment.setLastModifiedBy(getCurrentUsername());
        assignment.setLastModifiedAt(Instant.now(clock));

        roleAssignmentRepository.save(assignment);
        eventPublisher.publishEvent(
                new RoleAssignmentRevokedEvent(this, assignment.getUser().getId()));

        log.info(
                "Revoked role assignment: id={}, user={}, role={}, endDate={}, revokedAt={}",
                assignmentId,
                assignment.getUser().getUsername(),
                assignment.getRole().getName(),
                endDate,
                assignment.getRevokedAt());
    }

    /**
     * Get all roles.
     */
    @Override
    public List<RoleDto> getAllRoles() {
        return roleRepository.findAll().stream().map(this::toRoleDto).toList();
    }

    /**
     * Get role by name.
     */
    @Override
    public RoleDto getRoleByName(String name) {
        return toRoleDto(roleRepository
                .findByName(name)
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + name)));
    }

    // ── Story #62 implementations ─────────────────────────────────────────────

    @Override
    public Optional<RoleDto> getRoleById(@NonNull UUID id) {
        return roleRepository.findById(id).map(this::toRoleDto);
    }

    @Override
    @Transactional
    public void deleteRole(@NonNull UUID id) {
        Role role =
                roleRepository.findById(id).orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + id));
        role.getPermissions().clear();
        roleRepository.save(role);
        roleAssignmentRepository.deleteByRole_Id(id);
        roleRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void assignPermissionToRole(@NonNull UUID roleId, @NonNull String permissionKey) {
        Role role = roleRepository
                .findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + roleId));
        Permission permission = permissionRepository
                .findByName(permissionKey)
                .orElseThrow(() -> new PermissionNotFoundException(
                        "Permission not found: " + permissionKey + ". It must be registered first."));
        boolean newGrant = role.getPermissions().add(permission);
        role.setLastModifiedBy(getCurrentUsername());
        role.setLastModifiedAt(Instant.now(clock));
        roleRepository.save(role);
        if (newGrant) {
            roleRepository.recordGrantProvenance(
                    roleId, List.of(permission.getId()), getCurrentUsername(), Instant.now(clock));
        }
    }

    @Override
    @Transactional
    public void revokePermissionFromRole(@NonNull UUID roleId, @NonNull String permissionKey) {
        Role role = roleRepository
                .findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + roleId));
        role.getPermissions().removeIf(p -> p.getName().equals(permissionKey));
        role.setLastModifiedBy(getCurrentUsername());
        role.setLastModifiedAt(Instant.now(clock));
        roleRepository.save(role);
    }

    @Override
    @Transactional
    public void assignRoleToUser(@NonNull UUID userId, @NonNull UUID roleId) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND_PREFIX + userId));
        Role role = roleRepository
                .findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + roleId));

        // Idempotent: a pair already effectively assigned is a no-op rather than a second,
        // overlapping open-ended row (ADR-0061 amendment phase 2, #1914).
        userRoleGrantService.grant(user, role, getCurrentUsername());

        emitAuditEvent(new AuditLogEventRequest(
                "RoleAssignedToUser", getCurrentUsername(), userId.toString(), "User", "", role.getName(), null));
    }

    @Override
    @Transactional
    public void revokeRoleFromUser(@NonNull UUID userId, @NonNull UUID roleId) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND_PREFIX + userId));
        Role role = roleRepository
                .findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + roleId));

        LocalDateTime now = LocalDateTime.now(clock);
        boolean hasEffectiveAssignment = roleAssignmentRepository.findByUserAndRole(user, role).stream()
                .anyMatch(assignmentCandidate -> assignmentCandidate.isEffectiveAt(now));
        if (!hasEffectiveAssignment) {
            throw new RoleAssignmentNotFoundException(
                    "No active assignment for user " + userId + " and role " + roleId);
        }

        userRoleGrantService.revoke(user, role, getCurrentUsername());

        emitAuditEvent(new AuditLogEventRequest(
                "RoleRevokedFromUser", getCurrentUsername(), userId.toString(), "User", role.getName(), "", null));
    }

    private void emitAuditEvent(AuditLogEventRequest request) {
        try {
            auditEventService.createEvent(request);
        } catch (Exception exception) {
            log.warn("Audit event emission failed: {}", exception.getMessage());
        }
    }

    private String getCurrentUsername() {
        return CurrentActor.resolve();
    }

    private List<RoleAssignment> getAssignmentEntitiesForUser(UUID userId, boolean includeHistory) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND_PREFIX + userId));

        if (includeHistory) {
            return roleAssignmentRepository.findAllByUser_Id(userId);
        }

        return effectiveGrantResolver.resolve(user).assignments();
    }

    private RoleDto toRoleDto(Role role) {
        return RoleDto.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(role.getPermissions().stream()
                        .map(this::toPermissionDto)
                        .collect(Collectors.toSet()))
                .personaTitle(role.getPersonaTitle())
                .personaFocus(role.getPersonaFocus())
                .personaTone(role.getPersonaTone())
                .mcpPersonaRank(role.getMcpPersonaRank())
                .mcpPersonaEligible(role.isMcpPersonaEligible())
                .createdAt(role.getCreatedAt())
                .createdBy(role.getCreatedBy())
                .lastModifiedAt(role.getLastModifiedAt())
                .lastModifiedBy(role.getLastModifiedBy())
                .build();
    }

    private PermissionDto toPermissionDto(Permission permission) {
        return PermissionDto.builder()
                .id(permission.getId())
                .name(permission.getName())
                .domain(permission.getDomain())
                .description(permission.getDescription())
                .deprecated(permission.isDeprecated())
                .supersededBy(permission.getSupersededBy())
                .build();
    }

    /**
     * Maps an assignment to its wire shape, including the role's stable code.
     *
     * <p>{@code roleCode} reads through the lazy {@code role} association, so every path reaching
     * here must already have it loaded: both listing sources ({@code findAllByUser_Id}, and
     * {@code findEffectiveAssignmentsByUser} reached through {@code EffectiveGrantResolver}) declare
     * {@code @EntityGraph(attributePaths = {"user", "role"})}, and {@code createRoleAssignment}
     * sets a role it fetched itself. Reading {@code getRole().getId()} alone would have been
     * satisfied by an uninitialized proxy; reading the name is not, which is why the fetch plan
     * matters here and did not before.
     */
    private RoleAssignmentDto toRoleAssignmentDto(RoleAssignment assignment) {
        return RoleAssignmentDto.builder()
                .id(assignment.getId())
                .userId(assignment.getUser().getId())
                .roleId(assignment.getRole().getId())
                .roleCode(assignment.getRole().getName())
                .effectiveStartDate(assignment.getEffectiveStartDate())
                .effectiveEndDate(assignment.getEffectiveEndDate())
                .revokedAt(assignment.getRevokedAt())
                .createdAt(assignment.getCreatedAt())
                .createdBy(assignment.getCreatedBy())
                .lastModifiedAt(assignment.getLastModifiedAt())
                .lastModifiedBy(assignment.getLastModifiedBy())
                .build();
    }
}
