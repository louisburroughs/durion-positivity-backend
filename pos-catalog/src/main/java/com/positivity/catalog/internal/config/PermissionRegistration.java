package com.positivity.catalog.internal.config;

import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
public class PermissionRegistration extends PermissionRegistrationSupport {

    public PermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, enabled, "permissions.yaml");
    }
}
