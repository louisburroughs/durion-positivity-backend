package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.ScopeType;
import com.positivity.securityservice.internal.exception.DuplicateRoleNameException;
import com.positivity.securityservice.internal.exception.PermissionNotFoundException;
import com.positivity.securityservice.internal.exception.RoleAssignmentNotFoundException;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.AuditEventService;
import com.positivity.securityservice.service.RoleManagementService;
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
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing roles, role assignments, and role-permission mappings.
 * Implements the foundational RBAC framework with scope support.
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

    /**
     * Create a new role.
     */
    @Override
    @Transactional
    public RoleDto createRole(@NonNull String name, @Nullable String description) {
        if (roleRepository.existsByNameIgnoreCase(name)) {
            throw new DuplicateRoleNameException("Role with name " + name + " already exists");
        }

        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        role.setCreatedBy(getCurrentUsername());
        role.setCreatedAt(Instant.now(clock));

        return toRoleDto(roleRepository.save(role));
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

        role.setPermissions(permissions);
        role.setLastModifiedBy(getCurrentUsername());
        role.setLastModifiedAt(Instant.now(clock));

        log.info("Updated permissions for role {}: {} permissions assigned", role.getName(), permissions.size());

        return toRoleDto(roleRepository.save(role));
    }

    /**
     * Create a role assignment for a user.
     */
    @Override
    @Transactional
    public RoleAssignmentDto createRoleAssignment(RoleAssignmentRequest request) {
        if (request.getScopeType() == ScopeType.LOCATION
                && (request.getScopeLocationIds() == null
                        || request.getScopeLocationIds().isEmpty())) {
            throw new IllegalArgumentException("LOCATION scope requires at least one location ID");
        }

        if (request.getScopeType() == ScopeType.GLOBAL
                && request.getScopeLocationIds() != null
                && !request.getScopeLocationIds().isEmpty()) {
            throw new IllegalArgumentException("GLOBAL scope cannot have location IDs");
        }

        User user = userRepository
                .findById(request.getUserId())
                .orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND_PREFIX + request.getUserId()));

        Role role = roleRepository
                .findById(request.getRoleId())
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + request.getRoleId()));

        LocalDateTime requestStart =
                request.getEffectiveStartDate() != null ? request.getEffectiveStartDate() : LocalDateTime.now(clock);
        LocalDateTime requestEnd = request.getEffectiveEndDate();

        validateNoOverlappingAssignment(user.getId(), role.getId(), request, requestStart, requestEnd);

        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setScopeType(request.getScopeType());
        assignment.setScopeLocationIds(
                request.getScopeLocationIds() != null ? request.getScopeLocationIds() : new HashSet<>());
        assignment.setEffectiveStartDate(requestStart);
        assignment.setEffectiveEndDate(request.getEffectiveEndDate());
        assignment.setCreatedBy(getCurrentUsername());
        assignment.setCreatedAt(Instant.now(clock));

        log.info(
                "Created role assignment: user={}, role={}, scope={}, locations={}",
                user.getUsername(),
                role.getName(),
                assignment.getScopeType(),
                assignment.getScopeLocationIds());

        return toRoleAssignmentDto(roleAssignmentRepository.save(assignment));
    }

    private void validateNoOverlappingAssignment(
            UUID userId,
            UUID roleId,
            RoleAssignmentRequest request,
            LocalDateTime requestStart,
            LocalDateTime requestEnd) {

        List<RoleAssignment> existingAssignments =
                roleAssignmentRepository.findByUser_IdAndRole_IdAndScopeType(userId, roleId, request.getScopeType());

        for (RoleAssignment existing : existingAssignments) {
            boolean sameRoleAndScope =
                    existing.getRole().getId().equals(roleId) && existing.getScopeType() == request.getScopeType();

            if (sameRoleAndScope && hasDateOverlap(existing, requestStart, requestEnd)) {
                if (request.getScopeType() == ScopeType.LOCATION) {
                    if (hasLocationOverlap(existing.getScopeLocationIds(), request.getScopeLocationIds())) {
                        throw new IllegalStateException(
                                "Overlapping role assignment exists for same role, scope, and location(s)");
                    }
                } else {
                    throw new IllegalStateException(
                            "Overlapping role assignment exists for same role and GLOBAL scope");
                }
            }
        }
    }

    private boolean hasDateOverlap(RoleAssignment existing, LocalDateTime requestStart, LocalDateTime requestEnd) {
        LocalDateTime existingStart = existing.getEffectiveStartDate();
        LocalDateTime existingEnd = existing.getEffectiveEndDate();

        return (existingEnd == null || !requestStart.isAfter(existingEnd))
                && (requestEnd == null || !existingStart.isAfter(requestEnd));
    }

    private boolean hasLocationOverlap(Set<String> existingLocationIds, Set<String> requestLocationIds) {
        Set<String> existingLocs = new HashSet<>(existingLocationIds);
        Set<String> requestLocs = requestLocationIds != null ? new HashSet<>(requestLocationIds) : new HashSet<>();

        existingLocs.retainAll(requestLocs);
        return !existingLocs.isEmpty();
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
     * Get all permissions for a user (from all their effective role assignments).
     */
    @Override
    public Set<PermissionDto> getUserPermissions(UUID userId) {
        List<RoleAssignment> assignments = getAssignmentEntitiesForUser(userId, false);
        Set<PermissionDto> allPermissions = new HashSet<>();

        for (RoleAssignment assignment : assignments) {
            allPermissions.addAll(assignment.getRole().getPermissions().stream()
                    .map(this::toPermissionDto)
                    .collect(Collectors.toSet()));
        }

        return allPermissions;
    }

    /**
     * Check if a user has a specific permission, considering scope.
     */
    @Override
    public boolean userHasPermission(UUID userId, String permissionName, String locationId) {
        List<RoleAssignment> assignments = getAssignmentEntitiesForUser(userId, false);

        for (RoleAssignment assignment : assignments) {
            if (!assignment.coversLocation(locationId)) {
                continue;
            }

            for (Permission permission : assignment.getRole().getPermissions()) {
                if (permission.getName().equals(permissionName)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Revoke a role assignment.
     */
    @Override
    @Transactional
    public void revokeRoleAssignment(@NonNull UUID assignmentId, @NonNull LocalDateTime endDate) {
        RoleAssignment assignment = roleAssignmentRepository
                .findById(assignmentId)
                .orElseThrow(() -> new RoleAssignmentNotFoundException("Role assignment not found: " + assignmentId));

        assignment.setEffectiveEndDate(endDate); // This automatically sets revokedAt
        assignment.setLastModifiedBy(getCurrentUsername());
        assignment.setLastModifiedAt(Instant.now(clock));

        roleAssignmentRepository.save(assignment);

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
        role.getPermissions().add(permission);
        role.setLastModifiedBy(getCurrentUsername());
        role.setLastModifiedAt(Instant.now(clock));
        roleRepository.save(role);
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

        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setScopeType(ScopeType.GLOBAL);
        assignment.setEffectiveStartDate(LocalDateTime.now(clock));
        assignment.setCreatedBy(getCurrentUsername());
        assignment.setCreatedAt(Instant.now(clock));

        roleAssignmentRepository.save(assignment);

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

        RoleAssignment assignment = roleAssignmentRepository.findByUserAndRole(user, role).stream()
                .filter(RoleAssignment::isEffective)
                .findFirst()
                .orElseThrow(() -> new RoleAssignmentNotFoundException(
                        "No active assignment for user " + userId + " and role " + roleId));

        assignment.setEffectiveEndDate(LocalDateTime.now(clock));
        assignment.setLastModifiedBy(getCurrentUsername());
        assignment.setLastModifiedAt(Instant.now(clock));

        roleAssignmentRepository.save(assignment);

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
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return "system";
    }

    private List<RoleAssignment> getAssignmentEntitiesForUser(UUID userId, boolean includeHistory) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND_PREFIX + userId));

        if (includeHistory) {
            return roleAssignmentRepository.findAllByUser_Id(userId);
        }

        return roleAssignmentRepository.findEffectiveAssignmentsByUser(user);
    }

    private RoleDto toRoleDto(Role role) {
        return RoleDto.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(role.getPermissions().stream()
                        .map(this::toPermissionDto)
                        .collect(Collectors.toSet()))
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
                .build();
    }

    private RoleAssignmentDto toRoleAssignmentDto(RoleAssignment assignment) {
        return RoleAssignmentDto.builder()
                .id(assignment.getId())
                .userId(assignment.getUser().getId())
                .roleId(assignment.getRole().getId())
                .scopeType(assignment.getScopeType())
                .scopeLocationIds(assignment.getScopeLocationIds())
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
