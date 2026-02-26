package com.positivity.order.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.positivity.order.internal.security.OrderPermissions.*;
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
                PermissionDefinition.of(ORDER_VIEW, "View orders"),
                PermissionDefinition.of(ORDER_CREATE, "Create orders"),
                PermissionDefinition.of(ORDER_EDIT, "Edit orders"),
                PermissionDefinition.of(ORDER_CANCEL, "Cancel orders"),

                // Order Line Management
                PermissionDefinition.of(ORDER_LINE_VIEW, "View order lines"),
                PermissionDefinition.of(ORDER_LINE_CREATE, "Add order lines"),
                PermissionDefinition.of(ORDER_LINE_EDIT, "Edit order lines"),
                PermissionDefinition.of(ORDER_LINE_DELETE, "Remove order lines"),

                // Price Override (using existing constants)
                PermissionDefinition.of(PRICE_OVERRIDE_VIEW, "View price override history and reports"),
                PermissionDefinition.of(PRICE_OVERRIDE_APPLY, "Apply/request a price override"),
                PermissionDefinition.of(PRICE_OVERRIDE_APPROVE, "Approve price overrides"),
                PermissionDefinition.of(PRICE_OVERRIDE_REJECT, "Cancel/reject price overrides"));
    }
}
