package com.positivity.supplier.internal.config;

import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Registers supplier service permissions ({@code permissions.yaml}, ADR-0025) with
 * pos-security-service at startup — mirrors pos-warranty {@code WarrantyPermissionRegistration}.
 */
@Component
public class SupplierPermissionRegistration extends PermissionRegistrationSupport {

    public SupplierPermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8080}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, enabled, "permissions.yaml");
    }
}
