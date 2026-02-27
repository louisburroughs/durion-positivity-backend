package com.positivity.shopmanager.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Registers shop manager permissions with pos-security-service at startup.
 */
@Component
public class ShopManagerPermissionRegistration extends PermissionRegistrationSupport {

    public ShopManagerPermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, enabled, "permissions.yaml");
    }

    @Override
    protected List<PermissionDefinition> getPermissions() {
        return super.getPermissions();
    }
}
