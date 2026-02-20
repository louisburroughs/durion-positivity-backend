package com.positivity.price.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Registers pricing permissions with pos-security-service at startup.
 */
@Component
public class PricePermissionRegistration extends PermissionRegistrationSupport {

    public PricePermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, "pricing", "pos-price", enabled);
    }

    @Override
    protected List<PermissionDefinition> getPermissions() {
        return List.of(
                // Price Book Management
                PermissionDefinition.of("pricing:price_book:view", "View price books"),
                PermissionDefinition.of("pricing:price_book:create", "Create price books"),
                PermissionDefinition.of("pricing:price_book:edit", "Edit price books"),
                PermissionDefinition.of("pricing:price_book:delete", "Delete price books"),

                // Price Normalization
                PermissionDefinition.of("pricing:normalization:view", "View price normalization rules"),
                PermissionDefinition.of("pricing:normalization:edit", "Edit price normalization rules"),

                // Price Restrictions
                PermissionDefinition.of("pricing:restrictions:view", "View price restrictions"),
                PermissionDefinition.of("pricing:restrictions:edit", "Edit price restrictions"),

                // Pricing Rules
                PermissionDefinition.of("pricing:rule:view", "View pricing rules"),
                PermissionDefinition.of("pricing:rule:create", "Create pricing rules"),
                PermissionDefinition.of("pricing:rule:edit", "Edit pricing rules"),
                PermissionDefinition.of("pricing:rule:delete", "Delete pricing rules"));
    }
}
