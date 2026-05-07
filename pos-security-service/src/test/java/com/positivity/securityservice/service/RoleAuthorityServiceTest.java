package com.positivity.securityservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.service.RoleAuthorityServiceImpl;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoleAuthorityServiceTest {
    private static final String MCP_CHAT_EXECUTE = "mcp:chat:execute";

    private final RoleAuthorityServiceImpl roleAuthorityService = new RoleAuthorityServiceImpl();

    @Test
    void expandRolesToAuthorities_adminIncludesRoleAndDomainAuthorities() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("ADMIN"));

        assertThat(authorities)
                .contains("ROLE_ADMIN")
                .contains("crm:party:view")
                .contains("accounting:posting_rules:archive")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_adminIncludesAllCustomerAuthorities() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("ADMIN"));

        assertThat(authorities).contains(
                "crm:party:view",
                "crm:party:search",
                "crm:party:create",
                "crm:party:edit",
                "crm:party:deactivate",
                "crm:party:merge",
                "crm:contact:view",
                "crm:contact:create",
                "crm:contact:edit",
                "crm:contact:delete",
                "crm:contact_role:view",
                "crm:contact_role:assign",
                "crm:contact_role:revoke",
                "crm:contact_preference:view",
                "crm:contact_preference:edit",
                "crm:vehicle:view",
                "crm:vehicle:search",
                "crm:vehicle:create",
                "crm:vehicle:edit",
                "crm:vehicle:deactivate",
                "crm:vehicle_party_association:view",
                "crm:vehicle_party_association:create",
                "crm:vehicle_party_association:edit",
                "crm:vehicle_preference:view",
                "crm:vehicle_preference:edit",
                "crm:processing_log:view",
                "crm:suspense:view",
                "crm:integration:audit",
                "crm:billing_rules:edit");
    }

    @Test
    void expandRolesToAuthorities_handlesPrefixedAndBlankRoles() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("ROLE_CSR", " "));

        assertThat(authorities).contains("ROLE_CSR").contains("crm:party:view").contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_nullRoles_returnsEmpty() {
        assertThat(roleAuthorityService.expandRolesToAuthorities(null)).isEmpty();
    }

    @Test
    void expandRolesToAuthorities_emptyRoles_returnsEmpty() {
        assertThat(roleAuthorityService.expandRolesToAuthorities(Set.of())).isEmpty();
    }

    @Test
    void expandRolesToAuthorities_unknownRole_returnsOnlyRolePrefix() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("MECHANIC"));

        assertThat(authorities).containsExactly("ROLE_MECHANIC");
    }

    @Test
    void expandRolesToAuthorities_fleetManagerIncludesCsrAndVehiclePermissions() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("FLEET_MANAGER"));

        assertThat(authorities)
                .contains("ROLE_FLEET_MANAGER")
                .contains("crm:vehicle:create")
                .contains("crm:party:view")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_glAnalystIncludesAccountingPermissions() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("GL_ANALYST"));

        assertThat(authorities)
                .contains("ROLE_GL_ANALYST")
                .contains("accounting:je:view")
                .contains("accounting:coa:view")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_apClerkIncludesGlAnalystAndApPermissions() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("AP_CLERK"));

        assertThat(authorities)
                .contains("ROLE_AP_CLERK")
                .contains("accounting:ap:approve")
                .contains("accounting:je:view")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_accountantIncludesPostingPermissions() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("ACCOUNTANT"));

        assertThat(authorities)
                .contains("ROLE_ACCOUNTANT")
                .contains("accounting:je:post")
                .contains("accounting:coa:deactivate")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_controllerIncludesArchivePermission() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("CONTROLLER"));

        assertThat(authorities)
                .contains("ROLE_CONTROLLER")
                .contains("accounting:posting_rules:archive")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_accountingAssociateIncludesOperationalAccountingPermissions() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("ACCOUNTING_ASSOCIATE"));

        assertThat(authorities)
                .contains("ROLE_ACCOUNTING_ASSOCIATE")
                .contains("accounting:ap:approve")
                .contains("accounting:je:view")
                .doesNotContain("accounting:je:post")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_accountManagerIncludesBillingRulesAndPostingPermissions() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("ACCOUNT_MANAGER"));

        assertThat(authorities)
                .contains("ROLE_ACCOUNT_MANAGER")
                .contains("accounting:je:post")
                .contains("invoice:billing-rules")
                .doesNotContain("accounting:posting_rules:archive")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_locationManagerIncludesShopExecutionAndManagerOverrides() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("LOCATION_MANAGER"));

        assertThat(authorities)
                .contains("ROLE_LOCATION_MANAGER")
                .contains("shop:schedule:edit")
                .contains("workorder:workorder:assign-technician")
                .contains("workorder:change_request:emergency_override")
                .contains("timekeeping:overlap_override")
                .doesNotContain("security:user:edit")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_serviceAdvisorIncludesEstimateAndInvoiceWorkflowPermissions() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("SERVICE_ADVISOR"));

        assertThat(authorities)
                .contains("ROLE_SERVICE_ADVISOR")
                .contains("appointments:create")
                .contains("workorder:estimate:promote")
                .contains("workorder:workorder:generate_invoice")
                .contains("invoice:finalize")
                .doesNotContain("workorder:workorder:assign-technician")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_technicianIncludesLaborPartsAndWorkSessionPermissions() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("TECHNICIAN"));

        assertThat(authorities)
                .contains("ROLE_TECHNICIAN")
                .contains("workorder:labor:add")
                .contains("workorder:parts:consume")
                .contains("inventory:pick_list:execute")
                .contains("timekeeping:work_session:create")
                .doesNotContain("invoice:finalize")
                .contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_knownBusinessRolesIncludeChatExecutePermission() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of(
                "ADMIN",
                "CSR",
                "FLEET_MANAGER",
                "MANAGER",
                "GENERAL_MANAGER",
                "GL_ANALYST",
                "AP_CLERK",
                "ACCOUNTANT",
                "CONTROLLER",
                "ACCOUNTING_ASSOCIATE",
                "ACCOUNT_MANAGER",
                "LOCATION_MANAGER",
                "SERVICE_ADVISOR",
                "TECHNICIAN"));

        assertThat(authorities).contains(MCP_CHAT_EXECUTE);
    }

    @Test
    void expandRolesToAuthorities_nullRoleInSet_isSkipped() {
        Set<String> roles = new HashSet<>();
        roles.add(null);
        roles.add("CSR");

        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(roles);

        assertThat(authorities).contains("ROLE_CSR").noneMatch(a -> a.contains("null"));
    }
}
