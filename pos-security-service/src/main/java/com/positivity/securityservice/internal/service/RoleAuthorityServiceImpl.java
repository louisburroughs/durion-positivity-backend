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
                case ROLE_ACCOUNTING_ASSOCIATE -> authorities.addAll(accountingAssociateAuthorities());
                case ROLE_ACCOUNT_MANAGER -> authorities.addAll(accountManagerAuthorities());
                case ROLE_LOCATION_MANAGER -> authorities.addAll(locationManagerAuthorities());
                case ROLE_SERVICE_ADVISOR -> authorities.addAll(serviceAdvisorAuthorities());
                case ROLE_TECHNICIAN -> authorities.addAll(technicianAuthorities());
                default -> {
                    /* unknown role — ROLE_* prefix already added above */ }
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
                // Party / person (same CRM concept)
                "crm:party:view",
                "crm:party:search",
                "crm:person:read",
                // Relationships (read-only)
                "crm:relationship:read",
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
                "crm:suspense:view",
                // Promotion redemption
                "crm:promotion_redemption:record",
                "crm:promotion_redemption:view",
                // #704: view active promotions during a sale
                "pricing:promotion:view"));
        return authorities;
    }

    private Set<String> fleetManagerAuthorities() {
        Set<String> set = new HashSet<>(csrAuthorities());
        set.addAll(List.of(
                // Party edit
                "crm:party:edit",
                // Vehicle-party association (crm:vehicle:create guards the
                // POST /parties/{partyId}/vehicles association endpoint, ADR-0012)
                "crm:vehicle:create",
                // Vehicle registry writes live in pos-vehicle-inventory (ADR-0044 §6, #843)
                "vehicle-inventory:registry:view",
                "vehicle-inventory:registry:create",
                "vehicle-inventory:registry:update",
                // Associations manage
                "crm:vehicle_party_association:create",
                "crm:vehicle_party_association:edit",
                // Vehicle preferences
                "crm:vehicle_preference:view",
                "crm:vehicle_preference:edit"));
        return set;
    }

    private Set<String> customerAuthorities() {
        Set<String> set = new HashSet<>(fleetManagerAuthorities());
        set.addAll(List.of(
                "crm:party:create",
                "crm:party:deactivate",
                "crm:party:merge",
                "crm:person:create",
                "crm:contact:delete",
                "crm:contact_role:revoke",
                // Vehicle deactivation moved to the registry (ADR-0044 §6, #843)
                "vehicle-inventory:registry:delete",
                "crm:integration:audit",
                "crm:billing_rules:edit",
                "crm:relationship:create",
                "crm:relationship:update",
                "crm:relationship:delete",
                "crm:promotion_redemption:record",
                "crm:promotion_redemption:view"));
        return set;
    }

    private Set<String> adminAuthorities() {
        Set<String> set = new HashSet<>(customerAuthorities());
        // Accounting — full controller hierarchy
        set.addAll(controllerAuthorities());
        // Security — full admin set
        set.addAll(adminSecurityAuthorities());
        // All other service domains
        set.addAll(locationAuthorities());
        set.addAll(bulkImportAuthorities());
        set.addAll(catalogAuthorities());
        set.addAll(inventoryAuthorities());
        set.addAll(orderAuthorities());
        set.addAll(workorderAuthorities());
        set.addAll(shopAuthorities());
        set.addAll(peopleAuthorities());
        set.addAll(pricingAuthorities());
        set.addAll(appointmentAuthorities());
        set.addAll(invoiceAuthorities());
        set.addAll(timekeepingAuthorities());
        set.addAll(timekeepingApprovalAuthorities());
        set.addAll(nltiAuthorities());
        set.addAll(productLifecycleAuthorities());
        set.addAll(mcpAuthorities());
        set.addAll(taxAuthorities());
        set.addAll(vehicleFitmentAuthorities());
        set.addAll(vehicleInventoryAuthorities());
        set.add("documents:render");
        // Workorder time-entry approval is an admin/manager-only operation,
        // intentionally NOT granted via workorderAuthorities() (which technicians inherit).
        set.add("workorder:timeEntry:approve");
        set.add("workorder:timeEntry:reject");
        return set;
    }

    private Set<String> generalManagerAuthorities() {
        return new HashSet<>(managerAuthorities());
    }

    private Set<String> managerAuthorities() {
        Set<String> authorities = new HashSet<>(interactiveChatAuthorities());
        authorities.addAll(List.of("security:role:view", "security:role:assign", "security:permission:view"));
        // Managers review, approve, and reject pay-period timekeeping for their staff.
        authorities.addAll(timekeepingApprovalAuthorities());
        // Managers manage and apply promotions and approve/reject workorder time entries.
        authorities.addAll(List.of(
                "pricing:promotion:view",
                "pricing:promotion:manage",
                "pricing:promotion:apply",
                "workorder:timeEntry:approve",
                "workorder:timeEntry:reject"));
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
                "security:audit:export",
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
                // Default Mappings
                "accounting:default-mapping:view",
                "accounting:default-mapping:create",
                "accounting:default-mapping:edit",
                // Mapping Keys
                "accounting:mapping-key:view",
                "accounting:mapping-key:create",
                "accounting:mapping-key:edit",
                // GL Mapping resolution
                "accounting:gl-mapping:create",
                // Posting Rules
                "accounting:posting_rules:view",
                "accounting:posting_rules:create",
                // Posting Categories (view only)
                "accounting:posting-category:view",
                // Journal Entries (view and create draft)
                "accounting:je:view",
                "accounting:je:create",
                // Credit Memos (read)
                "accounting:credit-memo:read",
                // Events
                "accounting:events:view",
                "accounting:events:submit",
                // Export
                "accounting:export:view",
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
        set.addAll(List.of(
                "accounting:ap:approve",
                "accounting:ap:reject",
                "accounting:ap:pay",
                "accounting:credit-memo:create",
                "accounting:payment:apply"));
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
                "accounting:events:retry",
                "accounting:events:reprocess",
                "accounting:payment:reverse",
                "accounting:posting-category:create",
                "accounting:posting-category:edit",
                "accounting:posting-category:deactivate",
                "accounting:default-mapping:delete",
                "accounting:mapping-key:deactivate",
                "accounting:gl-mapping:resolve",
                "reporting:view:financial-statements"));
        return set;
    }

    private Set<String> controllerAuthorities() {
        Set<String> set = new HashSet<>(accountantAuthorities());
        // Additional Controller permissions (includes ACCOUNTANT)
        set.addAll(List.of(
                "accounting:posting_rules:archive",
                "accounting:export:request",
                "accounting:report:export",
                "accounting:time:export"));
        return set;
    }

    private Set<String> accountingAssociateAuthorities() {
        Set<String> authorities = new HashSet<>(interactiveChatAuthorities());
        authorities.addAll(List.of(
                "accounting:coa:view",
                "accounting:mapping:view",
                "accounting:posting_rules:view",
                "accounting:je:view",
                "accounting:events:view",
                "accounting:export:view",
                "accounting:ap:view",
                "accounting:ap:approve",
                "accounting:ap:reject",
                "accounting:ap:pay"));
        return authorities;
    }

    private Set<String> accountManagerAuthorities() {
        Set<String> authorities = new HashSet<>(accountantAuthorities());
        authorities.addAll(List.of("invoice:manage", "invoice:billing-rules"));
        return authorities;
    }

    private Set<String> serviceAdvisorAuthorities() {
        Set<String> authorities = new HashSet<>(interactiveChatAuthorities());
        authorities.addAll(List.of(
                "inventory:availability:read",
                "appointments:view",
                "appointments:create",
                "appointments:reschedule",
                "appointments:cancel",
                "shop:location:view",
                "shop:bay:view",
                "shop:schedule:view",
                "workorder:workorder:view",
                "workorder:workorder:create",
                "workorder:workorder:approve",
                "workorder:workorder:complete",
                "workorder:workorder:generate_invoice",
                "workorder:wip:view",
                "workorder:estimate:view",
                "workorder:estimate:create",
                "workorder:estimate:edit",
                "workorder:estimate:calculate",
                "workorder:estimate:approve",
                "workorder:estimate:decline",
                "workorder:estimate:reopen",
                "workorder:estimate:submit",
                "workorder:estimate:promote",
                "workorder:estimate_item:view",
                "workorder:estimate_item:add",
                "workorder:estimate_item:edit",
                "workorder:estimate_item:delete",
                "workorder:estimate_snapshot:view",
                "workorder:estimate_snapshot:create",
                "workorder:change_request:view",
                "workorder:change_request:create",
                "workorder:change_request:approve",
                "workorder:change_request:decline",
                "workorder:labor:view",
                "workorder:parts:view",
                "workorder:invoice:view",
                "workorder:invoice:create",
                "invoice:manage",
                "invoice:finalize",
                // #704: service advisors view active promotions during a sale and
                // approve/reject pay-period time entries.
                "pricing:promotion:view",
                "workorder:timeEntry:approve",
                "workorder:timeEntry:reject",
                // Estimate-create vehicle panel (ADR-0044 §6, #843): list customer
                // vehicles from the CRM replica and register new ones in pos-vehicle-inventory.
                "crm:vehicle:view",
                "crm:vehicle:search",
                "vehicle-inventory:registry:view",
                "vehicle-inventory:registry:create"));
        return authorities;
    }

    private Set<String> locationManagerAuthorities() {
        Set<String> authorities = new HashSet<>(serviceAdvisorAuthorities());
        // Shop/location managers approve and reject their staff's pay-period timekeeping.
        authorities.addAll(timekeepingApprovalAuthorities());
        authorities.addAll(List.of(
                "people:availability:view",
                // Time management (self-service + team approval)
                "people:timeAdjustment:view",
                "people:timeAdjustment:create",
                "people:timeAdjustment:approve",
                "people:timeEntry:approve",
                "people:timeEntry:reject",
                "people:timeException:view",
                "people:timeException:create",
                "people:timeException:acknowledge",
                "people:timeException:resolve",
                "shop:location:create",
                "shop:location:edit",
                "shop:bay:create",
                "shop:bay:edit",
                "shop:bay:assign",
                "shop:schedule:edit",
                "inventory:pick_list:view",
                "inventory:pick_list:create",
                "inventory:pick_list:execute",
                "workorder:workorder:edit",
                "workorder:workorder:reopen_completed",
                "workorder:workorder:assign-technician",
                "workorder:timeEntry:approve",
                "workorder:timeEntry:reject",
                "workorder:dashboard:view",
                "workorder:change_request:emergency_override",
                "workorder:approval_config:view",
                "workorder:approval_config:create",
                "workorder:approval_config:edit",
                "workorder:parts:add",
                "workorder:parts:consume",
                "workorder:operationalContext:override",
                "workorder:start",
                "timekeeping:overlap_override"));
        return authorities;
    }

    private Set<String> technicianAuthorities() {
        Set<String> authorities = new HashSet<>(interactiveChatAuthorities());
        authorities.addAll(List.of(
                "inventory:availability:read",
                "shop:schedule:view",
                "workorder:workorder:view",
                "workorder:start",
                "workorder:estimate:view",
                "workorder:estimate_snapshot:view",
                "workorder:change_request:view",
                "workorder:change_request:create",
                "workorder:labor:view",
                "workorder:labor:add",
                "workorder:parts:view",
                "workorder:parts:add",
                "workorder:parts:consume",
                "inventory:pick_list:view",
                "inventory:pick_list:execute",
                "timekeeping:work_session:create",
                "timekeeping:work_session:stop",
                "timekeeping:work_session:break_start",
                "timekeeping:work_session:break_stop",
                // Time self-service
                "people:timeAdjustment:view",
                "people:timeAdjustment:create",
                "people:timeException:view",
                "people:timeException:create",
                "people:timeException:acknowledge"));
        return authorities;
    }

    // ============================================================================
    // Other Service Domain Authorities (admin-only at this time)
    // ============================================================================

    private Set<String> locationAuthorities() {
        return new HashSet<>(List.of(
                "location:read",
                "location:write",
                "location:bay:read",
                "location:bay:manage",
                "location:mobile-unit:read",
                "location:mobile-unit:manage",
                "location:service-area:read",
                "location:service-area:manage",
                "location:travel-buffer-policy:read",
                "location:travel-buffer-policy:manage"));
    }

    private Set<String> bulkImportAuthorities() {
        return new HashSet<>(List.of("bulkImport:status:read", "bulkImport:upload:execute"));
    }

    private Set<String> catalogAuthorities() {
        return new HashSet<>(List.of(
                "catalog:category:view",
                "catalog:category:create",
                "catalog:category:edit",
                "catalog:category:delete",
                "catalog:product:view",
                "catalog:product:create",
                "catalog:product:edit",
                "catalog:product:delete",
                "catalog:variant:view",
                "catalog:variant:create",
                "catalog:variant:edit",
                "catalog:service_type:view",
                "catalog:service_type:create",
                "catalog:service_type:edit",
                "catalog:price_book:read",
                "catalog:price_book:write",
                "catalog:msrp:read",
                "catalog:msrp:write",
                "catalog:supplier_cost:read",
                "catalog:supplier_cost:write"));
    }

    private Set<String> inventoryAuthorities() {
        return new HashSet<>(List.of(
                "inventory:availability:read",
                "inventory:on_hand:view",
                "inventory:on_hand:search",
                "inventory:ledger:view",
                "inventory:location:view",
                "inventory:location:admin",
                "inventory:adjustment:view",
                "inventory:adjustment:create",
                "inventory:adjustment:approve",
                "inventory:asn:view",
                "inventory:asn:create",
                "inventory:goods_receipt:view",
                "inventory:goods_receipt:create",
                "inventory:goods_receipt:override",
                "inventory:purchase_order:view",
                "inventory:purchase_order:create",
                "inventory:purchase_order:approve",
                "inventory:purchase_order:receive",
                "inventory:receiving:view",
                "inventory:receiving:create",
                "inventory:receiving:complete",
                "inventory:putaway:view",
                "inventory:putaway:generate",
                "inventory:putaway:claim",
                "inventory:putaway:execute",
                "inventory:putaway:override_location_capacity",
                "inventory:putaway:override_location_compatibility",
                "inventory:pick_list:view",
                "inventory:pick_list:create",
                "inventory:pick_list:execute",
                "inventory:cycle_count:view",
                "inventory:cycle_count:initiate",
                "inventory:cycle_count:complete",
                "inventory:shortage:view",
                "inventory:shortage:resolve",
                "inventory:stock_movement:create",
                "inventory:allocations:reallocate",
                "inventory:issue:parts",
                "inventory:return:view",
                "inventory:return:write",
                "inventory:override:part-match"));
    }

    private Set<String> orderAuthorities() {
        return new HashSet<>(List.of(
                "order:order:view",
                "order:order:create",
                "order:order:edit",
                "order:order:cancel",
                "order:line:view",
                "order:line:create",
                "order:line:edit",
                "order:line:delete",
                "order:line:enter_manual_price",
                "order:price_override:view",
                "order:price_override:apply",
                "order:price_override:approve",
                "order:price_override:reject"));
    }

    private Set<String> workorderAuthorities() {
        return new HashSet<>(List.of(
                "workorder:workorder:view",
                "workorder:workorder:create",
                "workorder:workorder:edit",
                "workorder:workorder:approve",
                "workorder:workorder:start",
                "workorder:start",
                "workorder:workorder:complete",
                "workorder:workorder:delete",
                "workorder:workorder:reopen_completed",
                "workorder:workorder:generate_invoice",
                "workorder:workorder:assign-technician",
                "workorder:wip:view",
                "workorder:wip:view_all_locations",
                "workorder:dashboard:view",
                "workorder:estimate:view",
                "workorder:estimate:create",
                "workorder:estimate:edit",
                "workorder:estimate:calculate",
                "workorder:estimate:approve",
                "workorder:estimate:decline",
                "workorder:estimate:submit",
                "workorder:estimate:promote",
                "workorder:estimate:delete",
                "workorder:estimate:reopen",
                "workorder:estimate_item:view",
                "workorder:estimate_item:add",
                "workorder:estimate_item:edit",
                "workorder:estimate_item:delete",
                "workorder:estimate_snapshot:view",
                "workorder:estimate_snapshot:create",
                "workorder:change_request:view",
                "workorder:change_request:create",
                "workorder:change_request:approve",
                "workorder:change_request:decline",
                "workorder:change_request:emergency_override",
                "workorder:operationalContext:override",
                "workorder:approval_config:view",
                "workorder:approval_config:create",
                "workorder:approval_config:edit",
                "workorder:approval_config:delete",
                "workorder:labor:view",
                "workorder:labor:add",
                "workorder:parts:view",
                "workorder:parts:add",
                "workorder:parts:consume",
                "workorder:invoice:view",
                "workorder:invoice:create"));
    }

    private Set<String> timekeepingAuthorities() {
        return new HashSet<>(List.of(
                "timekeeping:work_session:create",
                "timekeeping:work_session:stop",
                "timekeeping:work_session:break_start",
                "timekeeping:work_session:break_stop",
                "timekeeping:overlap_override"));
    }

    /**
     * Pay-period timekeeping approval authorities required by TimekeepingApprovalController
     * (people:timekeeping:*). Separate from the employee-facing work_session authorities above:
     * view lists every employee's entries; approve/reject are manager decisions.
     */
    private Set<String> timekeepingApprovalAuthorities() {
        return new HashSet<>(
                List.of("people:timekeeping:view", "people:timekeeping:approve", "people:timekeeping:reject"));
    }

    private Set<String> shopAuthorities() {
        return new HashSet<>(List.of(
                "shop:location:view",
                "shop:location:create",
                "shop:location:edit",
                "shop:location:deactivate",
                "shop:bay:view",
                "shop:bay:create",
                "shop:bay:edit",
                "shop:bay:assign",
                "shop:schedule:view",
                "shop:schedule:edit"));
    }

    private Set<String> peopleAuthorities() {
        return new HashSet<>(List.of(
                "people:availability:view",
                // Identity/link/access authorities moved to pos-people-contact with the
                // ADR-0044 Phase 3 split (#874/#875); the retired people:person/userLink/role
                // permissions are no longer registered.
                "people-contact:person:view",
                "people-contact:person:create",
                "people-contact:person:edit",
                "people-contact:person:delete",
                "people-contact:userLink:view",
                "people-contact:userLink:write",
                "people-contact:role:view",
                "people-contact:role:assign",
                "people-contact:role:revoke",
                "people:employee:view",
                "people:employee:create",
                "people:employee:edit",
                "people:employee:deactivate",
                "people:skill:view",
                "people:skill:edit",
                "people:skill:assign",
                "people:timeAdjustment:view",
                "people:timeAdjustment:create",
                "people:timeAdjustment:approve",
                "people:timeEntry:approve",
                "people:timeEntry:reject",
                "people:timeException:view",
                "people:timeException:create",
                "people:timeException:acknowledge",
                "people:timeException:resolve"));
    }

    private Set<String> pricingAuthorities() {
        return new HashSet<>(List.of(
                "pricing:price_book:view",
                "pricing:price_book:create",
                "pricing:price_book:edit",
                "pricing:price_book:delete",
                "pricing:rule:view",
                "pricing:rule:create",
                "pricing:rule:edit",
                "pricing:rule:delete",
                "pricing:base_price:create",
                "pricing:normalization:view",
                "pricing:normalization:edit",
                "pricing:restrictions:view",
                "pricing:restrictions:edit",
                "pricing:restriction:manage",
                "pricing:restriction:override",
                "pricing:override:approve",
                "pricing:promotion:view",
                "pricing:promotion:manage",
                "pricing:promotion:apply"));
    }

    private Set<String> appointmentAuthorities() {
        return new HashSet<>(
                List.of("appointments:view", "appointments:create", "appointments:reschedule", "appointments:cancel"));
    }

    private Set<String> invoiceAuthorities() {
        return new HashSet<>(List.of("invoice:manage", "invoice:finalize", "invoice:billing-rules"));
    }

    private Set<String> nltiAuthorities() {
        return new HashSet<>(List.of("nlti:request:read", "nlti:request:submit", "nlti:audit:read"));
    }

    private Set<String> productLifecycleAuthorities() {
        return new HashSet<>(List.of("product:lifecycle:update", "product:lifecycle:override_discontinued"));
    }

    private Set<String> mcpAuthorities() {
        return new HashSet<>(List.of(
                "mcp:chat:execute",
                "mcp:chat:stream",
                "mcp:document:ingest",
                "mcp:llm_api:view",
                "mcp:llm_api:create",
                "mcp:llm_api:update",
                "mcp:llm_api:delete",
                "mcp:system_prompt:view",
                "mcp:system_prompt:create",
                "mcp:system_prompt:update",
                "mcp:system_prompt:delete"));
    }

    private Set<String> taxAuthorities() {
        return new HashSet<>(List.of("tax:calculate", "tax:mode:view"));
    }

    private Set<String> vehicleFitmentAuthorities() {
        return new HashSet<>(List.of(
                "vehicle-fitment:catalog:view",
                "vehicle-fitment:hint:view",
                "vehicle-fitment:hint:create",
                "vehicle-fitment:hint:update",
                "vehicle-fitment:hint:delete"));
    }

    private Set<String> vehicleInventoryAuthorities() {
        return new HashSet<>(List.of(
                "vehicle-inventory:registry:view",
                "vehicle-inventory:registry:create",
                "vehicle-inventory:registry:update",
                "vehicle-inventory:registry:delete",
                "vehicle-inventory:preferences:manage",
                "vehicle-inventory:search:view"));
    }
}
