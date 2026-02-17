package com.positivity.catalog.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Registers catalog permissions with pos-security-service at startup.
 *
 * <p>
 * Note: Current controller methods use role-based access (ROLE_ADMIN,
 * ROLE_CATALOG_VIEW, etc.)
 * These permissions are registered for future migration to fine-grained
 * permission-based access.
 * </p>
 */
@Component
public class CatalogPermissionRegistration extends PermissionRegistrationSupport {

    public CatalogPermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, "catalog", "pos-catalog", enabled);
    }

    @Override
    protected List<PermissionDefinition> getPermissions() {
        return List.of(
                // Product Management
                PermissionDefinition.of("catalog:product:view", "View products in catalog"),
                PermissionDefinition.of("catalog:product:create", "Create products in catalog"),
                PermissionDefinition.of("catalog:product:edit", "Edit products in catalog"),
                PermissionDefinition.of("catalog:product:delete", "Delete products from catalog"),
                PermissionDefinition.of("product:lifecycle:update", "Update product lifecycle state"),
                PermissionDefinition.of("product:lifecycle:override_discontinued",
                        "Override discontinued lifecycle restrictions"),

                // Category Management
                PermissionDefinition.of("catalog:category:view", "View product categories"),
                PermissionDefinition.of("catalog:category:create", "Create product categories"),
                PermissionDefinition.of("catalog:category:edit", "Edit product categories"),
                PermissionDefinition.of("catalog:category:delete", "Delete product categories"),

                // Service/Part Type Management
                PermissionDefinition.of("catalog:service_type:view", "View service types"),
                PermissionDefinition.of("catalog:service_type:create", "Create service types"),
                PermissionDefinition.of("catalog:service_type:edit", "Edit service types"),

                // Variant Management
                PermissionDefinition.of("catalog:variant:view", "View product variants"),
                PermissionDefinition.of("catalog:variant:create", "Create product variants"),
                PermissionDefinition.of("catalog:variant:edit", "Edit product variants"));
    }
}
