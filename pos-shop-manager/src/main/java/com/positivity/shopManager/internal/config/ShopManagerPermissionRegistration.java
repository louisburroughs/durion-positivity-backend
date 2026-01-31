package com.positivity.shopManager.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Registers shop manager permissions with pos-security-service at startup.
 */
@Component
public class ShopManagerPermissionRegistration extends PermissionRegistrationSupport {

    public ShopManagerPermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, "shop", "pos-shop-manager", enabled);
    }

    @Override
    protected List<PermissionDefinition> getPermissions() {
        return List.of(
                // Shop Management
                PermissionDefinition.of("shop:location:view", "View shop/location details"),
                PermissionDefinition.of("shop:location:create", "Create shop/location"),
                PermissionDefinition.of("shop:location:edit", "Edit shop/location"),
                PermissionDefinition.of("shop:location:deactivate", "Deactivate shop/location"),

                // Bay Management
                PermissionDefinition.of("shop:bay:view", "View service bays"),
                PermissionDefinition.of("shop:bay:create", "Create service bays"),
                PermissionDefinition.of("shop:bay:edit", "Edit service bays"),
                PermissionDefinition.of("shop:bay:assign", "Assign work to bays"),

                // Schedule Management
                PermissionDefinition.of("shop:schedule:view", "View shop schedules"),
                PermissionDefinition.of("shop:schedule:edit", "Edit shop schedules"));
    }
}
