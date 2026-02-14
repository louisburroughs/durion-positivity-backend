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
                PermissionDefinition.of("workorder:workorder:start", "Start work on workorders"),
                PermissionDefinition.of("workorder:workorder:complete", "Complete workorders"),
                PermissionDefinition.of("workorder:workorder:approve", "Approve workorders"),

                // Estimate Management
                PermissionDefinition.of("workorder:estimate:view", "View estimates"),
                PermissionDefinition.of("workorder:estimate:create", "Create estimates"),
                PermissionDefinition.of("workorder:estimate:edit", "Edit estimates"),
                PermissionDefinition.of("workorder:estimate:delete", "Delete estimates"),
                PermissionDefinition.of("workorder:estimate:approve", "Approve estimates"),
                PermissionDefinition.of("workorder:estimate:decline", "Decline estimates"),
                PermissionDefinition.of("workorder:estimate:reopen", "Reopen declined estimates"),
                PermissionDefinition.of("workorder:estimate:calculate", "Calculate estimate totals and taxes"),

                // Estimate Item Management
                PermissionDefinition.of("workorder:estimate_item:view", "View estimate line items"),
                PermissionDefinition.of("workorder:estimate_item:add", "Add items to estimates"),
                PermissionDefinition.of("workorder:estimate_item:edit", "Edit estimate items"),
                PermissionDefinition.of("workorder:estimate_item:delete", "Remove items from estimates"),

                // Estimate Snapshots
                PermissionDefinition.of("workorder:estimate_snapshot:create", "Create estimate snapshots"),
                PermissionDefinition.of("workorder:estimate_snapshot:view", "View estimate snapshot history"),

                // Change Request Management
                PermissionDefinition.of("workorder:change_request:view", "View change requests"),
                PermissionDefinition.of("workorder:change_request:create", "Create change requests"),
                PermissionDefinition.of("workorder:change_request:approve", "Approve change requests"),
                PermissionDefinition.of("workorder:change_request:decline", "Decline change requests"),
                PermissionDefinition.of("workorder:change_request:emergency_override",
                        "Apply emergency override to change requests (Manager only)"),

                // Approval Configuration Management
                PermissionDefinition.of("workorder:approval_config:view", "View approval configurations"),
                PermissionDefinition.of("workorder:approval_config:create", "Create approval configurations"),
                PermissionDefinition.of("workorder:approval_config:edit", "Edit approval configurations"),
                PermissionDefinition.of("workorder:approval_config:delete", "Delete approval configurations"),

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
