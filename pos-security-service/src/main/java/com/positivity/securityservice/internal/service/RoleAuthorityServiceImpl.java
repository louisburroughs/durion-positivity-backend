package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.service.RoleAuthorityService;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Expands business roles to concrete authorities used by downstream services.
 *
 * This includes domain permissions like "crm:party:view" which are consumed
 * by `@PreAuthorize("hasAuthority('crm:...')")` checks in services such as
 * pos-customer, and "accounting:je:view" etc. for pos-accounting.
 *
 * Supports role expansion for CRM, Accounting, and other domains.
 */
@Service
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class RoleAuthorityServiceImpl implements RoleAuthorityService {
    private static final String MCP_CHAT_EXECUTE = "mcp:chat:execute";

    /**
     * Expand roles to authorities, including ROLE_* and domain permission strings.
     * Supports CRM roles (CSR, FLEET_MANAGER) and Accounting roles (GL_ANALYST,
     * AP_CLERK, etc.).
     */
    @Override
    public Set<String> expandRolesToAuthorities(Set<String> roles) {
        Set<String> authorities = new HashSet<>();
        if (roles == null || roles.isEmpty()) return authorities;

        for (String role : roles) {
            if (role == null || role.isBlank()) continue;
            String normalized = normalizeRole(role);
            // Always include the ROLE_* itself
            authorities.add(ROLE_PREFIX + normalized);

            switch (normalized) {
                // CRM roles
                case ROLE_ADMIN -> authorities.addAll(adminAuthorities());
                case ROLE_GENERAL_MANAGER -> authorities.addAll(generalManagerAuthorities());
                case ROLE_MANAGER -> authorities.addAll(managerAuthorities());
                case ROLE_FLEET_MANAGER -> authorities.addAll(fleetManagerAuthorities());
                case ROLE_CSR -> authorities.addAll(csrAuthorities());
                // Accounting roles
                case ROLE_GL_ANALYST -> authorities.addAll(glAnalystAuthorities());
                case ROLE_AP_CLERK -> authorities.addAll(apClerkAuthorities());
                case ROLE_ACCOUNTANT -> authorities.addAll(accountantAuthorities());
                case ROLE_CONTROLLER -> authorities.addAll(controllerAuthorities());
                default -> {}
            }
        }
        return authorities;
    }

    private String normalizeRole(String role) {
        return role.trim().toUpperCase(Locale.ROOT).replaceFirst("^" + ROLE_PREFIX, "");
    }

    private Set<String> csrAuthorities() {
        Set<String> authorities = new HashSet<>(interactiveChatAuthorities());
        authorities.addAll(List.of(
                // Party
                "crm:party:view",
                "crm:party:search",
                // Contacts & roles
                "crm:contact:view",
                "crm:contact:create",
                "crm:contact:edit",
                "crm:contact_role:view",
                "crm:contact_role:assign",
                // Communication preferences
                "crm:contact_preference:view",
                "crm:contact_preference:edit",
                // Vehicles (view/search only)
                "crm:vehicle:view",
                "crm:vehicle:search",
                // Associations (view only)
                "crm:vehicle_party_association:view",
                // Integration monitoring (read-only)
                "crm:processing_log:view",
                "crm:suspense:view"));
        return authorities;
    }

    private Set<String> fleetManagerAuthorities() {
        Set<String> set = new HashSet<>(csrAuthorities());
        set.addAll(List.of(
                // Party edit
                "crm:party:edit",
                // Vehicles create/edit
                "crm:vehicle:create",
                "crm:vehicle:edit",
                // Associations manage
                "crm:vehicle_party_association:create",
                "crm:vehicle_party_association:edit",
                // Vehicle preferences
                "crm:vehicle_preference:view",
                "crm:vehicle_preference:edit"));
        return set;
    }

    private Set<String> adminAuthorities() {
        Set<String> set = new HashSet<>(fleetManagerAuthorities());
        // CRM high-risk operations
        set.addAll(List.of("crm:party:deactivate", "crm:party:merge"));
        // Accounting admin (all permissions)
        set.addAll(controllerAuthorities());
        set.addAll(adminSecurityAuthorities());
        return set;
    }

    private Set<String> generalManagerAuthorities() {
        return new HashSet<>(managerAuthorities());
    }

    private Set<String> managerAuthorities() {
        Set<String> authorities = new HashSet<>(interactiveChatAuthorities());
        authorities.addAll(List.of("security:role:view", "security:role:assign", "security:permission:view"));
        return authorities;
    }

    private Set<String> adminSecurityAuthorities() {
        return new HashSet<>(List.of(
                "security:role:view",
                "security:role:create",
                "security:role:edit",
                "security:role:delete",
                "security:role:assign",
                "security:permission:view",
                "security:permission:register",
                "security:user:view",
                "security:user:create",
                "security:user:edit",
                "security:user:delete",
                "security:user_account_state:view",
                "security:user_account_state:manage",
                "security:audit:view",
                "security:audit:create",
                "security:authorization:decide",
                "security:token:issue_internal"));
    }

    // ============================================================================
    // Accounting Domain Authorities (GL Analyst → AP Clerk → Accountant →
    // Controller)
    // ============================================================================

    private Set<String> glAnalystAuthorities() {
        Set<String> authorities = new HashSet<>(interactiveChatAuthorities());
        authorities.addAll(List.of(
                // GL Account view
                "accounting:coa:view",
                "accounting:coa:create",
                "accounting:coa:edit",
                // GL Mapping
                "accounting:mapping:view",
                "accounting:mapping:create",
                "accounting:mapping:edit",
                // Posting Rules
                "accounting:posting_rules:view",
                "accounting:posting_rules:create",
                // Journal Entries (view and create draft)
                "accounting:je:view",
                "accounting:je:create",
                // Events
                "accounting:events:view",
                "accounting:events:submit",
                // AP
                "accounting:ap:view"));
        return authorities;
    }

    private Set<String> interactiveChatAuthorities() {
        return new HashSet<>(Set.of(MCP_CHAT_EXECUTE));
    }

    private Set<String> apClerkAuthorities() {
        Set<String> set = new HashSet<>(glAnalystAuthorities());
        // Additional AP Clerk permissions (already include GL_ANALYST)
        set.addAll(List.of("accounting:ap:approve", "accounting:ap:reject", "accounting:ap:pay"));
        return set;
    }

    private Set<String> accountantAuthorities() {
        Set<String> set = new HashSet<>(apClerkAuthorities());
        // Additional Accountant permissions (includes AP_CLERK + GL_ANALYST)
        set.addAll(List.of(
                "accounting:coa:deactivate",
                "accounting:mapping:deactivate",
                "accounting:posting_rules:publish",
                "accounting:je:post",
                "accounting:je:reverse",
                "accounting:events:retry"));
        return set;
    }

    private Set<String> controllerAuthorities() {
        Set<String> set = new HashSet<>(accountantAuthorities());
        // Additional Controller permissions (includes ACCOUNTANT)
        set.addAll(List.of("accounting:posting_rules:archive"));
        return set;
    }
}
