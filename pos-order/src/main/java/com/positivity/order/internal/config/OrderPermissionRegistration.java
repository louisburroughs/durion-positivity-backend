package com.positivity.order.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.positivity.order.internal.security.PriceOverridePermissions.*;

/**
 * Registers order permissions with pos-security-service at startup.
 */
@Component
@Profile("!test")
public class OrderPermissionRegistration extends PermissionRegistrationSupport {

    public OrderPermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, "order", "pos-order", enabled);
    }

    @Override
    protected List<PermissionDefinition> getPermissions() {
        return List.of(
                // Order Management
                PermissionDefinition.of("order:order:view", "View orders"),
                PermissionDefinition.of("order:order:create", "Create orders"),
                PermissionDefinition.of("order:order:edit", "Edit orders"),
                PermissionDefinition.of("order:order:cancel", "Cancel orders"),

                // Order Line Management
                PermissionDefinition.of("order:line:view", "View order lines"),
                PermissionDefinition.of("order:line:create", "Add order lines"),
                PermissionDefinition.of("order:line:edit", "Edit order lines"),
                PermissionDefinition.of("order:line:delete", "Remove order lines"),

                // Price Override (using existing constants)
                PermissionDefinition.of(PRICE_OVERRIDE_VIEW, "View price override history and reports"),
                PermissionDefinition.of(PRICE_OVERRIDE_APPLY, "Apply/request a price override"),
                PermissionDefinition.of(PRICE_OVERRIDE_APPROVE, "Approve price overrides"),
                PermissionDefinition.of(PRICE_OVERRIDE_REJECT, "Cancel/reject price overrides"));
    }
}
