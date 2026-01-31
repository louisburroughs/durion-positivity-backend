package com.positivity.accounting.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Registers accounting permissions with pos-security-service at startup.
 */
@Component
public class AccountingPermissionRegistration extends PermissionRegistrationSupport {

    public AccountingPermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, "accounting", "pos-accounting", enabled);
    }

    @Override
    protected List<PermissionDefinition> getPermissions() {
        return List.of(
                // Journal Entry Management
                PermissionDefinition.of("accounting:je:view", "View journal entries"),
                PermissionDefinition.of("accounting:je:create", "Create journal entries"),
                PermissionDefinition.of("accounting:je:post", "Post journal entries"),

                // Accounts Payable
                PermissionDefinition.of("accounting:ap:view", "View accounts payable"),
                PermissionDefinition.of("accounting:ap:pay", "Process payments"),

                // Chart of Accounts
                PermissionDefinition.of("accounting:coa:view", "View chart of accounts"),
                PermissionDefinition.of("accounting:coa:create", "Create accounts in chart of accounts"),
                PermissionDefinition.of("accounting:coa:edit", "Edit accounts in chart of accounts"),

                // Event Ingestion
                PermissionDefinition.of("accounting:events:view", "View accounting events"),
                PermissionDefinition.of("accounting:events:submit", "Submit accounting events"),
                PermissionDefinition.of("accounting:events:retry", "Retry failed accounting events"));
    }
}
