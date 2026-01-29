package com.positivity.securityservice.internal.config;

import com.positivity.securityservice.internal.model.Permission;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Initializes inventory domain permissions at application startup.
 * 
 * Implements the permission model decided in clarification for issue #37:
 * - inventory:adjustment:create - Create adjustment requests
 * - inventory:adjustment:approve - Approve and post adjustments to ledger
 * 
 * These permissions are scope-aware (GLOBAL or LOCATION) and support
 * threshold-based approval workflows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryPermissionInitializer {
    
    private final PermissionRepository permissionRepository;
    
    private static final String DOMAIN = "inventory";
    private static final String SERVICE_NAME = "pos-security-service";
    
    @PostConstruct
    public void initInventoryPermissions() {
        log.info("Initializing inventory domain permissions");
        
        Map<String, String> permissions = new HashMap<>();
        
        // Adjustment permissions
        permissions.put(
            "inventory:adjustment:create",
            "Create an adjustment request (draft/pending), capture reason code, quantity, and supporting notes"
        );
        permissions.put(
            "inventory:adjustment:approve",
            "Approve and post an adjustment to the ledger (creates ADJUSTMENT_IN/OUT events)"
        );
        
        int registered = 0;
        int skipped = 0;
        
        for (Map.Entry<String, String> entry : permissions.entrySet()) {
            String permissionName = entry.getKey();
            String description = entry.getValue();
            
            if (!permissionRepository.existsByName(permissionName)) {
                Permission permission = new Permission();
                permission.setName(permissionName);
                permission.setDescription(description);
                permission.setRegisteredByService(SERVICE_NAME);
                permission.setRegisteredAt(Instant.now());
                permission.setVersion("1.0");
                
                try {
                    permission.parsePermissionName();
                    permissionRepository.save(permission);
                    registered++;
                    log.info("Registered inventory permission: {}", permissionName);
                } catch (IllegalArgumentException e) {
                    log.error("Failed to parse permission name: {}", permissionName, e);
                }
            } else {
                skipped++;
                log.debug("Inventory permission already exists: {}", permissionName);
            }
        }
        
        log.info("Inventory permission initialization complete: {} registered, {} skipped", 
                 registered, skipped);
    }
}
