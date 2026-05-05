package com.positivity.invoice.internal.config;

import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Registers invoice service permissions with pos-security-service at startup.
 */
@Component
public class PermissionRegistration extends PermissionRegistrationSupport {

    public PermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8080}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, enabled, "permissions.yaml");
    }
}
