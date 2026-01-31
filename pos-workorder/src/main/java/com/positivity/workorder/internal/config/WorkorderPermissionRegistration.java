package com.positivity.workorder.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Registers workorder permissions with pos-security-service at startup.
 */
@Component
public class WorkorderPermissionRegistration extends PermissionRegistrationSupport {

    public WorkorderPermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, "workorder", "pos-workorder", enabled);
    }

    @Override
    protected List<PermissionDefinition> getPermissions() {
        return List.of(
                // Workorder Management
                PermissionDefinition.of("workorder:workorder:view", "View workorders"),
                PermissionDefinition.of("workorder:workorder:create", "Create workorders"),
                PermissionDefinition.of("workorder:workorder:edit", "Edit workorders"),
                PermissionDefinition.of("workorder:workorder:delete", "Delete workorders"),

                // Estimate Management
                PermissionDefinition.of("workorder:estimate:view", "View estimates"),
                PermissionDefinition.of("workorder:estimate:create", "Create estimates"),
                PermissionDefinition.of("workorder:estimate:edit", "Edit estimates"),
                PermissionDefinition.of("workorder:estimate:approve", "Approve estimates"),

                // Invoice Management
                PermissionDefinition.of("workorder:invoice:view", "View workorder invoices"),
                PermissionDefinition.of("workorder:invoice:create", "Create workorder invoices"),

                // Labor and Parts
                PermissionDefinition.of("workorder:labor:view", "View labor entries"),
                PermissionDefinition.of("workorder:labor:add", "Add labor entries"),
                PermissionDefinition.of("workorder:parts:view", "View parts on workorder"),
                PermissionDefinition.of("workorder:parts:add", "Add parts to workorder"));
    }
}
