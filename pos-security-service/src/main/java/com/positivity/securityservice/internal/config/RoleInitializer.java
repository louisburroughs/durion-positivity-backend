package com.positivity.securityservice.internal.config;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.repository.RoleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Initializes default roles at application startup.
 * 
 * Roles bundle permissions and can be assigned to users with scope (GLOBAL or
 * LOCATION).
 * The inventory-related roles support the permission model from issue #37:
 * - INVENTORY_LEAD: Can create adjustment requests
 * - INVENTORY_MANAGER: Can create and approve adjustments (location-scoped)
 * - INVENTORY_CONTROLLER: Can approve adjustments globally
 */

@Component
@RequiredArgsConstructor
@Slf4j
public class RoleInitializer {
    private final RoleRepository roleRepository;

    @PostConstruct
    public void initRoles() {
        // Initialize roles with their descriptions
        Map<String, String> roles = Map.of(
                "ADMIN", "System administrator with full access",
                "GENERAL_MANAGER", "General manager with broad organizational access",
                "MANAGER", "Department or location manager",
                "CUSTOMER", "Customer with limited access to self-service features",
                "INVENTORY_LEAD", "Inventory lead with permission to create adjustment requests",
                "INVENTORY_MANAGER", "Inventory manager with permission to create and approve adjustments",
                "INVENTORY_CONTROLLER", "Inventory controller with global adjustment approval authority");

        for (Map.Entry<String, String> entry : roles.entrySet()) {
            String roleName = entry.getKey();
            String description = entry.getValue();

            if (!roleRepository.existsByName(roleName)) {
                Role role = new Role();
                role.setName(roleName);
                role.setDescription(description);
                role.setCreatedBy("system");
                role.setCreatedAt(Instant.now());
                roleRepository.save(role);
                log.info("Initialized default role: {}", roleName);
            }
        }
    }
}
