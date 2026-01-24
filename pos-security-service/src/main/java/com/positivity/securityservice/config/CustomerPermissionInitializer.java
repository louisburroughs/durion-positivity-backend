package com.positivity.securityservice.config;

import com.positivity.securityservice.model.Permission;
import com.positivity.securityservice.repository.PermissionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Initializes CRM (customer domain) permissions at application startup.
 *
 * Baseline set aligns with permissions currently enforced in pos-customer:
 * - crm:party:view, crm:party:create, crm:party:edit, crm:party:deactivate
 * - crm:party:merge (documented category)
 * - crm:vehicle:create (used by vehicle creation endpoint)
 *
 * Additional CRM permissions can be registered dynamically by services
 * (e.g., pos-customer via /v1/permissions/register). This initializer ensures
 * a minimal, consistent baseline in the Security Domain even if upstream
 * services are not yet running.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CustomerPermissionInitializer {

    private final PermissionRepository permissionRepository;

    private static final String DOMAIN = "crm";
    private static final String SERVICE_NAME = "pos-security-service";

    @PostConstruct
    public void initCrmPermissions() {
        log.info("Initializing CRM domain baseline permissions");

        Map<String, String> permissions = new HashMap<>();

        // Party / Customer permissions (baseline)
        permissions.put("crm:party:view", "View party/customer records");
        permissions.put("crm:party:create", "Create party/customer records");
        permissions.put("crm:party:edit", "Edit party/customer records");
        permissions.put("crm:party:deactivate", "Deactivate party/customer records");
        permissions.put("crm:party:merge", "Merge duplicate parties (winner/loser)");

        // Vehicle creation permission (documented in CRM REST wrappers)
        permissions.put("crm:vehicle:create", "Create/attach vehicle (VIN) to a party");

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
                    log.info("Registered CRM permission: {}", permissionName);
                } catch (IllegalArgumentException e) {
                    log.error("Failed to parse CRM permission name: {}", permissionName, e);
                }
            } else {
                skipped++;
                log.debug("CRM permission already exists: {}", permissionName);
            }
        }

        log.info("CRM permission initialization complete: {} registered, {} skipped", registered, skipped);
    }
}
