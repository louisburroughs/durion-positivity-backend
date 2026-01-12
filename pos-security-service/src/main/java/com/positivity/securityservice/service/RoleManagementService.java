package com.positivity.securityservice.service;

import com.positivity.securityservice.dto.RoleAssignmentRequest;
import com.positivity.securityservice.dto.RolePermissionsRequest;
import com.positivity.securityservice.model.Permission;
import com.positivity.securityservice.model.Role;
import com.positivity.securityservice.model.RoleAssignment;
import com.positivity.securityservice.model.User;
import com.positivity.securityservice.repository.PermissionRepository;
import com.positivity.securityservice.repository.RoleAssignmentRepository;
import com.positivity.securityservice.repository.RoleRepository;
import com.positivity.securityservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service for managing roles, role assignments, and role-permission mappings.
 * Implements the foundational RBAC framework with scope support.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleManagementService {
    
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final UserRepository userRepository;

    /**
     * Create a new role
     */
    @Transactional
    public Role createRole(String name, String description) {
        if (roleRepository.existsByName(name)) {
            throw new IllegalArgumentException("Role with name " + name + " already exists");
        }
        
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        role.setCreatedBy(getCurrentUsername());
        role.setCreatedAt(Instant.now());
        
        return roleRepository.save(role);
    }

    /**
     * Update permissions for a role
     */
    @Transactional
    public Role updateRolePermissions(RolePermissionsRequest request) {
        Role role = roleRepository.findById(request.getRoleId())
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + request.getRoleId()));
        
        Set<Permission> permissions = new HashSet<>();
        for (String permissionName : request.getPermissionNames()) {
            Permission permission = permissionRepository.findByName(permissionName)
                .orElseThrow(() -> new IllegalArgumentException(
                    "Permission not found: " + permissionName + ". It must be registered first."));
            permissions.add(permission);
        }
        
        role.setPermissions(permissions);
        role.setLastModifiedBy(getCurrentUsername());
        role.setLastModifiedAt(Instant.now());
        
        log.info("Updated permissions for role {}: {} permissions assigned", 
                 role.getName(), permissions.size());
        
        return roleRepository.save(role);
    }

    /**
     * Create a role assignment for a user
     */
    @Transactional
    public RoleAssignment createRoleAssignment(RoleAssignmentRequest request) {
        User user = userRepository.findById(request.getUserId())
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.getUserId()));
        
        Role role = roleRepository.findById(request.getRoleId())
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + request.getRoleId()));
        
        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setScopeType(request.getScopeType());
        assignment.setScopeLocationIds(request.getScopeLocationIds() != null ? 
                                       request.getScopeLocationIds() : new HashSet<>());
        assignment.setEffectiveStartDate(request.getEffectiveStartDate() != null ? 
                                        request.getEffectiveStartDate() : LocalDate.now());
        assignment.setEffectiveEndDate(request.getEffectiveEndDate());
        assignment.setCreatedBy(getCurrentUsername());
        assignment.setCreatedAt(Instant.now());
        
        log.info("Created role assignment: user={}, role={}, scope={}, locations={}", 
                 user.getUsername(), role.getName(), assignment.getScopeType(), 
                 assignment.getScopeLocationIds());
        
        return roleAssignmentRepository.save(assignment);
    }

    /**
     * Get effective role assignments for a user
     */
    public List<RoleAssignment> getEffectiveRoleAssignments(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        
        return roleAssignmentRepository.findEffectiveAssignmentsByUser(user);
    }

    /**
     * Get all permissions for a user (from all their effective role assignments)
     */
    public Set<Permission> getUserPermissions(Long userId) {
        List<RoleAssignment> assignments = getEffectiveRoleAssignments(userId);
        Set<Permission> allPermissions = new HashSet<>();
        
        for (RoleAssignment assignment : assignments) {
            allPermissions.addAll(assignment.getRole().getPermissions());
        }
        
        return allPermissions;
    }

    /**
     * Check if a user has a specific permission, considering scope
     */
    public boolean userHasPermission(Long userId, String permissionName, String locationId) {
        List<RoleAssignment> assignments = getEffectiveRoleAssignments(userId);
        
        for (RoleAssignment assignment : assignments) {
            // Check if assignment covers the location
            if (!assignment.coversLocation(locationId)) {
                continue;
            }
            
            // Check if role has the permission
            for (Permission permission : assignment.getRole().getPermissions()) {
                if (permission.getName().equals(permissionName)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Revoke a role assignment
     */
    @Transactional
    public void revokeRoleAssignment(Long assignmentId) {
        RoleAssignment assignment = roleAssignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new IllegalArgumentException("Role assignment not found: " + assignmentId));
        
        // Set end date to today to effectively revoke
        assignment.setEffectiveEndDate(LocalDate.now().minusDays(1));
        assignment.setLastModifiedBy(getCurrentUsername());
        assignment.setLastModifiedAt(Instant.now());
        
        roleAssignmentRepository.save(assignment);
        
        log.info("Revoked role assignment: id={}, user={}, role={}", 
                 assignmentId, assignment.getUser().getUsername(), assignment.getRole().getName());
    }

    /**
     * Get all roles
     */
    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    /**
     * Get role by name
     */
    public Role getRoleByName(String name) {
        return roleRepository.findByName(name)
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + name));
    }

    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return "system";
    }
}
