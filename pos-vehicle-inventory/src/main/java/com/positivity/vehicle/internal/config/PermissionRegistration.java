package com.positivity.vehicle.internal.config;

import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Registers vehicle inventory permissions with pos-security-service at startup.
 * Registration runs on a virtual thread to avoid blocking the main startup
 * sequence.
 */
@Component
public class PermissionRegistration extends PermissionRegistrationSupport {

    public PermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8080}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, enabled, "permissions.yaml");
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("vehicle-permission-init").start(() -> super.run(args));
    }
}
