package com.positivity.tax.internal.config;

import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Registers pos-tax permissions with pos-security-service at startup, mirroring the
 * {@code EventTypeInitializer} pattern. Failures are swallowed by the shared support so
 * startup never blocks (the service is internal-only and enforcement degrades safely).
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
