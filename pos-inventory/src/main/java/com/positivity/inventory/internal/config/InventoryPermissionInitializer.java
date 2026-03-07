package com.positivity.inventory.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;

/**
 * Registers inventory permissions with pos-security-service at startup.
 */
@Component
public class InventoryPermissionInitializer extends PermissionRegistrationSupport {

    private final List<PermissionDefinition> permissions;

    public InventoryPermissionInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, enabled, "permissions.yaml");
        this.permissions = loadPermissions();
    }

    @Override
    public List<PermissionDefinition> getPermissions() {
        return permissions;
    }

    @SuppressWarnings("unchecked")
    private List<PermissionDefinition> loadPermissions() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("permissions.yaml")) {
            if (in == null) {
                throw new IllegalStateException("permissions.yaml not found on classpath");
            }
            Yaml yaml = new Yaml();
            Map<String, Object> manifest = yaml.load(in);
            List<Map<String, String>> permList = (List<Map<String, String>>) manifest.get("permissions");
            return permList.stream()
                    .map(p -> PermissionDefinition.of(p.get("name"), p.get("description")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load permissions.yaml", e);
        }
    }
}
